/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.endrit.gpusky;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import java.util.regex.Pattern;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WeatherRenderer
{
	private static final Logger log = LoggerFactory.getLogger(WeatherRenderer.class);
	private static final String VERTEX_SHADER = String.join("\n",
		"#version 330",
		"layout(location = 0) in vec3 position;",
		"layout(location = 1) in vec2 tex;",
		"layout(location = 2) in vec4 weatherA;",
		"layout(location = 3) in vec4 weatherB;",
		"layout(std140) uniform uniforms {",
		"  mat4 worldProj;",
		"  vec3 cameraPos;",
		"  float cameraYaw;",
		"  float cameraPitch;",
		"};",
		"out vec2 fragUv;",
		"out vec2 fragNextUv;",
		"out vec4 fragColor;",
		"out float fragFrameBlend;",
		"out float fragFootFade;",
		"void main() {",
		"  fragUv = tex;",
		"  fragNextUv = weatherA.xy;",
		"  fragColor = vec4(weatherA.zw, weatherB.xy);",
		"  fragFrameBlend = weatherB.z;",
		"  fragFootFade = weatherB.w;",
		"  gl_Position = worldProj * vec4(position, 1.0);",
		"}");
	private static final String FRAGMENT_SHADER = String.join("\n",
		"#version 330",
		"uniform sampler2D weatherTexture;",
		"uniform float tintStrength;",
		"in vec2 fragUv;",
		"in vec2 fragNextUv;",
		"in vec4 fragColor;",
		"in float fragFrameBlend;",
		"in float fragFootFade;",
		"out vec4 outColor;",
		"void main() {",
		"  vec4 texel = mix(texture(weatherTexture, fragUv), texture(weatherTexture, fragNextUv), fragFrameBlend);",
		"  float foot = clamp(fragFootFade, 0.0, 1.0);",
		"  float alpha = texel.a * fragColor.a * foot * foot * (3.0 - 2.0 * foot);",
		"  if (alpha <= 0.001) discard;",
		"  float brightness = max(max(texel.r, texel.g), texel.b);",
		"  vec3 textured = texel.rgb * fragColor.rgb;",
		"  vec3 tinted = fragColor.rgb * max(brightness, 0.28);",
		"  outColor = vec4(mix(textured, tinted, tintStrength), alpha);",
		"}");

	private static final int VERTEX_FLOATS = OpenGlContext.VERTEX_STRIDE_FLOATS;
	// Not unit 0: that is gpuSkyCurrent, which the scene shader samples on the next frame.
	private static final int WEATHER_TEXTURE_UNIT = 6;
	private static final int MAX_PARTICLES_PER_LAYER = 16384;
	private static final int MAX_BEAM_COLUMNS = 6;
	private static final int MAX_BEAM_ROWS = 8;
	/**
	 * Most vertices one particle can write: a terrain-following beam grid (6 per cell), else
	 * 18 for a soft-volume billboard. Space for this much is reserved before every particle.
	 * A new geometry path that writes more must raise this.
	 */
	static final int MAX_VERTICES_PER_PARTICLE = Math.max(18, MAX_BEAM_COLUMNS * MAX_BEAM_ROWS * 6);
	/** First allocation, made at the first weather draw (not at plugin start). */
	static final int INITIAL_BATCH_VERTICES = 16_384;
	/**
	 * A layer's vertices are drawn in batches of at most this many. The largest layer today
	 * writes about 62,400, so it stays one draw; past this the batch is drawn and the next one
	 * started, in the same particle order and with the same state, so it looks the same.
	 */
	static final int MAX_BATCH_VERTICES = 131_072;
	private static final int BEAM_PROXIMITY_COLUMNS = 4;
	private static final int BEAM_PROXIMITY_ROWS = 6;
	private static final int ATLAS_PADDING = 2;
	private static final float TWO_PI = (float) (Math.PI * 2.0);
	private static final float WIND_X = -850f;
	private static final float WIND_Z = -380f;
	private static final float WIND_LENGTH = (float) Math.sqrt(WIND_X * WIND_X + WIND_Z * WIND_Z);
	private static final float TILE_SIZE = Perspective.LOCAL_TILE_SIZE;
	private static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	private static final float FAINT_RAIN_ALPHA_SCALE = 0.25f;
	private static final float FAINT_PESTILENCE_RAIN_ALPHA_SCALE = 0.40f;
	private static final float MOONLIGHT_DRIFT_SPEED = 0.2f;
	private static final float FALL_LEAF_BRIGHTNESS = 1.2f;
	private static final float BEAM_EDGE_HIDDEN_FACING = 0.04f;
	private static final float BEAM_EDGE_FULL_FACING = 0.20f;

	// Grows on demand up to MAX_BATCH_VERTICES (about 6.8 MB); empty until weather draws.
	private float[] vertexBuffer = new float[0];
	private final float[] beamRowX0 = new float[MAX_BEAM_COLUMNS + 1];
	private final float[] beamRowY0 = new float[MAX_BEAM_COLUMNS + 1];
	private final float[] beamRowZ0 = new float[MAX_BEAM_COLUMNS + 1];
	private final float[] beamRowAlpha0 = new float[MAX_BEAM_COLUMNS + 1];
	private final float[] beamRowX1 = new float[MAX_BEAM_COLUMNS + 1];
	private final float[] beamRowY1 = new float[MAX_BEAM_COLUMNS + 1];
	private final float[] beamRowZ1 = new float[MAX_BEAM_COLUMNS + 1];
	private final float[] beamRowAlpha1 = new float[MAX_BEAM_COLUMNS + 1];
	private final EnumMap<Layer, SpriteSet> spriteSets = new EnumMap<>(Layer.class);
	// Client thread. Layers that load the same frames with the same processing share one texture.
	private final EnumMap<Layer, String> layerKeys = new EnumMap<>(Layer.class);
	private final Map<String, SharedTexture> sharedTextures = new HashMap<>();
	private final EnumMap<Layer, WeatherParticle[]> particles = new EnumMap<>(Layer.class);
	private final EnumMap<Layer, Integer> triggeredParticleIndices = new EnumMap<>(Layer.class);
	private final Random random = new Random(0x51A0CC0L);
	private GpuSkyExtensionConfig.WeatherEffect activeEffect;
	private long lastFrameNanos;
	private int lastViewportWidth = -1;
	private int lastViewportHeight = -1;
	private float lastAnchorGroundHeight = Float.NaN;
	private OpenGlContext gpu;
	private int program;
	private int buffer;
	private int vertexOffset;
	private float moonlightDirectionX;
	private float moonlightDirectionY = -1f;
	private float moonlightDirectionZ;
	private float lightWeatherOpacity = 1f;
	// Colour of the sky's light that the beams come from (Sunlight and Moonlight).
	private float lightRed = 1f;
	private float lightGreen = 1f;
	private float lightBlue = 1f;

	void start(OpenGlContext gpu)
	{
		this.gpu = gpu;
		program = gpu.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
		buffer = gpu.createBuffer();
	}

	void stop()
	{
		shutdownTextures();
		vertexBuffer = new float[0];
		gpu = null;
		program = 0;
		buffer = 0;
	}

	void shutdownTextures()
	{
		spriteSets.clear();
		layerKeys.clear();
		sharedTextures.clear(); // Deleted with the GL context that owns them.
		particles.clear();
		triggeredParticleIndices.clear();
		lastFrameNanos = 0;
		lastAnchorGroundHeight = Float.NaN;
	}

	static LoadedWeatherAssets loadAssets(GpuSkyExtensionConfig.WeatherEffect weatherEffect, Filepath directory,
		Filepath sharedDirectory, LoadedWeatherAssets previous)
	{
		return loadAssets(weatherEffect, directory, sharedDirectory, previous, new SpriteCache());
	}

	/** {@code cache} (one per loader session) makes layers and effects that use the same frames share one decode. */
	static LoadedWeatherAssets loadAssets(GpuSkyExtensionConfig.WeatherEffect weatherEffect, Filepath directory,
		Filepath sharedDirectory, LoadedWeatherAssets previous, SpriteCache cache)
	{
		LoadedWeatherAssets assets = new LoadedWeatherAssets(weatherEffect);
		if (previous != null)
		{
			for (Map.Entry<Layer, LoadedSpriteSet> entry : previous.spriteSets.entrySet())
			{
				// A stale set's pixels are gone with an old GPU context: decode it again.
				if (!entry.getValue().stale)
				{
					assets.spriteSets.put(entry.getKey(), entry.getValue());
				}
			}
		}
		for (Layer layer : Layer.values())
		{
			if (Thread.currentThread().isInterrupted())
			{
				throw new CancellationException("Weather loading interrupted");
			}
			if (!layerMatchesEffect(layer, weatherEffect) || assets.spriteSets.containsKey(layer))
			{
				continue;
			}
			try
			{
				LoadedSpriteSet spriteSet = loadSpriteSet(directory, sharedDirectory, layer, cache);
				if (spriteSet != null)
				{
					assets.spriteSets.put(layer, spriteSet);
				}
				else
				{
					assets.complete = false;
				}
			}
			catch (CancellationException ex)
			{
				throw ex;
			}
			catch (IOException | RuntimeException ex)
			{
				assets.complete = false;
				log.debug("Unable to load weather layer {}; will retry", layer, ex);
			}
		}
		return assets;
	}

	private static boolean usesMoonlightLayers(GpuSkyExtensionConfig.WeatherEffect effect)
	{
		return effect == GpuSkyExtensionConfig.WeatherEffect.MOONLIGHT
			|| effect == GpuSkyExtensionConfig.WeatherEffect.SUNLIGHT;
	}

	private static boolean layerMatchesEffect(Layer layer, GpuSkyExtensionConfig.WeatherEffect effect)
	{
		return layer.effect == effect
			|| usesMoonlightLayers(effect) && layer.effect == GpuSkyExtensionConfig.WeatherEffect.MOONLIGHT
			|| (effect == GpuSkyExtensionConfig.WeatherEffect.FALL || effect == GpuSkyExtensionConfig.WeatherEffect.DAY)
				&& (layer == Layer.SPRING_LEAVES || layer == Layer.SPRING_GROUND_LEAVES || layer == Layer.SPRING_MOTES);
	}

	boolean upload(LoadedWeatherAssets assets)
	{
		boolean complete = assets.complete;
		List<LoadedSpriteSet> uploaded = new ArrayList<>();
		for (Map.Entry<Layer, LoadedSpriteSet> entry : assets.spriteSets.entrySet())
		{
			Layer layer = entry.getKey();
			LoadedSpriteSet loaded = entry.getValue();
			if (java.util.Objects.equals(loaded.key, layerKeys.get(layer)) && spriteSets.containsKey(layer))
			{
				continue;
			}
			try
			{
				SharedTexture shared = sharedTextures.get(loaded.key);
				if (shared == null)
				{
					BufferedImage atlas = loaded.atlas;
					if (atlas == null)
					{
						// Its pixels were released after an earlier upload, and that texture is gone.
						loaded.stale = true;
						assets.foundStale = true;
						complete = false;
						continue;
					}
					int texture = gpu.createTexture(atlas, OpenGlContext.Wrap.CLAMP, OpenGlContext.Wrap.CLAMP,
						OpenGlContext.Filter.LINEAR, OpenGlContext.TextureOrigin.TOP_LEFT);
					shared = new SharedTexture(texture);
					sharedTextures.put(loaded.key, shared);
					uploaded.add(loaded);
				}
				// Count the new user before releasing the old one, so a shared texture is never deleted in use.
				shared.users++;
				releaseLayer(layer);
				spriteSets.put(layer, new SpriteSet(shared.texture, loaded.frames, loaded.displayWidth, loaded.displayHeight));
				layerKeys.put(layer, loaded.key);
				log.debug("Loaded {} weather frames for {}", loaded.frames.length, layer.assetPrefix);
			}
			catch (RuntimeException ex)
			{
				complete = false;
				log.debug("Unable to upload weather layer {}; will retry", layer, ex);
			}
		}
		// The pixels now live on the GPU. After a GPU restart the loader decodes them again.
		for (LoadedSpriteSet set : uploaded)
		{
			set.atlas = null;
		}
		return complete;
	}

	/** Drops a layer's texture use; the texture is deleted once no layer uses it. */
	private void releaseLayer(Layer layer)
	{
		String key = layerKeys.remove(layer);
		spriteSets.remove(layer);
		SharedTexture shared = key == null ? null : sharedTextures.get(key);
		if (shared != null && --shared.users <= 0)
		{
			sharedTextures.remove(key);
			gpu.destroyTexture(shared.texture);
		}
	}

	/** Number of distinct weather textures on the GPU, for tests and logging. */
	int textureCount()
	{
		return sharedTextures.size();
	}

	boolean activate(GpuSkyExtensionConfig.WeatherEffect effect)
	{
		for (Layer layer : Layer.values())
		{
			if (layerMatchesEffect(layer, effect) && spriteSets.containsKey(layer))
			{
				if (activeEffect != effect)
				{
					activeEffect = effect;
					particles.clear();
					lastFrameNanos = 0;
				}
				return true;
			}
		}
		return false;
	}

	void draw(GpuSkyExtensionConfig.WeatherEffect weatherEffect, int viewportWidth, int viewportHeight, int intensityPercent,
		float cameraX, float cameraY, float cameraZ, float cameraYaw, float cameraPitch,
		float anchorX, float anchorZ, Scene scene, byte[][][] tileSettings, int plane,
		float moonDirectionX, float moonDirectionY, float moonDirectionZ, float lightWeatherOpacity,
		float lightRed, float lightGreen, float lightBlue)
	{
		if (gpu == null || program == 0 || buffer == 0 || spriteSets.isEmpty() || activeEffect != weatherEffect
			|| viewportWidth <= 0 || viewportHeight <= 0)
		{
			return;
		}

		float intensity = LightBeams.weatherIntensity(usesMoonlightLayers(weatherEffect), intensityPercent);
		if (intensity <= 0f)
		{
			return;
		}
		setMoonlightDirection(moonDirectionX, moonDirectionY, moonDirectionZ);
		this.lightWeatherOpacity = lightWeatherOpacity;
		float[] beamColor = weatherEffect == GpuSkyExtensionConfig.WeatherEffect.SUNLIGHT
			? LightBeams.sunlightColor(lightRed, lightGreen, lightBlue)
			: new float[]{lightRed, lightGreen, lightBlue};
		this.lightRed = beamColor[0];
		this.lightGreen = beamColor[1];
		this.lightBlue = beamColor[2];

		float anchorGroundHeight = anchoredGroundHeight(scene, plane, anchorX, anchorZ, cameraX, cameraZ);
		float anchorGroundDelta = Float.isNaN(lastAnchorGroundHeight) ? 0f : anchorGroundHeight - lastAnchorGroundHeight;
		if (viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight || particles.isEmpty())
		{
			resetParticles(viewportWidth, viewportHeight, cameraX, cameraZ, anchorGroundHeight, scene, tileSettings, plane);
			anchorGroundDelta = 0f;
		}
		lastAnchorGroundHeight = anchorGroundHeight;

		long now = System.nanoTime();
		float deltaSeconds = lastFrameNanos == 0 ? 0f : Math.min((now - lastFrameNanos) / 1_000_000_000f, 0.05f);
		lastFrameNanos = now;

		gpu.useProgram(program);

		Billboard screenBillboard = cameraBillboard(cameraYaw, cameraPitch);
		if (usesMoonlightLayers(activeEffect))
		{
			drawMoonlight(screenBillboard, cameraX, cameraY, cameraZ, anchorGroundHeight, anchorGroundDelta,
				scene, tileSettings, plane, deltaSeconds, intensity);
			return;
		}
		for (Layer layer : Layer.values())
		{
			if (!layerMatchesEffect(layer, activeEffect))
			{
				continue;
			}

			drawLayer(layer, screenBillboard, cameraX, cameraY, cameraZ, anchorGroundHeight, anchorGroundDelta,
				scene, tileSettings, plane, deltaSeconds, intensity);
		}
	}

	private void drawLayer(Layer layer, Billboard screenBillboard, float cameraX, float cameraY, float cameraZ,
		float anchorGroundHeight, float anchorGroundDelta, Scene scene, byte[][][] tileSettings, int plane,
		float deltaSeconds, float intensity)
	{
		SpriteSet spriteSet = spriteSets.get(layer);
		WeatherParticle[] layerParticles = particles.get(layer);
		if (spriteSet == null || spriteSet.frames.length == 0 || layerParticles == null)
		{
			return;
		}

		int particleCount = layer.triggeredOnly()
			? Math.min(MAX_PARTICLES_PER_LAYER, layerParticles.length)
			: Math.min(MAX_PARTICLES_PER_LAYER, Math.min(layerParticles.length, Math.round(layer.baseCount * intensity)));
		if (particleCount <= 0)
		{
			return;
		}

		vertexOffset = 0;
		int vertices = 0;
		for (int i = 0; i < particleCount; i++)
		{
			WeatherParticle particle = layerParticles[i];
			updateParticle(layer, particle, screenBillboard, cameraX, cameraZ, anchorGroundHeight, anchorGroundDelta,
				scene, tileSettings, plane, deltaSeconds);
			if (particle.active)
			{
				vertices = reserveParticle(layer, spriteSet, vertices);
				vertices += putParticle(layer, spriteSet, particle, screenBillboard, cameraX, cameraY, cameraZ,
					anchorGroundHeight, scene, plane);
			}
		}
		drawBatch(layer, spriteSet, vertices);
	}

	/**
	 * Makes room for one more particle's worst case (MAX_VERTICES_PER_PARTICLE) after the
	 * {@code vertices} already written: grows the array, or, at MAX_BATCH_VERTICES, draws the
	 * batch so far and starts a new one. Returns the vertex count to continue from.
	 */
	private int reserveParticle(Layer layer, SpriteSet spriteSet, int vertices)
	{
		// From the write position itself, so the bound holds even if a count were off.
		int needed = vertexOffset / VERTEX_FLOATS + MAX_VERTICES_PER_PARTICLE;
		if ((long) needed * VERTEX_FLOATS <= vertexBuffer.length)
		{
			return vertices;
		}
		int capacity = batchCapacity(vertexBuffer.length / VERTEX_FLOATS, needed);
		if (capacity >= needed)
		{
			vertexBuffer = Arrays.copyOf(vertexBuffer, capacity * VERTEX_FLOATS);
			return vertices;
		}
		drawBatch(layer, spriteSet, vertices);
		vertexOffset = 0;
		return 0;
	}

	/** Next array size in vertices: doubling from INITIAL_BATCH_VERTICES, never past MAX_BATCH_VERTICES. */
	static int batchCapacity(int current, int needed)
	{
		int capacity = Math.max(INITIAL_BATCH_VERTICES, current);
		while (capacity < needed && capacity < MAX_BATCH_VERTICES)
		{
			capacity = Math.min(MAX_BATCH_VERTICES, capacity * 2);
		}
		return capacity;
	}

	private void drawBatch(Layer layer, SpriteSet spriteSet, int vertices)
	{
		if (vertices == 0)
		{
			return;
		}
		gpu.useProgram(program);
		gpu.bindTexture("weatherTexture", spriteSet.texture, WEATHER_TEXTURE_UNIT);
		gpu.uniform1f("tintStrength", layer.tintStrength());
		gpu.upload(buffer, vertexBuffer, vertices);
		gpu.draw(buffer, 0, vertices, layer.depth(), OpenGlContext.Blend.ALPHA);
	}

	private void drawMoonlight(Billboard screenBillboard, float cameraX, float cameraY, float cameraZ,
		float anchorGroundHeight, float anchorGroundDelta, Scene scene, byte[][][] tileSettings, int plane,
		float deltaSeconds, float intensity)
	{
		Layer sourceLayer = Layer.MOONLIGHT_AURORA;
		WeatherParticle[] layerParticles = particles.get(sourceLayer);
		if (layerParticles == null)
		{
			return;
		}
		int particleCount = Math.min(layerParticles.length, Math.round(sourceLayer.baseCount * intensity));
		for (int i = 0; i < particleCount; i++)
		{
			WeatherParticle particle = layerParticles[i];
			updateParticle(sourceLayer, particle, screenBillboard, cameraX, cameraZ, anchorGroundHeight, anchorGroundDelta,
				scene, tileSettings, plane, deltaSeconds);
		}

		drawLayer(Layer.MOONLIGHT_FILL, screenBillboard, cameraX, cameraY, cameraZ, anchorGroundHeight,
			anchorGroundDelta, scene, tileSettings, plane, deltaSeconds, intensity);
		drawMoonlightPass(Layer.MOONLIGHT_SMOKE, layerParticles, particleCount, screenBillboard,
			cameraX, cameraY, cameraZ, anchorGroundHeight, scene, plane);
		drawMoonlightPass(Layer.MOONLIGHT_BEAMS, layerParticles, particleCount, screenBillboard,
			cameraX, cameraY, cameraZ, anchorGroundHeight, scene, plane);
		drawMoonlightPass(Layer.MOONLIGHT_AURORA, layerParticles, particleCount, screenBillboard,
			cameraX, cameraY, cameraZ, anchorGroundHeight, scene, plane);
	}

	private void drawMoonlightPass(Layer layer, WeatherParticle[] layerParticles, int particleCount,
		Billboard screenBillboard, float cameraX, float cameraY, float cameraZ,
		float anchorGroundHeight, Scene scene, int plane)
	{
		SpriteSet spriteSet = spriteSets.get(layer);
		if (spriteSet == null || spriteSet.frames.length == 0)
		{
			return;
		}
		vertexOffset = 0;
		int vertices = 0;
		for (int i = 0; i < particleCount; i++)
		{
			WeatherParticle particle = layerParticles[i];
			if (particle.active)
			{
				vertices = reserveParticle(layer, spriteSet, vertices);
				vertices += putParticle(layer, spriteSet, particle, screenBillboard, cameraX, cameraY, cameraZ,
					anchorGroundHeight, scene, plane);
			}
		}
		drawBatch(layer, spriteSet, vertices);
	}

	private int putParticle(Layer layer, SpriteSet spriteSet, WeatherParticle particle,
		Billboard screenBillboard, float cameraX, float cameraY, float cameraZ,
		float anchorGroundHeight, Scene scene, int plane)
	{
		int frameIndex;
		int nextFrameIndex;
		float frameBlend;
		if (layer.fitAnimationToLifetime())
		{
			float animationProgress = Math.max(0f, Math.min(1f, particle.age / Math.max(0.001f, particle.lifetime)));
			float framePosition = animationProgress * (spriteSet.frames.length - 1);
			int frameFloor = (int) Math.floor(framePosition);
			frameIndex = Math.min(frameFloor, spriteSet.frames.length - 1);
			nextFrameIndex = Math.min(frameIndex + 1, spriteSet.frames.length - 1);
			frameBlend = framePosition - frameFloor;
		}
		else if (layer.animateFrames())
		{
			float framePosition = particle.age * particle.animationFps + particle.frameOffset;
			int frameFloor = (int) Math.floor(framePosition);
			frameIndex = Math.floorMod(frameFloor, spriteSet.frames.length);
			nextFrameIndex = (frameIndex + 1) % spriteSet.frames.length;
			frameBlend = framePosition - (float) Math.floor(framePosition);
			if (!layer.wrapAnimation() && frameIndex == spriteSet.frames.length - 1)
			{
				nextFrameIndex = frameIndex;
				frameBlend = 0f;
			}
		}
		else
		{
			frameIndex = Math.floorMod((int) particle.frameOffset, spriteSet.frames.length);
			nextFrameIndex = frameIndex;
			frameBlend = 0f;
		}

		SpriteFrame frame = spriteSet.frames[frameIndex];
		SpriteFrame nextFrame = spriteSet.frames[nextFrameIndex];
		float frameScale = frame.scale + (nextFrame.scale - frame.scale) * frameBlend;
		float frameAlphaScale = frame.alphaScale + (nextFrame.alphaScale - frame.alphaScale) * frameBlend;
		float frameAspect = spriteSet.displayWidth / (float) Math.max(1, spriteSet.displayHeight);
		float distanceX = particle.x - cameraX;
		float distanceZ = particle.z - cameraZ;
		float cameraDistance = (float) Math.sqrt(distanceX * distanceX + distanceZ * distanceZ);
		float unscaledHeight = particle.size * frameScale * lifetimeSizeScale(layer, particle, spriteSet.frames.length)
			* nearGroundScale(layer, particle, scene, plane, anchorGroundHeight);
		float height = unscaledHeight * layer.verticalScale();
		float width = unscaledHeight * frameAspect * layer.widthScale() * nearGroundWidthScale(layer, particle, scene, plane, anchorGroundHeight)
			* nearCameraWidthScale(layer, cameraDistance);
		float halfWidth = width * 0.5f;
		float halfHeight = height * 0.5f;
		float fade = fade(layer, particle.age, particle.lifetime);
		float alpha = particle.alpha * frameAlphaScale * layer.renderAlphaScale() * fade * nearGroundAlphaScale(layer, particle, scene, plane, anchorGroundHeight)
			* opacityWaveScale(layer, particle)
			* cameraProximityAlphaScale(layer, cameraDistance)
			* edgeFade(cameraDistance, layer.radius);
		if (layer.moonlightLayer())
		{
			alpha *= lightWeatherOpacity;
			if (activeEffect == GpuSkyExtensionConfig.WeatherEffect.SUNLIGHT)
			{
				alpha *= 3f;
			}
		}
		if (alpha <= 0.001f)
		{
			return 0;
		}

		float rotation = particle.rotation + layer.screenRotation();
		float cos = (float) Math.cos(rotation);
		float sin = (float) Math.sin(rotation);
		Billboard billboard;
		if (layer.groundAligned())
		{
			billboard = groundBillboard();
		}
		else if (layer.screenAligned())
		{
			billboard = screenBillboard;
		}
		else if (layer.velocityAligned())
		{
			billboard = velocityBillboard(particle, cameraX, cameraY, cameraZ, screenBillboard);
		}
		else if (layer.cylindricalBillboard())
		{
			billboard = cylindricalBillboard(particle, cameraX, cameraZ, screenBillboard);
		}
		else
		{
			billboard = screenBillboard;
		}
		float baseHeight = particle.size * frameScale * nearGroundScale(layer, particle, scene, plane, anchorGroundHeight);
		float growthOffset = layer.skywardGrowth() ? Math.max(0f, height - baseHeight) * 0.5f : 0f;
		alpha *= cameraIntersectionAlphaScale(layer, particle, billboard, cameraX, cameraY, cameraZ,
			halfWidth, halfHeight, growthOffset);
		if (alpha <= 0.001f)
		{
			return 0;
		}
		float bottomAlpha = alpha * layer.bottomVertexAlpha();
		int renderedVertices = 6;

		if (layer.softVolumeBillboard())
		{
			putQuad(particle, billboard, halfWidth, halfHeight, cos, sin, frame, nextFrame, frameBlend, layer, alpha * 0.42f, bottomAlpha * 0.42f);
			Billboard verticalBillboard = cylindricalBillboard(particle, cameraX, cameraZ, screenBillboard);
			putQuad(particle, verticalBillboard, halfWidth * 0.82f, halfHeight * 1.12f,
				(float) Math.cos(rotation + 0.55f), (float) Math.sin(rotation + 0.55f),
				frame, nextFrame, frameBlend, layer, alpha * 0.34f, bottomAlpha * 0.34f);
			putQuad(particle, crossedVerticalBillboard(verticalBillboard), halfWidth * 1.08f, halfHeight * 0.84f,
				(float) Math.cos(rotation - 0.48f), (float) Math.sin(rotation - 0.48f),
				frame, nextFrame, frameBlend, layer, alpha * 0.28f, bottomAlpha * 0.28f);
			return 18;
		}

		if (particle.trail)
		{
			Billboard trailBillboard = trailVelocityBillboard(particle, cameraX, cameraY, cameraZ, screenBillboard);
			putTrailQuad(particle, trailBillboard, halfWidth, halfWidth * 0.08f, halfHeight * 1.125f, halfHeight * 0.25f,
				frame, nextFrame, frameBlend, layer, alpha * 0.85f);
			putQuad(particle, billboard, halfWidth, halfHeight, cos, sin, frame, nextFrame, frameBlend, layer, alpha, bottomAlpha);
			return 12;
		}

		if (layer.skywardGrowth())
		{
			particle.x += billboard.upX * growthOffset;
			particle.y += billboard.upY * growthOffset;
			particle.z += billboard.upZ * growthOffset;
			float horizontal = (float) Math.sqrt(billboard.upX * billboard.upX + billboard.upZ * billboard.upZ);
			float bottomBiasX = 0f;
			float bottomBiasY = 0f;
			float bottomBiasZ = 0f;
			if (horizontal > 0.001f)
			{
				float elevation = (float) Math.atan2(-billboard.upY, horizontal);
				float biasedElevation = Math.min((float) Math.PI * 0.49f, elevation + (float) Math.toRadians(35f));
				float biasedHorizontal = (float) Math.cos(biasedElevation);
				float biasedUpX = billboard.upX / horizontal * biasedHorizontal;
				float biasedUpY = -(float) Math.sin(biasedElevation);
				float biasedUpZ = billboard.upZ / horizontal * biasedHorizontal;
				bottomBiasX = baseHeight * (billboard.upX - biasedUpX);
				bottomBiasY = baseHeight * (billboard.upY - biasedUpY);
				bottomBiasZ = baseHeight * (billboard.upZ - biasedUpZ);
			}
			renderedVertices = putTerrainFadedSkewedQuad(particle, billboard, halfWidth, halfHeight, cos, sin,
				frame, nextFrame, frameBlend, layer, alpha, bottomAlpha, bottomBiasX, bottomBiasY, bottomBiasZ,
				scene, plane, cameraX, cameraY, cameraZ);
			particle.x -= billboard.upX * growthOffset;
			particle.y -= billboard.upY * growthOffset;
			particle.z -= billboard.upZ * growthOffset;
		}
		else
		{
			putQuad(particle, billboard, halfWidth, halfHeight, cos, sin, frame, nextFrame, frameBlend, layer, alpha, bottomAlpha);
		}
		return renderedVertices;
	}

	private static float nearGroundScale(Layer layer, WeatherParticle particle, Scene scene, int plane, float anchorGroundHeight)
	{
		if (!layer.shrinkNearGround())
		{
			return 1f;
		}

		float groundHeight = particleGroundHeight(layer, particle, scene, plane, anchorGroundHeight);
		if (Float.isNaN(groundHeight))
		{
			return 1f;
		}

		float distanceToGround = Math.max(0f, groundHeight - particle.y);
		float scale = Math.max(0f, Math.min(1f, distanceToGround / (TILE_SIZE * 3f)));
		scale = scale * scale * (3f - 2f * scale);
		return 0.35f + scale * 0.65f;
	}

	private static float nearGroundWidthScale(Layer layer, WeatherParticle particle, Scene scene, int plane, float anchorGroundHeight)
	{
		if (!layer.thinNearGround())
		{
			return 1f;
		}

		float groundHeight = particleGroundHeight(layer, particle, scene, plane, anchorGroundHeight);
		if (Float.isNaN(groundHeight))
		{
			return 1f;
		}

		float distanceToGround = Math.max(0f, groundHeight - particle.y);
		float scale = Math.max(0f, Math.min(1f, distanceToGround / (TILE_SIZE * 3f)));
		scale = scale * scale * (3f - 2f * scale);
		return 0.24f + scale * 0.76f;
	}

	private static float nearCameraWidthScale(Layer layer, float distance)
	{
		if (!layer.rainStreakLayer())
		{
			return 1f;
		}

		float start = TILE_SIZE * 3f;
		float end = TILE_SIZE * 12f;
		float scale = Math.max(0f, Math.min(1f, (distance - start) / (end - start)));
		scale = scale * scale * (3f - 2f * scale);
		return 0.35f + scale * 0.65f;
	}

	private static float nearGroundAlphaScale(Layer layer, WeatherParticle particle, Scene scene, int plane, float anchorGroundHeight)
	{
		if (!layer.fadeOutNearGround())
		{
			return 1f;
		}

		float groundHeight = particleGroundHeight(layer, particle, scene, plane, anchorGroundHeight);
		if (Float.isNaN(groundHeight) || particle.initialGroundDistance <= 0f)
		{
			return 1f;
		}

		float distanceToGround = Math.max(0f, groundHeight - particle.y);
		float start = particle.initialGroundDistance * 0.70f;
		float end = particle.initialGroundDistance * 0.50f;
		if (distanceToGround >= start)
		{
			return 1f;
		}
		if (distanceToGround <= end)
		{
			return 0.04f;
		}

		float scale = (distanceToGround - end) / Math.max(1f, start - end);
		scale = scale * scale * (3f - 2f * scale);
		return 0.04f + scale * 0.96f;
	}

	private static float lifetimeSizeScale(Layer layer, WeatherParticle particle, int frameCount)
	{
		float progress = Math.max(0f, Math.min(1f, particle.age / Math.max(0.001f, particle.lifetime)));
		if (layer.moonlightLayer())
		{
			return 1f + smoothStep(progress);
		}
		if (!layer.impactRingLayer() || frameCount > 1)
		{
			return 1f;
		}

		float scale = progress * progress * (3f - 2f * progress);
		return 0.45f + scale * 0.80f;
	}

	private static float opacityWaveScale(Layer layer, WeatherParticle particle)
	{
		if (!layer.opacityWave())
		{
			return 1f;
		}

		float period = 3.8f;
		float pulseLength = 0.55f;
		float phase = (particle.age + particle.frameOffset * 0.37f) % period;
		if (phase >= pulseLength)
		{
			return 1f;
		}

		float t = phase / pulseLength;
		float dip = (float) Math.sin(t * Math.PI);
		return 1f - dip * 0.45f;
	}

	private static float cameraProximityAlphaScale(Layer layer, float cameraDistance)
	{
		if (!layer.cameraProximityFade())
		{
			return 1f;
		}

		float fade = smoothStep((cameraDistance - TILE_SIZE) / (TILE_SIZE * 5f));
		return 0.10f + fade * 0.90f;
	}

	private static float cameraIntersectionAlphaScale(Layer layer, WeatherParticle particle, Billboard billboard,
		float cameraX, float cameraY, float cameraZ, float halfWidth, float halfHeight, float growthOffset)
	{
		if (!layer.cameraProximityFade())
		{
			return 1f;
		}

		float centerX = particle.x + billboard.upX * growthOffset;
		float centerY = particle.y + billboard.upY * growthOffset;
		float centerZ = particle.z + billboard.upZ * growthOffset;
		float relativeX = cameraX - centerX;
		float relativeY = cameraY - centerY;
		float relativeZ = cameraZ - centerZ;
		float localRight = relativeX * billboard.rightX + relativeY * billboard.rightY + relativeZ * billboard.rightZ;
		float localUp = relativeX * billboard.upX + relativeY * billboard.upY + relativeZ * billboard.upZ;
		float normalX = relativeX - billboard.rightX * localRight - billboard.upX * localUp;
		float normalY = relativeY - billboard.rightY * localRight - billboard.upY * localUp;
		float normalZ = relativeZ - billboard.rightZ * localRight - billboard.upZ * localUp;
		float outsideRight = Math.max(0f, Math.abs(localRight) - halfWidth);
		float outsideUp = Math.max(0f, Math.abs(localUp) - halfHeight);
		float distance = (float) Math.sqrt(outsideRight * outsideRight + outsideUp * outsideUp
			+ normalX * normalX + normalY * normalY + normalZ * normalZ);
		return smoothStep((distance - TILE_SIZE * 0.5f) / (TILE_SIZE * 4f));
	}

	private void putQuad(WeatherParticle particle, Billboard billboard, float halfWidth, float halfHeight,
		float cos, float sin, SpriteFrame frame, SpriteFrame nextFrame, float frameBlend, Layer layer,
		float alpha, float bottomAlpha)
	{
		float bottomFootFade = layer.softFootFade() ? 0f : 1f;
		float tintStrength = layer.tintStrength();
		float red = layerRed(layer);
		float green = layerGreen(layer);
		float blue = layerBlue(layer);
		float leftU = particle.mirrorHorizontal ? frame.u1 : frame.u0;
		float rightU = particle.mirrorHorizontal ? frame.u0 : frame.u1;
		float nextLeftU = particle.mirrorHorizontal ? nextFrame.u1 : nextFrame.u0;
		float nextRightU = particle.mirrorHorizontal ? nextFrame.u0 : nextFrame.u1;
		putVertex(particle, billboard, -halfWidth, -halfHeight, cos, sin, leftU, frame.v1, nextLeftU, nextFrame.v1, frameBlend, red, green, blue, bottomAlpha, bottomFootFade, tintStrength);
		putVertex(particle, billboard, halfWidth, -halfHeight, cos, sin, rightU, frame.v1, nextRightU, nextFrame.v1, frameBlend, red, green, blue, bottomAlpha, bottomFootFade, tintStrength);
		putVertex(particle, billboard, halfWidth, halfHeight, cos, sin, rightU, frame.v0, nextRightU, nextFrame.v0, frameBlend, red, green, blue, alpha, 1f, tintStrength);
		putVertex(particle, billboard, -halfWidth, -halfHeight, cos, sin, leftU, frame.v1, nextLeftU, nextFrame.v1, frameBlend, red, green, blue, bottomAlpha, bottomFootFade, tintStrength);
		putVertex(particle, billboard, halfWidth, halfHeight, cos, sin, rightU, frame.v0, nextRightU, nextFrame.v0, frameBlend, red, green, blue, alpha, 1f, tintStrength);
		putVertex(particle, billboard, -halfWidth, halfHeight, cos, sin, leftU, frame.v0, nextLeftU, nextFrame.v0, frameBlend, red, green, blue, alpha, 1f, tintStrength);
	}

	private void putSkewedQuad(WeatherParticle particle, Billboard billboard, float halfWidth, float halfHeight,
		float cos, float sin, SpriteFrame frame, SpriteFrame nextFrame, float frameBlend, Layer layer,
		float alpha, float bottomAlpha, float bottomBiasX, float bottomBiasY, float bottomBiasZ)
	{
		float bottomFootFade = layer.softFootFade() ? 0f : 1f;
		float tintStrength = layer.tintStrength();
		float red = layerRed(layer);
		float green = layerGreen(layer);
		float blue = layerBlue(layer);
		float leftU = particle.mirrorHorizontal ? frame.u1 : frame.u0;
		float rightU = particle.mirrorHorizontal ? frame.u0 : frame.u1;
		float nextLeftU = particle.mirrorHorizontal ? nextFrame.u1 : nextFrame.u0;
		float nextRightU = particle.mirrorHorizontal ? nextFrame.u0 : nextFrame.u1;
		putSkewedVertex(particle, billboard, -halfWidth, -halfHeight, cos, sin, leftU, frame.v1, nextLeftU, nextFrame.v1, frameBlend, red, green, blue, bottomAlpha, bottomFootFade, tintStrength, bottomBiasX, bottomBiasY, bottomBiasZ);
		putSkewedVertex(particle, billboard, halfWidth, -halfHeight, cos, sin, rightU, frame.v1, nextRightU, nextFrame.v1, frameBlend, red, green, blue, bottomAlpha, bottomFootFade, tintStrength, bottomBiasX, bottomBiasY, bottomBiasZ);
		putSkewedVertex(particle, billboard, halfWidth, halfHeight, cos, sin, rightU, frame.v0, nextRightU, nextFrame.v0, frameBlend, red, green, blue, alpha, 1f, tintStrength, bottomBiasX, bottomBiasY, bottomBiasZ);
		putSkewedVertex(particle, billboard, -halfWidth, -halfHeight, cos, sin, leftU, frame.v1, nextLeftU, nextFrame.v1, frameBlend, red, green, blue, bottomAlpha, bottomFootFade, tintStrength, bottomBiasX, bottomBiasY, bottomBiasZ);
		putSkewedVertex(particle, billboard, halfWidth, halfHeight, cos, sin, rightU, frame.v0, nextRightU, nextFrame.v0, frameBlend, red, green, blue, alpha, 1f, tintStrength, bottomBiasX, bottomBiasY, bottomBiasZ);
		putSkewedVertex(particle, billboard, -halfWidth, halfHeight, cos, sin, leftU, frame.v0, nextLeftU, nextFrame.v0, frameBlend, red, green, blue, alpha, 1f, tintStrength, bottomBiasX, bottomBiasY, bottomBiasZ);
	}

	private int putTerrainFadedSkewedQuad(WeatherParticle particle, Billboard billboard, float halfWidth,
		float halfHeight, float cos, float sin, SpriteFrame frame, SpriteFrame nextFrame, float frameBlend,
		Layer layer, float alpha, float bottomAlpha, float bottomBiasX, float bottomBiasY, float bottomBiasZ,
		Scene scene, int plane, float cameraX, float cameraY, float cameraZ)
	{
		// The lower-end bias tilts the rendered plane away from the original billboard.
		float height = Math.max(0.001f, halfHeight * 2f);
		float facingAlpha = beamEdgeAlphaScale(billboard.rightX, billboard.rightY, billboard.rightZ,
			billboard.upX - bottomBiasX / height,
			billboard.upY - bottomBiasY / height,
			billboard.upZ - bottomBiasZ / height,
			cameraX - particle.x - bottomBiasX * 0.5f,
			cameraY - particle.y - bottomBiasY * 0.5f,
			cameraZ - particle.z - bottomBiasZ * 0.5f);
		alpha *= facingAlpha;
		bottomAlpha *= facingAlpha;
		if (Math.max(alpha, bottomAlpha) <= 0.001f)
		{
			return 0;
		}

		if (!beamNearTerrain(particle, billboard, halfWidth, halfHeight, cos, sin,
			bottomBiasX, bottomBiasY, bottomBiasZ, scene, plane))
		{
			putSkewedQuad(particle, billboard, halfWidth, halfHeight, cos, sin, frame, nextFrame, frameBlend,
				layer, alpha, bottomAlpha, bottomBiasX, bottomBiasY, bottomBiasZ);
			return 6;
		}

		int columns = Math.max(2, Math.min(MAX_BEAM_COLUMNS,
			(int) Math.ceil(halfWidth * 2f / (TILE_SIZE * 2f))));
		int rows = Math.max(3, Math.min(MAX_BEAM_ROWS,
			(int) Math.ceil(halfHeight * 2f / (TILE_SIZE * 2f))));
		float tintStrength = layer.tintStrength();
		float red = layerRed(layer);
		float green = layerGreen(layer);
		float blue = layerBlue(layer);
		fillBeamTerrainRow(beamRowX0, beamRowY0, beamRowZ0, beamRowAlpha0, particle, billboard,
			halfWidth, halfHeight, columns, 0f, cos, sin, alpha, bottomAlpha,
			bottomBiasX, bottomBiasY, bottomBiasZ, scene, plane);

		float[] lowerX = beamRowX0;
		float[] lowerY = beamRowY0;
		float[] lowerZ = beamRowZ0;
		float[] lowerAlpha = beamRowAlpha0;
		float[] upperX = beamRowX1;
		float[] upperY = beamRowY1;
		float[] upperZ = beamRowZ1;
		float[] upperAlpha = beamRowAlpha1;
		int vertices = 0;
		for (int row = 0; row < rows; row++)
		{
			float lowerFraction = row / (float) rows;
			float upperFraction = (row + 1f) / rows;
			fillBeamTerrainRow(upperX, upperY, upperZ, upperAlpha, particle, billboard,
				halfWidth, halfHeight, columns, upperFraction, cos, sin, alpha, bottomAlpha,
				bottomBiasX, bottomBiasY, bottomBiasZ, scene, plane);
			float lowerV = frame.v1 + (frame.v0 - frame.v1) * lowerFraction;
			float upperV = frame.v1 + (frame.v0 - frame.v1) * upperFraction;
			float nextLowerV = nextFrame.v1 + (nextFrame.v0 - nextFrame.v1) * lowerFraction;
			float nextUpperV = nextFrame.v1 + (nextFrame.v0 - nextFrame.v1) * upperFraction;
			for (int column = 0; column < columns; column++)
			{
				float leftFraction = column / (float) columns;
				float rightFraction = (column + 1f) / columns;
				float leftU = beamU(frame, particle.mirrorHorizontal, leftFraction);
				float rightU = beamU(frame, particle.mirrorHorizontal, rightFraction);
				float nextLeftU = beamU(nextFrame, particle.mirrorHorizontal, leftFraction);
				float nextRightU = beamU(nextFrame, particle.mirrorHorizontal, rightFraction);
				putBeamGridVertex(lowerX[column], lowerY[column], lowerZ[column], leftU, lowerV,
					nextLeftU, nextLowerV, frameBlend, red, green, blue, lowerAlpha[column], tintStrength);
				putBeamGridVertex(lowerX[column + 1], lowerY[column + 1], lowerZ[column + 1], rightU, lowerV,
					nextRightU, nextLowerV, frameBlend, red, green, blue, lowerAlpha[column + 1], tintStrength);
				putBeamGridVertex(upperX[column + 1], upperY[column + 1], upperZ[column + 1], rightU, upperV,
					nextRightU, nextUpperV, frameBlend, red, green, blue, upperAlpha[column + 1], tintStrength);
				putBeamGridVertex(lowerX[column], lowerY[column], lowerZ[column], leftU, lowerV,
					nextLeftU, nextLowerV, frameBlend, red, green, blue, lowerAlpha[column], tintStrength);
				putBeamGridVertex(upperX[column + 1], upperY[column + 1], upperZ[column + 1], rightU, upperV,
					nextRightU, nextUpperV, frameBlend, red, green, blue, upperAlpha[column + 1], tintStrength);
				putBeamGridVertex(upperX[column], upperY[column], upperZ[column], leftU, upperV,
					nextLeftU, nextUpperV, frameBlend, red, green, blue, upperAlpha[column], tintStrength);
				vertices += 6;
			}

			float[] swap = lowerX;
			lowerX = upperX;
			upperX = swap;
			swap = lowerY;
			lowerY = upperY;
			upperY = swap;
			swap = lowerZ;
			lowerZ = upperZ;
			upperZ = swap;
			swap = lowerAlpha;
			lowerAlpha = upperAlpha;
			upperAlpha = swap;
		}
		return vertices;
	}

	static float beamEdgeAlphaScale(float rightX, float rightY, float rightZ,
		float upX, float upY, float upZ, float viewX, float viewY, float viewZ)
	{
		float normalX = rightY * upZ - rightZ * upY;
		float normalY = rightZ * upX - rightX * upZ;
		float normalZ = rightX * upY - rightY * upX;
		float normalLengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ;
		float viewLengthSquared = viewX * viewX + viewY * viewY + viewZ * viewZ;
		if (normalLengthSquared < 0.000001f || viewLengthSquared < 0.000001f)
		{
			return 0f;
		}

		float facing = Math.abs(normalX * viewX + normalY * viewY + normalZ * viewZ)
			/ (float) Math.sqrt(normalLengthSquared * viewLengthSquared);
		// Suppress the thin projected line only within about 12 degrees of the beam plane.
		return smoothStep((facing - BEAM_EDGE_HIDDEN_FACING)
			/ (BEAM_EDGE_FULL_FACING - BEAM_EDGE_HIDDEN_FACING));
	}

	private static boolean beamNearTerrain(WeatherParticle particle, Billboard billboard, float halfWidth,
		float halfHeight, float cos, float sin, float bottomBiasX, float bottomBiasY, float bottomBiasZ,
		Scene scene, int plane)
	{
		for (int row = 0; row <= BEAM_PROXIMITY_ROWS; row++)
		{
			float localY = -halfHeight + halfHeight * 2f * row / BEAM_PROXIMITY_ROWS;
			for (int column = 0; column <= BEAM_PROXIMITY_COLUMNS; column++)
			{
				float localX = -halfWidth + halfWidth * 2f * column / BEAM_PROXIMITY_COLUMNS;
				float rotatedX = localX * cos - localY * sin;
				float rotatedY = localX * sin + localY * cos;
				float biasScale = Math.max(0f, Math.min(1f,
					(halfHeight - rotatedY) / Math.max(0.001f, halfHeight * 2f)));
				float x = particle.x + billboard.rightX * rotatedX + billboard.upX * rotatedY
					+ bottomBiasX * biasScale;
				float y = particle.y + billboard.rightY * rotatedX + billboard.upY * rotatedY
					+ bottomBiasY * biasScale;
				float z = particle.z + billboard.rightZ * rotatedX + billboard.upZ * rotatedY
					+ bottomBiasZ * biasScale;
				float ground = groundHeight(scene, plane, x, z);
				if (!Float.isNaN(ground) && ground - y < TILE_SIZE * 4f)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static float beamU(SpriteFrame frame, boolean mirrored, float fraction)
	{
		return mirrored
			? frame.u1 + (frame.u0 - frame.u1) * fraction
			: frame.u0 + (frame.u1 - frame.u0) * fraction;
	}

	private static void fillBeamTerrainRow(float[] xValues, float[] yValues, float[] zValues,
		float[] alphaValues, WeatherParticle particle, Billboard billboard, float halfWidth, float halfHeight,
		int columns, float verticalFraction, float cos, float sin, float alpha, float bottomAlpha,
		float bottomBiasX, float bottomBiasY, float bottomBiasZ, Scene scene, int plane)
	{
		float localY = -halfHeight + halfHeight * 2f * verticalFraction;
		for (int column = 0; column <= columns; column++)
		{
			float horizontalFraction = column / (float) columns;
			float localX = -halfWidth + halfWidth * 2f * horizontalFraction;
			float rotatedX = localX * cos - localY * sin;
			float rotatedY = localX * sin + localY * cos;
			float biasScale = Math.max(0f, Math.min(1f,
				(halfHeight - rotatedY) / Math.max(0.001f, halfHeight * 2f)));
			float x = particle.x + billboard.rightX * rotatedX + billboard.upX * rotatedY
				+ bottomBiasX * biasScale;
			float y = particle.y + billboard.rightY * rotatedX + billboard.upY * rotatedY
				+ bottomBiasY * biasScale;
			float z = particle.z + billboard.rightZ * rotatedX + billboard.upZ * rotatedY
				+ bottomBiasZ * biasScale;
			float ground = groundHeight(scene, plane, x, z);
			float terrainScale = Float.isNaN(ground) ? 1f
				: smoothStep((ground - y) / (TILE_SIZE * 2.2f));
			float baseAlpha = alpha + (bottomAlpha - alpha) * biasScale;
			xValues[column] = x;
			yValues[column] = y;
			zValues[column] = z;
			alphaValues[column] = baseAlpha * terrainScale;
		}
	}

	private void putBeamGridVertex(float x, float y, float z, float u, float v, float nextU, float nextV,
		float frameBlend, float red, float green, float blue, float alpha, float tintStrength)
	{
		vertexBuffer[vertexOffset++] = x;
		vertexBuffer[vertexOffset++] = y;
		vertexBuffer[vertexOffset++] = z;
		vertexBuffer[vertexOffset++] = u;
		vertexBuffer[vertexOffset++] = v;
		vertexBuffer[vertexOffset++] = nextU;
		vertexBuffer[vertexOffset++] = nextV;
		vertexBuffer[vertexOffset++] = red;
		vertexBuffer[vertexOffset++] = green;
		vertexBuffer[vertexOffset++] = blue;
		vertexBuffer[vertexOffset++] = alpha;
		vertexBuffer[vertexOffset++] = frameBlend;
		vertexBuffer[vertexOffset++] = 1f;
	}

	private float layerRed(Layer layer)
	{
		if (isFallLeaf(layer))
		{
			return FALL_LEAF_BRIGHTNESS;
		}
		// Sunlight and Moonlight take the colour of the sky's light they come from.
		return layer.moonlightLayer() ? lightRed : layer.red;
	}

	private float layerGreen(Layer layer)
	{
		if (isFallLeaf(layer))
		{
			return 0.42f * FALL_LEAF_BRIGHTNESS;
		}
		return layer.moonlightLayer() ? lightGreen : layer.green;
	}

	private float layerBlue(Layer layer)
	{
		if (isFallLeaf(layer))
		{
			return 0.08f * FALL_LEAF_BRIGHTNESS;
		}
		return layer.moonlightLayer() ? lightBlue : layer.blue;
	}

	private boolean isFallLeaf(Layer layer)
	{
		return activeEffect == GpuSkyExtensionConfig.WeatherEffect.FALL
			&& (layer == Layer.SPRING_LEAVES || layer == Layer.SPRING_GROUND_LEAVES);
	}

	private void putTrailQuad(WeatherParticle particle, Billboard billboard, float headHalfWidth, float tailHalfWidth,
		float tailLength, float headOverlap,
		SpriteFrame frame, SpriteFrame nextFrame, float frameBlend, Layer layer, float alpha)
	{
		float tintStrength = layer.tintStrength();
		float pulse = 0.5f + 0.5f * (float) Math.sin(particle.age * 14f + particle.frameOffset);
		float red = 1f;
		float green = 0.20f + pulse * 0.42f;
		float blue = 0.04f + pulse * 0.18f;
		float tailAlpha = Math.min(1f, alpha * (0.05f + pulse * 0.06f));
		float headAlpha = Math.min(1f, alpha * (0.95f + pulse * 0.30f));
		float uMid = (frame.u0 + frame.u1) * 0.5f;
		float nextUMid = (nextFrame.u0 + nextFrame.u1) * 0.5f;
		float uTailHalf = Math.abs(frame.u1 - frame.u0) * 0.08f;
		float nextUTailHalf = Math.abs(nextFrame.u1 - nextFrame.u0) * 0.08f;
		float vMid = (frame.v0 + frame.v1) * 0.5f;
		float nextVMid = (nextFrame.v0 + nextFrame.v1) * 0.5f;
		float tailY = -tailLength;
		float headY = headOverlap;
		putVertex(particle, billboard, -tailHalfWidth, tailY, 1f, 0f, uMid - uTailHalf, vMid, nextUMid - nextUTailHalf, nextVMid, frameBlend, red, green, blue, tailAlpha, 1f, tintStrength);
		putVertex(particle, billboard, tailHalfWidth, tailY, 1f, 0f, uMid + uTailHalf, vMid, nextUMid + nextUTailHalf, nextVMid, frameBlend, red, green, blue, tailAlpha, 1f, tintStrength);
		putVertex(particle, billboard, headHalfWidth, headY, 1f, 0f, frame.u1, vMid, nextFrame.u1, nextVMid, frameBlend, red, green, blue, headAlpha, 1f, tintStrength);
		putVertex(particle, billboard, -tailHalfWidth, tailY, 1f, 0f, uMid - uTailHalf, vMid, nextUMid - nextUTailHalf, nextVMid, frameBlend, red, green, blue, tailAlpha, 1f, tintStrength);
		putVertex(particle, billboard, headHalfWidth, headY, 1f, 0f, frame.u1, vMid, nextFrame.u1, nextVMid, frameBlend, red, green, blue, headAlpha, 1f, tintStrength);
		putVertex(particle, billboard, -headHalfWidth, headY, 1f, 0f, frame.u0, vMid, nextFrame.u0, nextVMid, frameBlend, red, green, blue, headAlpha, 1f, tintStrength);
	}

	static Billboard cameraBillboard(float cameraYaw, float cameraPitch)
	{
		// Invert the GPU view rotation: rotateX(pitch) * rotateY(yaw).
		float rightX = (float) Math.cos(cameraYaw);
		float rightY = 0f;
		float rightZ = (float) Math.sin(cameraYaw);

		float pitchCos = (float) Math.cos(cameraPitch);
		float viewX = -(float) Math.sin(cameraYaw) * pitchCos;
		float viewY = (float) Math.sin(cameraPitch);
		float viewZ = (float) Math.cos(cameraYaw) * pitchCos;

		float upX = viewY * rightZ - viewZ * rightY;
		float upY = viewZ * rightX - viewX * rightZ;
		float upZ = viewX * rightY - viewY * rightX;
		float upLength = (float) Math.sqrt(upX * upX + upY * upY + upZ * upZ);
		if (upLength < 0.001f)
		{
			upX = 0f;
			upY = 1f;
			upZ = 0f;
			upLength = 1f;
		}

		return new Billboard(
			rightX, rightY, rightZ,
			upX / upLength, upY / upLength, upZ / upLength);
	}

	private static Billboard cylindricalBillboard(WeatherParticle particle, float cameraX, float cameraZ,
		Billboard fallback)
	{
		float normalX = cameraX - particle.x;
		float normalZ = cameraZ - particle.z;
		float normalLength = (float) Math.sqrt(normalX * normalX + normalZ * normalZ);
		if (normalLength < 0.001f)
		{
			return fallback;
		}

		normalX /= normalLength;
		normalZ /= normalLength;
		return new Billboard(normalZ, 0f, -normalX, 0f, 1f, 0f);
	}

	private static Billboard groundBillboard()
	{
		return new Billboard(1f, 0f, 0f, 0f, 0f, 1f);
	}

	private static Billboard crossedVerticalBillboard(Billboard verticalBillboard)
	{
		return new Billboard(-verticalBillboard.rightZ, 0f, verticalBillboard.rightX, 0f, 1f, 0f);
	}

	private static Billboard velocityBillboard(WeatherParticle particle, float cameraX, float cameraY, float cameraZ,
		Billboard fallback)
	{
		return velocityBillboard(particle, cameraX, cameraY, cameraZ, fallback,
			particle.velocityX, particle.velocityY, particle.velocityZ);
	}

	private static Billboard trailVelocityBillboard(WeatherParticle particle, float cameraX, float cameraY, float cameraZ,
		Billboard fallback)
	{
		float upX = particle.velocityX;
		float upZ = particle.velocityZ;
		float horizontalLength = (float) Math.sqrt(upX * upX + upZ * upZ);
		if (horizontalLength > 0.001f)
		{
			float wobbleVelocity = trailWobbleVelocity(particle);
			upX += -particle.velocityZ / horizontalLength * wobbleVelocity;
			upZ += particle.velocityX / horizontalLength * wobbleVelocity;
		}

		return velocityBillboard(particle, cameraX, cameraY, cameraZ, fallback,
			upX, particle.velocityY, upZ);
	}

	private static float trailWobbleVelocity(WeatherParticle particle)
	{
		return (float) Math.sin(particle.age * 10f + particle.frameOffset * 1.7f) * particle.size * 1.584f;
	}

	private static Billboard velocityBillboard(WeatherParticle particle, float cameraX, float cameraY, float cameraZ,
		Billboard fallback, float upX, float upY, float upZ)
	{
		float upLength = (float) Math.sqrt(upX * upX + upY * upY + upZ * upZ);
		if (upLength < 0.001f)
		{
			return fallback;
		}
		upX /= upLength;
		upY /= upLength;
		upZ /= upLength;

		float viewX = cameraX - particle.x;
		float viewY = cameraY - particle.y;
		float viewZ = cameraZ - particle.z;
		float viewLength = (float) Math.sqrt(viewX * viewX + viewY * viewY + viewZ * viewZ);
		if (viewLength < 0.001f)
		{
			return fallback;
		}
		viewX /= viewLength;
		viewY /= viewLength;
		viewZ /= viewLength;

		float rightX = viewY * upZ - viewZ * upY;
		float rightY = viewZ * upX - viewX * upZ;
		float rightZ = viewX * upY - viewY * upX;
		float rightLength = (float) Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
		if (rightLength < 0.001f)
		{
			rightX = upZ;
			rightY = 0f;
			rightZ = -upX;
			rightLength = (float) Math.sqrt(rightX * rightX + rightZ * rightZ);
			if (rightLength < 0.001f)
			{
				rightX = 1f;
				rightY = 0f;
				rightZ = 0f;
				rightLength = 1f;
			}
		}

		return new Billboard(
			rightX / rightLength, rightY / rightLength, rightZ / rightLength,
			upX, upY, upZ);
	}

	private void putVertex(WeatherParticle particle, Billboard billboard, float localX, float localY,
		float cos, float sin, float u, float v, float nextU, float nextV, float frameBlend,
		float red, float green, float blue, float alpha, float footFade, float tintStrength)
	{
		float rotatedX = localX * cos - localY * sin;
		float rotatedY = localX * sin + localY * cos;
		float x = particle.x + billboard.rightX * rotatedX + billboard.upX * rotatedY;
		float y = particle.y + billboard.rightY * rotatedX + billboard.upY * rotatedY;
		float z = particle.z + billboard.rightZ * rotatedX + billboard.upZ * rotatedY;
		vertexBuffer[vertexOffset++] = x;
		vertexBuffer[vertexOffset++] = y;
		vertexBuffer[vertexOffset++] = z;
		vertexBuffer[vertexOffset++] = u;
		vertexBuffer[vertexOffset++] = v;
		vertexBuffer[vertexOffset++] = nextU;
		vertexBuffer[vertexOffset++] = nextV;
		vertexBuffer[vertexOffset++] = red;
		vertexBuffer[vertexOffset++] = green;
		vertexBuffer[vertexOffset++] = blue;
		vertexBuffer[vertexOffset++] = alpha;
		vertexBuffer[vertexOffset++] = frameBlend;
		vertexBuffer[vertexOffset++] = footFade;
	}

	private void putSkewedVertex(WeatherParticle particle, Billboard billboard, float localX, float localY,
		float cos, float sin, float u, float v, float nextU, float nextV, float frameBlend,
		float red, float green, float blue, float alpha, float footFade, float tintStrength,
		float bottomBiasX, float bottomBiasY, float bottomBiasZ)
	{
		float rotatedX = localX * cos - localY * sin;
		float rotatedY = localX * sin + localY * cos;
		float bottom = rotatedY < 0f ? 1f : 0f;
		float x = particle.x + billboard.rightX * rotatedX + billboard.upX * rotatedY + bottomBiasX * bottom;
		float y = particle.y + billboard.rightY * rotatedX + billboard.upY * rotatedY + bottomBiasY * bottom;
		float z = particle.z + billboard.rightZ * rotatedX + billboard.upZ * rotatedY + bottomBiasZ * bottom;
		vertexBuffer[vertexOffset++] = x;
		vertexBuffer[vertexOffset++] = y;
		vertexBuffer[vertexOffset++] = z;
		vertexBuffer[vertexOffset++] = u;
		vertexBuffer[vertexOffset++] = v;
		vertexBuffer[vertexOffset++] = nextU;
		vertexBuffer[vertexOffset++] = nextV;
		vertexBuffer[vertexOffset++] = red;
		vertexBuffer[vertexOffset++] = green;
		vertexBuffer[vertexOffset++] = blue;
		vertexBuffer[vertexOffset++] = alpha;
		vertexBuffer[vertexOffset++] = frameBlend;
		vertexBuffer[vertexOffset++] = footFade;
	}

	private void resetParticles(int viewportWidth, int viewportHeight, float cameraX, float cameraZ,
		float anchorGroundHeight, Scene scene, byte[][][] tileSettings, int plane)
	{
		lastViewportWidth = viewportWidth;
		lastViewportHeight = viewportHeight;
		particles.clear();
		triggeredParticleIndices.clear();
		random.setSeed(0x51A0CC0L);

		for (Layer layer : Layer.values())
		{
			if (!layerMatchesEffect(layer, activeEffect))
			{
				continue;
			}

			// Room for the highest intensity: 200%, which Sunlight/Moonlight count double.
			WeatherParticle[] layerParticles = new WeatherParticle[Math.round(layer.baseCount
				* LightBeams.weatherIntensity(usesMoonlightLayers(activeEffect), 200))];
			particles.put(layer, layerParticles);
			for (int i = 0; i < layerParticles.length; i++)
			{
				WeatherParticle particle = new WeatherParticle();
				if (layer.triggeredOnly())
				{
					deactivateParticle(particle);
				}
				else
				{
					resetParticle(layer, particle, cameraX, cameraZ, anchorGroundHeight, scene, tileSettings, plane, true);
				}
				layerParticles[i] = particle;
			}
			if (layer.triggeredOnly())
			{
				triggeredParticleIndices.put(layer, 0);
			}
		}
	}

	private void updateParticle(Layer layer, WeatherParticle particle, Billboard screenBillboard, float cameraX, float cameraZ,
		float anchorGroundHeight, float anchorGroundDelta, Scene scene, byte[][][] tileSettings, int plane, float deltaSeconds)
	{
		if (!particle.active)
		{
			if (!layer.triggeredOnly())
			{
				particle.spawnRetrySeconds -= deltaSeconds;
				if (particle.spawnRetrySeconds <= 0f)
				{
					resetParticle(layer, particle, cameraX, cameraZ, anchorGroundHeight, scene, tileSettings, plane, false);
				}
			}
			return;
		}

		if (layer.playerAnchoredHeight() && anchorGroundDelta != 0f)
		{
			particle.y += anchorGroundDelta;
		}
		if (layer.moonlightLayer())
		{
			particle.velocityX = moonlightDirectionX * MOONLIGHT_DRIFT_SPEED;
			particle.velocityY = moonlightDirectionY * MOONLIGHT_DRIFT_SPEED;
			particle.velocityZ = moonlightDirectionZ * MOONLIGHT_DRIFT_SPEED;
		}
		particle.age += deltaSeconds;
		if (layer.screenDriftScale() != 0f)
		{
			float screenDrift = particle.velocityY * layer.screenDriftScale() * deltaSeconds;
			particle.x += screenBillboard.rightX * screenDrift;
			particle.z += screenBillboard.rightZ * screenDrift;
		}
		else
		{
			particle.x += particle.velocityX * deltaSeconds;
			particle.z += particle.velocityZ * deltaSeconds;
		}
		particle.y += particle.velocityY * deltaSeconds;
		if (particle.trail)
		{
			float horizontalLength = (float) Math.sqrt(particle.velocityX * particle.velocityX + particle.velocityZ * particle.velocityZ);
			if (horizontalLength > 0.001f)
			{
				float wobbleVelocity = trailWobbleVelocity(particle);
				particle.x += -particle.velocityZ / horizontalLength * wobbleVelocity * deltaSeconds;
				particle.z += particle.velocityX / horizontalLength * wobbleVelocity * deltaSeconds;
			}
		}
		float flutterStrength = layer.flutterStrength();
		if (flutterStrength != 0f)
		{
			float phase = particle.age * layer.flutterFrequency() + particle.frameOffset * 0.61f;
			float flutterVelocity = (float) Math.sin(phase) * particle.size * flutterStrength;
			particle.x += -WIND_Z / WIND_LENGTH * flutterVelocity * deltaSeconds;
			particle.z += WIND_X / WIND_LENGTH * flutterVelocity * deltaSeconds;
		}
		particle.rotation += particle.spin * deltaSeconds;

		float dx = particle.x - cameraX;
		float dz = particle.z - cameraZ;
		boolean hitGround = reachedGround(layer, particle, scene, plane, anchorGroundHeight);
		if (particle.age >= particle.lifetime
			|| dx * dx + dz * dz > layer.radius * layer.radius
			|| hitGround)
		{
			if (hitGround && layer.rainImpactSource())
			{
				spawnRainImpact(layer, particle.x, particle.z, scene, tileSettings, plane);
			}

			if (layer.triggeredOnly())
			{
				deactivateParticle(particle);
			}
			else
			{
				resetParticle(layer, particle, cameraX, cameraZ, anchorGroundHeight, scene, tileSettings, plane, false);
			}
		}
	}

	private void setMoonlightDirection(float x, float y, float z)
	{
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		if (length < 0.001f)
		{
			moonlightDirectionX = 0f;
			moonlightDirectionY = -1f;
			moonlightDirectionZ = 0f;
			return;
		}
		moonlightDirectionX = x / length;
		moonlightDirectionY = y / length;
		moonlightDirectionZ = z / length;
	}

	private void spawnRainImpact(Layer rainLayer, float x, float z, Scene scene, byte[][][] tileSettings, int plane)
	{
		Layer impactLayer = rainLayer == Layer.PESTILENCE_RAIN ? Layer.PESTILENCE_RAIN_IMPACT : Layer.RAIN_IMPACT;
		if (random.nextFloat() > impactLayer.triggerChance())
		{
			return;
		}

		spawnTriggeredParticle(impactLayer, x, z, scene, tileSettings, plane);
	}

	private void spawnTriggeredParticle(Layer layer, float x, float z, Scene scene, byte[][][] tileSettings, int plane)
	{
		WeatherParticle[] layerParticles = particles.get(layer);
		if (layerParticles == null || layerParticles.length == 0)
		{
			return;
		}

		float groundHeight = groundHeight(scene, plane, x, z);
		if (Float.isNaN(groundHeight) || isUnderRoof(tileSettings, plane, x, z))
		{
			return;
		}

		int particleIndex = triggeredParticleIndices.getOrDefault(layer, 0);
		WeatherParticle particle = layerParticles[particleIndex % layerParticles.length];
		triggeredParticleIndices.put(layer, (particleIndex + 1) % layerParticles.length);
		placeParticle(layer, particle, x, z, groundHeight, false);
	}

	private static boolean reachedGround(Layer layer, WeatherParticle particle, Scene scene, int plane, float anchorGroundHeight)
	{
		if (!layer.resetAtGround())
		{
			return false;
		}

		float groundHeight = particleGroundHeight(layer, particle, scene, plane, anchorGroundHeight);
		return !Float.isNaN(groundHeight) && particle.y >= groundHeight - particle.size * 0.35f;
	}

	private void resetParticle(Layer layer, WeatherParticle particle, float cameraX, float cameraZ,
		float anchorGroundHeight, Scene scene, byte[][][] tileSettings, int plane, boolean initial)
	{
		for (int attempt = 0; attempt < 8; attempt++)
		{
			float[] point = spawnPoint(layer, initial, cameraX, cameraZ);
			float groundHeight = groundHeight(scene, plane, point[0], point[1]);
			if (Float.isNaN(groundHeight) || isUnderRoof(tileSettings, plane, point[0], point[1]))
			{
				continue;
			}

			placeParticle(layer, particle, point[0], point[1],
				layer.playerAnchoredHeight() ? anchorGroundHeight : groundHeight, initial);
			return;
		}

		// Stay invisible if every candidate is roofed/invalid; stagger retries when moving outdoors.
		deactivateParticle(particle);
		particle.spawnRetrySeconds = random(0.5f, 1.5f);
	}

	private float[] spawnPoint(Layer layer, boolean initial, float cameraX, float cameraZ)
	{
		float minimumRadius = layer.minimumSpawnRadiusScale() * layer.radius;
		if (layer.innerCoverage() && random.nextFloat() < 0.60f)
		{
			return randomPointInAnnulus(cameraX, cameraZ, minimumRadius, layer.radius * 0.60f);
		}
		return initial || !layer.spawnUpwind()
			? randomPointInAnnulus(cameraX, cameraZ, minimumRadius, layer.radius)
			: randomUpwindPoint(cameraX, cameraZ, layer.radius);
	}

	private void placeParticle(Layer layer, WeatherParticle particle, float x, float z, float groundHeight, boolean initial)
	{
		float sizeScale = random(layer.minSize, layer.maxSize);
		if (layer.avoidLargeNeighbors() && sizeScale >= layer.largeSizeThreshold()
			&& (random.nextFloat() < 0.20f || hasLargeNeighbor(layer, particle, x, z, sizeScale)))
		{
			sizeScale = random(layer.minSize, layer.largeSizeThreshold());
		}

		particle.active = true;
		particle.x = x;
		particle.z = z;
		particle.size = sizeScale;
		particle.initialGroundDistance = random(layer.minHeight, layer.maxHeight) + particle.size * layer.groundLift();
		particle.y = groundHeight - particle.initialGroundDistance;
		particle.velocityX = WIND_X * layer.windScale + random(-layer.turbulence, layer.turbulence);
		particle.velocityZ = WIND_Z * layer.windScale + random(-layer.turbulence, layer.turbulence);
		particle.velocityY = random(layer.minVelocityY, layer.maxVelocityY);
		particle.trail = layer.trailCandidate() && random.nextFloat() < 0.16f;
		if (particle.trail)
		{
			particle.size = Math.min(particle.size, layer.maxSize * 0.9f);
			float speedScale = 2.15f;
			particle.velocityX *= speedScale;
			particle.velocityY *= speedScale;
			particle.velocityZ *= speedScale;
		}
		particle.alpha = random(layer.minAlpha, layer.maxAlpha);
		particle.rotation = random(-layer.maxInitialRotation, layer.maxInitialRotation);
		particle.mirrorHorizontal = false;
		if (layer.discreteTextureOrientation())
		{
			int orientation = random.nextInt(3);
			particle.mirrorHorizontal = orientation == 1;
			particle.rotation = orientation == 2 ? (float) Math.PI : 0f;
		}
		particle.spin = random(layer.minSpin, layer.maxSpin);
		particle.animationFps = random(layer.minAnimationFps, layer.maxAnimationFps);
		particle.frameOffset = random(0f, 64f);
		particle.lifetime = random(layer.minLifetime, layer.maxLifetime);
		particle.age = initial ? random(0f, particle.lifetime) : 0f;
		if (layer.rainStreakLayer() && random.nextBoolean())
		{
			particle.alpha *= layer.faintRainAlphaScale();
		}
	}

	private boolean hasLargeNeighbor(Layer layer, WeatherParticle particle, float x, float z, float size)
	{
		WeatherParticle[] layerParticles = particles.get(layer);
		if (layerParticles == null)
		{
			return false;
		}

		float threshold = layer.largeSizeThreshold();
		for (WeatherParticle other : layerParticles)
		{
			if (other == null || other == particle || !other.active || other.size < threshold)
			{
				continue;
			}

			float dx = other.x - x;
			float dz = other.z - z;
			float minDistance = Math.max(TILE_SIZE * 8f, (size + other.size) * 0.35f);
			if (dx * dx + dz * dz < minDistance * minDistance)
			{
				return true;
			}
		}
		return false;
	}

	private static void deactivateParticle(WeatherParticle particle)
	{
		particle.active = false;
		particle.age = 0f;
		particle.lifetime = 0f;
		particle.alpha = 0f;
	}

	private float[] randomPointInRadius(float cameraX, float cameraZ, float radius)
	{
		return randomPointInAnnulus(cameraX, cameraZ, 0f, radius);
	}

	private float[] randomPointInAnnulus(float cameraX, float cameraZ, float minimumRadius, float radius)
	{
		float angle = random(0f, TWO_PI);
		float minimumRadiusSquared = minimumRadius * minimumRadius;
		float distance = (float) Math.sqrt(minimumRadiusSquared
			+ random.nextFloat() * (radius * radius - minimumRadiusSquared));
		return new float[]{
			cameraX + (float) Math.cos(angle) * distance,
			cameraZ + (float) Math.sin(angle) * distance
		};
	}

	private float[] randomUpwindPoint(float cameraX, float cameraZ, float radius)
	{
		float windX = WIND_X / WIND_LENGTH;
		float windZ = WIND_Z / WIND_LENGTH;
		float sideX = -windZ;
		float sideZ = windX;
		float distance = random(radius * 0.18f, radius * 0.96f);
		float maxLateral = (float) Math.sqrt(Math.max(0f, radius * radius - distance * distance));
		float lateral = random(-maxLateral, maxLateral);
		float jitter = random(-TILE_SIZE * 3f, TILE_SIZE * 3f);
		return new float[]{
			cameraX - windX * distance + sideX * lateral + windX * jitter,
			cameraZ - windZ * distance + sideZ * lateral + windZ * jitter
		};
	}

	private static float anchoredGroundHeight(Scene scene, int plane, float anchorX, float anchorZ, float cameraX, float cameraZ)
	{
		float groundHeight = groundHeight(scene, plane, anchorX, anchorZ);
		if (Float.isNaN(groundHeight))
		{
			groundHeight = groundHeight(scene, plane, cameraX, cameraZ);
		}
		return Float.isNaN(groundHeight) ? 0f : groundHeight;
	}

	private static float particleGroundHeight(Layer layer, WeatherParticle particle, Scene scene, int plane, float anchorGroundHeight)
	{
		return layer.playerAnchoredHeight()
			? anchorGroundHeight
			: groundHeight(scene, plane, particle.x, particle.z);
	}

	private static float groundHeight(Scene scene, int plane, float x, float z)
	{
		if (scene == null)
		{
			return Float.NaN;
		}

		return groundHeight(scene.getTileHeights(), scene.getExtendedTileSettings(),
			scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0, plane, x, z);
	}

	static float groundHeight(int[][][] tileHeights, byte[][][] extendedTileSettings,
		int sceneOffset, int plane, float x, float z)
	{
		if (tileHeights == null || plane < 0 || plane >= tileHeights.length)
		{
			return Float.NaN;
		}

		int tileX = (int) Math.floor(x / TILE_SIZE);
		int tileZ = (int) Math.floor(z / TILE_SIZE);
		float dx = (x - tileX * TILE_SIZE) / TILE_SIZE;
		float dz = (z - tileZ * TILE_SIZE) / TILE_SIZE;

		int sampleX = tileX + sceneOffset;
		int sampleZ = tileZ + sceneOffset;
		// Bridge flags live on plane 1; heights and these flags both use extended coordinates.
		if (plane + 1 < tileHeights.length && extendedTileSettings != null
			&& extendedTileSettings.length > 1
			&& sampleX >= 0 && sampleX < extendedTileSettings[1].length
			&& sampleZ >= 0 && sampleZ < extendedTileSettings[1][sampleX].length
			&& (extendedTileSettings[1][sampleX][sampleZ] & Constants.TILE_FLAG_BRIDGE) != 0)
		{
			plane++;
		}

		if (sampleX < 0 || sampleZ < 0
			|| sampleX + 1 >= tileHeights[plane].length
			|| sampleZ + 1 >= tileHeights[plane][sampleX].length)
		{
			return Float.NaN;
		}

		float sw = tileHeights[plane][sampleX][sampleZ];
		float se = tileHeights[plane][sampleX + 1][sampleZ];
		float ne = tileHeights[plane][sampleX + 1][sampleZ + 1];
		float nw = tileHeights[plane][sampleX][sampleZ + 1];
		float south = sw + (se - sw) * dx;
		float north = nw + (ne - nw) * dx;
		return south + (north - south) * dz;
	}

	static boolean isUnderRoof(byte[][][] tileSettings, int plane, float x, float z)
	{
		if (tileSettings == null || plane < 0 || plane >= tileSettings.length)
		{
			return false;
		}

		// Tile settings use local coordinates, unlike the extended terrain height array.
		int tileX = (int) Math.floor(x / TILE_SIZE);
		int tileZ = (int) Math.floor(z / TILE_SIZE);
		if (tileX < 0 || tileZ < 0 || tileX >= tileSettings[plane].length || tileZ >= tileSettings[plane][tileX].length)
		{
			return false;
		}

		return (tileSettings[plane][tileX][tileZ] & Constants.TILE_FLAG_UNDER_ROOF) != 0;
	}

	private float random(float min, float max)
	{
		return min + random.nextFloat() * (max - min);
	}

	private static float fade(Layer layer, float age, float lifetime)
	{
		float fadeIn = Math.min(age / Math.max(0.001f, lifetime * layer.fadeInFraction()), 1f);
		float fadeOut = Math.min((lifetime - age) / Math.max(0.001f, lifetime * layer.fadeOutFraction()), 1f);
		fadeIn = fadeIn * fadeIn * (3f - 2f * fadeIn);
		fadeOut = fadeOut * fadeOut * (3f - 2f * fadeOut);
		return Math.max(0f, Math.min(fadeIn, fadeOut));
	}

	private static float edgeFade(float distance, float radius)
	{
		float start = radius * 0.68f;
		if (distance <= start)
		{
			return 1f;
		}

		float fade = Math.max(0f, Math.min(1f, (radius - distance) / Math.max(1f, radius - start)));
		return fade * fade * (3f - 2f * fade);
	}

	private static LoadedSpriteSet loadSpriteSet(Filepath directory, Filepath sharedDirectory, Layer layer,
		SpriteCache cache) throws IOException
	{
		String externalAssetPrefix = layer.externalAssetPrefix();
		if (externalAssetPrefix != null)
		{
			LoadedSpriteSet externalSpriteSet = loadSpriteSetFromFrames(directory, externalAssetPrefix, layer, cache);
			if (externalSpriteSet == null)
			{
				externalSpriteSet = loadSpriteSetFromFrames(sharedDirectory, externalAssetPrefix, layer, cache);
			}
			if (externalSpriteSet != null)
			{
				return externalSpriteSet;
			}
		}

		if (layer.proceduralSprite())
		{
			// Generated per layer; shared only by the effects that use this layer (Day/Fall/Blossom).
			String key = "procedural|" + layer.name();
			LoadedSpriteSet cached = cache.get(key);
			if (cached != null)
			{
				return cached;
			}
			LoadedSpriteSet generated = buildAtlas(createProceduralSprites(layer));
			generated.key = key;
			cache.put(key, generated);
			return generated;
		}

		LoadedSpriteSet spriteSet = loadSpriteSetFromFrames(directory, layer.assetPrefix, layer, cache);
		return spriteSet != null ? spriteSet : loadSpriteSetFromFrames(sharedDirectory, layer.assetPrefix, layer, cache);
	}

	/**
	 * What a frame-based atlas depends on besides its files. Moonlight and soft-edged layers
	 * process frames in their own way (blur, skipped frames, scales), so each is its own kind;
	 * every other layer only decodes and packs, so same files give the same atlas.
	 */
	static String atlasProcessing(String layerName, boolean moonlightLayer, boolean softFrameEdges)
	{
		return moonlightLayer || softFrameEdges ? layerName : "plain";
	}

	private static LoadedSpriteSet loadSpriteSetFromFrames(Filepath directory, String assetPrefix, Layer layer,
		SpriteCache cache) throws IOException
	{
		List<FramePath> framePaths = listFrames(directory, assetPrefix);
		if (framePaths.isEmpty())
		{
			return null;
		}
		StringBuilder key = new StringBuilder(atlasProcessing(layer.name(), layer.moonlightLayer(), layer.softFrameEdges()));
		for (FramePath framePath : framePaths)
		{
			key.append('|').append(framePath.path);
		}
		String cacheKey = key.toString();
		LoadedSpriteSet cached = cache.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}
		LoadedSpriteSet loaded = decodeFrames(framePaths, layer);
		if (loaded != null)
		{
			loaded.key = cacheKey;
			cache.put(cacheKey, loaded);
		}
		return loaded;
	}

	private static LoadedSpriteSet decodeFrames(List<FramePath> framePaths, Layer layer) throws IOException
	{

		List<BufferedImage> images = new ArrayList<>(framePaths.size());
		List<Float> frameScales = new ArrayList<>(framePaths.size());
		List<Float> frameAlphaScales = new ArrayList<>(framePaths.size());
		long frameBytes = 0;
		for (FramePath framePath : framePaths)
		{
			if (Thread.currentThread().isInterrupted())
			{
				throw new CancellationException("Weather loading interrupted");
			}
			// The numbered fill sequence excludes light_03, which belongs to the smoke layer.
			if (layer.moonlightPlasmaLayer() && framePath.index >= 1 && framePath.index <= 3)
			{
				continue;
			}
			BufferedImage image = ImageLoader.read(framePath.path, false,
				layer.softBeamFrame() ? 192 : layer.softFrameEdges() ? 32 : 8);
			if (image != null)
			{
				if (layer.softBeamFrame())
				{
					float blurScale = 3f;
					if (layer == Layer.MOONLIGHT_FILL)
					{
						blurScale = 50f;
					}
					else if (layer == Layer.MOONLIGHT_AURORA)
					{
						if (framePath.index == 5)
						{
							blurScale = 30f;
						}
						else if (framePath.index == 6)
						{
							blurScale = 18f;
						}
						else if (framePath.index == 4)
						{
							blurScale = 15f;
						}
						else if (framePath.index == 7)
						{
							blurScale = 6f;
						}
					}
					image = softenBeamFrame(image, blurScale);
				}
				else if (layer.softFrameEdges())
				{
					if (!hasUsableSoftFramePadding(image))
					{
						continue;
					}
					image = softenFrameEdges(image);
				}
				frameBytes += (long) image.getWidth() * image.getHeight() * 4;
				ImageLoader.checkWorkingBytes(frameBytes);
				images.add(image);
				float frameScale = 1f;
				if (layer.moonlightPlasmaLayer())
				{
					if (framePath.index == 7)
					{
						frameScale = 3f;
					}
					else if (framePath.index == 4 || framePath.index == 5
						|| framePath.index == 6 || framePath.index == 8)
					{
						frameScale = 1.5f;
					}
				}
				frameScales.add(frameScale);
				float frameAlphaScale = layer.moonlightLayer() ? 0.78f : 1f;
				if (layer.moonlightPlasmaLayer())
				{
					if (framePath.index == 7)
					{
						frameAlphaScale = 0.65f;
					}
					else if (framePath.index == 8)
					{
						frameAlphaScale = 0.325f;
					}
				}
				frameAlphaScales.add(frameAlphaScale);
			}
		}

		if (images.isEmpty())
		{
			return null;
		}

		return buildAtlas(images, frameScales, frameAlphaScales);
	}

	private static boolean hasUsableSoftFramePadding(BufferedImage image)
	{
		int width = image.getWidth();
		int height = image.getHeight();
		int margin = Math.max(3, Math.round(Math.min(width, height) * 0.025f));
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				int alpha = image.getRGB(x, y) >>> 24;
				if (alpha <= 16)
				{
					continue;
				}

				if (x < margin || y < margin || x >= width - margin || y >= height - margin)
				{
					return false;
				}
			}
		}
		return true;
	}

	private static BufferedImage softenFrameEdges(BufferedImage image) throws IOException
	{
		int width = image.getWidth();
		int height = image.getHeight();
		ImageLoader.checkDimensions(width, height, 32);
		int fadePixels = Math.max(10, Math.round(Math.min(width, height) * 0.18f));
		int blurRadius = Math.max(3, Math.round(Math.min(width, height) * 0.035f));
		int[] argb = new int[width * height];
		int[] alpha = new int[width * height];
		image.getRGB(0, 0, width, height, argb, 0, width);
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				int index = y * width + x;
				int sourceAlpha = argb[index] >>> 24;
				if (sourceAlpha == 0)
				{
					continue;
				}

				int edgeDistance = Math.min(Math.min(x, width - 1 - x), Math.min(y, height - 1 - y));
				float edgeFade = Math.max(0f, Math.min(1f, edgeDistance / (float) fadePixels));
				edgeFade = edgeFade * edgeFade * (3f - 2f * edgeFade);
				alpha[index] = Math.round(sourceAlpha * edgeFade);
			}
		}

		int[] blurredAlpha = boxBlurAlpha(boxBlurAlpha(alpha, width, height, blurRadius), width, height, blurRadius);
		BufferedImage softened = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				int index = y * width + x;
				if ((argb[index] >>> 24) == 0)
				{
					continue;
				}

				int softenedAlpha = Math.min(alpha[index], Math.round(blurredAlpha[index] * 1.35f));
				softened.setRGB(x, y, (softenedAlpha << 24) | (argb[index] & 0x00FFFFFF));
			}
		}
		return softened;
	}

	private static BufferedImage softenBeamFrame(BufferedImage image, float blurScale) throws IOException
	{
		int sourceWidth = image.getWidth();
		int sourceHeight = image.getHeight();
		int width = sourceWidth;
		int height = sourceHeight * 3;
		ImageLoader.checkDimensions(width, height, 64);
		int fadeX = Math.max(5, Math.round(width * 0.14f));
		int fadeY = Math.max(7, Math.round(sourceHeight * 0.80f));
		int blurRadius = Math.max(2, Math.round(Math.min(width, height) * 0.028f * blurScale));
		int[] source = new int[sourceWidth * sourceHeight];
		int[] argb = new int[width * height];
		int[] alpha = new int[width * height];
		int[] premultipliedRed = new int[width * height];
		int[] premultipliedGreen = new int[width * height];
		int[] premultipliedBlue = new int[width * height];
		image.getRGB(0, 0, sourceWidth, sourceHeight, source, 0, sourceWidth);
		for (int y = 0; y < height; y++)
		{
			System.arraycopy(source, (y % sourceHeight) * sourceWidth, argb, y * width, width);
			float edgeY = smoothStep(Math.min(y, height - 1 - y) / (float) fadeY);
			edgeY *= edgeY;
			for (int x = 0; x < width; x++)
			{
				int index = y * width + x;
				float edgeX = smoothStep(Math.min(x, width - 1 - x) / (float) fadeX);
				int pixelAlpha = Math.round((argb[index] >>> 24) * Math.min(edgeX, edgeY));
				alpha[index] = pixelAlpha;
				premultipliedRed[index] = (argb[index] >> 16 & 0xFF) * pixelAlpha;
				premultipliedGreen[index] = (argb[index] >> 8 & 0xFF) * pixelAlpha;
				premultipliedBlue[index] = (argb[index] & 0xFF) * pixelAlpha;
			}
		}

		int[] blurred = boxBlurAlpha(boxBlurAlpha(alpha, width, height, blurRadius), width, height, blurRadius);
		int[] blurredRed = boxBlurAlpha(boxBlurAlpha(premultipliedRed, width, height, blurRadius), width, height, blurRadius);
		int[] blurredGreen = boxBlurAlpha(boxBlurAlpha(premultipliedGreen, width, height, blurRadius), width, height, blurRadius);
		int[] blurredBlue = boxBlurAlpha(boxBlurAlpha(premultipliedBlue, width, height, blurRadius), width, height, blurRadius);
		BufferedImage softened = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++)
		{
			float edgeY = smoothStep(Math.min(y, height - 1 - y) / (float) fadeY);
			edgeY *= edgeY;
			for (int x = 0; x < width; x++)
			{
				int index = y * width + x;
				float edgeX = smoothStep(Math.min(x, width - 1 - x) / (float) fadeX);
				float edge = Math.min(edgeX, edgeY);
				float directWeight = alpha[index] * 0.05f;
				float blurredWeight = blurred[index] * 0.63f;
				float colorWeight = directWeight + blurredWeight;
				int softenedAlpha = Math.min(255, Math.round(colorWeight * edge));
				int red = colorWeight <= 0f ? 0 : Math.min(255, Math.round(
					((argb[index] >> 16 & 0xFF) * directWeight + blurredRed[index] * 0.63f) / colorWeight));
				int green = colorWeight <= 0f ? 0 : Math.min(255, Math.round(
					((argb[index] >> 8 & 0xFF) * directWeight + blurredGreen[index] * 0.63f) / colorWeight));
				int blue = colorWeight <= 0f ? 0 : Math.min(255, Math.round(
					((argb[index] & 0xFF) * directWeight + blurredBlue[index] * 0.63f) / colorWeight));
				softened.setRGB(x, y, softenedAlpha << 24 | red << 16 | green << 8 | blue);
			}
		}
		return softened;
	}

	private static float smoothStep(float value)
	{
		float t = Math.max(0f, Math.min(1f, value));
		return t * t * (3f - 2f * t);
	}

	private static int[] boxBlurAlpha(int[] alpha, int width, int height, int radius)
	{
		if (radius <= 0)
		{
			return alpha.clone();
		}

		int[] horizontal = new int[alpha.length];
		int[] blurred = new int[alpha.length];
		int window = radius * 2 + 1;
		for (int y = 0; y < height; y++)
		{
			int row = y * width;
			int sum = 0;
			for (int x = -radius; x <= radius; x++)
			{
				sum += alpha[row + Math.max(0, Math.min(width - 1, x))];
			}
			for (int x = 0; x < width; x++)
			{
				horizontal[row + x] = sum / window;
				int removeX = Math.max(0, x - radius);
				int addX = Math.min(width - 1, x + radius + 1);
				sum += alpha[row + addX] - alpha[row + removeX];
			}
		}

		for (int x = 0; x < width; x++)
		{
			int sum = 0;
			for (int y = -radius; y <= radius; y++)
			{
				sum += horizontal[Math.max(0, Math.min(height - 1, y)) * width + x];
			}
			for (int y = 0; y < height; y++)
			{
				blurred[y * width + x] = sum / window;
				int removeY = Math.max(0, y - radius);
				int addY = Math.min(height - 1, y + radius + 1);
				sum += horizontal[addY * width + x] - horizontal[removeY * width + x];
			}
		}
		return blurred;
	}

	private static List<BufferedImage> createProceduralSprites(Layer layer)
	{
		List<BufferedImage> images = new ArrayList<>();
		switch (layer)
		{
			case RAIN_IMPACT:
			case PESTILENCE_RAIN_IMPACT:
				for (int i = 0; i < 12; i++)
				{
					images.add(createRainImpactImage(i / 11f));
				}
				break;
			case ASH_SMOKE:
				for (int i = 0; i < 8; i++)
				{
					images.add(createAshSmokeImage(i));
				}
				break;
			case ASH_FLECKS:
				for (int i = 0; i < 16; i++)
				{
					images.add(createAshFleckImage(i, false));
				}
				break;
			case ASH_EMBERS:
				for (int i = 0; i < 16; i++)
				{
					images.add(createAshFleckImage(i, true));
				}
				break;
			case ASH_SPARKS:
				for (int i = 0; i < 10; i++)
				{
					images.add(createAshSparkImage(i / 9f));
				}
				break;
			case ASH_GLOW_DOTS:
				for (int i = 0; i < 8; i++)
				{
					images.add(createAshGlowImage(i / 7f));
				}
				break;
			case SPRING_PETALS:
				for (int i = 0; i < 18; i++)
				{
					images.add(createSpringPetalImage(i));
				}
				break;
			case SPRING_LEAVES:
				for (int i = 0; i < 10; i++)
				{
					images.add(createSpringGroundLeafImage(i));
				}
				break;
			case SPRING_GROUND_PETALS:
				for (int i = 0; i < 12; i++)
				{
					images.add(createSpringGroundPetalImage(i));
				}
				break;
			case SPRING_GROUND_LEAVES:
				for (int i = 0; i < 10; i++)
				{
					images.add(createSpringGroundLeafImage(i));
				}
				break;
			case SPRING_MOTES:
				for (int i = 0; i < 8; i++)
				{
					images.add(createSpringMoteImage(i / 7f));
				}
				break;
			case SNOW_FLAKES:
			case SNOW_FLURRIES:
				for (int i = 0; i < 16; i++)
				{
					images.add(createSnowFlakeImage(i));
				}
				break;
			case MOONLIGHT_BEAMS:
			case MOONLIGHT_SMOKE:
			case MOONLIGHT_AURORA:
			case MOONLIGHT_FILL:
				for (int i = 0; i < 8; i++)
				{
					images.add(createMoonBeamImage(i, layer.moonlightPlasmaLayer()));
				}
				break;
			default:
				images.add(createRainStreakImage());
				break;
		}
		return images;
	}

	private static BufferedImage createAshSmokeImage(int frame)
	{
		int size = 128;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float center = (size - 1) * 0.5f;
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float px = (x - center) / center;
				float py = (y - center) / center;
				float alpha = 0f;
				for (int i = 0; i < 7; i++)
				{
					float angle = frame * 0.47f + i * 1.73f;
					float cx = (float) Math.sin(angle * 1.37f) * 0.24f;
					float cy = (float) Math.cos(angle * 1.91f) * 0.20f - 0.05f;
					float radiusX = 0.22f + i * 0.018f;
					float radiusY = 0.18f + (6 - i) * 0.014f;
					float dx = (px - cx) / radiusX;
					float dy = (py - cy) / radiusY;
					alpha += (float) Math.exp(-(dx * dx + dy * dy) * 1.4f);
				}

				float edge = 1f - Math.min(1f, (float) Math.sqrt(px * px + py * py));
				float verticalFade = 0.35f + Math.max(0f, -py) * 0.65f;
				alpha = Math.max(0f, Math.min(1f, (alpha - 0.24f) * 0.22f * edge * verticalFade));
				int a = Math.min(255, Math.round(alpha * 255f));
				image.setRGB(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return image;
	}

	private static BufferedImage createAshFleckImage(int frame, boolean ember)
	{
		int size = ember ? 28 : 22;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float center = (size - 1) * 0.5f;
		float angle = frame * 0.71f;
		float cos = (float) Math.cos(angle);
		float sin = (float) Math.sin(angle);
		float radiusX = ember ? 4.4f : 3.2f;
		float radiusY = ember ? 1.8f : 1.3f;
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float dx = x - center;
				float dy = y - center;
				float rx = (dx * cos - dy * sin) / radiusX;
				float ry = (dx * sin + dy * cos) / radiusY;
				float core = 1f - (rx * rx + ry * ry);
				float alpha = Math.max(0f, core);
				alpha *= alpha;
				if (!ember)
				{
					float chip = 1f - Math.min(1f, Math.abs(dx + dy * 0.45f) / Math.max(1f, center));
					alpha *= 0.45f + chip * 0.55f;
				}
				int a = Math.min(255, Math.round(alpha * (ember ? 255f : 210f)));
				image.setRGB(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return image;
	}

	private static BufferedImage createAshSparkImage(float progress)
	{
		int size = 64;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float center = (size - 1) * 0.5f;
		float stretch = 1.4f + progress * 1.8f;
		float alphaScale = 1f - progress * 0.65f;
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float dx = (x - center) / center;
				float dy = (y - center) / center * stretch;
				float distance = (float) Math.sqrt(dx * dx + dy * dy);
				float glow = Math.max(0f, 1f - distance);
				glow = glow * glow * alphaScale;
				int a = Math.min(255, Math.round(glow * 210f));
				image.setRGB(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return image;
	}

	private static BufferedImage createAshGlowImage(float progress)
	{
		int size = 88;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float center = (size - 1) * 0.5f;
		float squash = 1.15f + progress * 0.35f;
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float dx = (x - center) / center;
				float dy = (y - center) / center * squash;
				float distance2 = dx * dx + dy * dy;
				float core = (float) Math.exp(-distance2 * 95f);
				float glow = (float) Math.exp(-distance2 * 6.8f);
				float alpha = Math.max(core * 0.82f, glow * 0.36f);
				int a = Math.min(255, Math.round(alpha * 230f));
				int r = 255;
				int g = Math.min(255, Math.round(18f + core * 237f + glow * 24f));
				int b = Math.min(255, Math.round(6f + core * 244f + glow * 6f));
				image.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
			}
		}
		return image;
	}

	private static BufferedImage createRainStreakImage()
	{
		int width = 3;
		int height = 128;
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++)
		{
			float vertical = 1f - Math.abs((y + 0.5f) / height * 2f - 1f);
			vertical = Math.min(1f, vertical * 2.4f);
			for (int x = 0; x < width; x++)
			{
				float horizontal = 1f - Math.abs((x + 0.5f) / width * 2f - 1f);
				float alpha = Math.max(0f, horizontal) * vertical;
				int a = Math.min(255, Math.round(alpha * 220f));
				image.setRGB(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return image;
	}

	private static BufferedImage createRainImpactImage(float progress)
	{
		int size = 96;
		float center = (size - 1) * 0.5f;
		float radius = 5f + progress * 28f;
		float thickness = 2.5f + progress * 3f;
		float alphaScale = (1f - progress) * 0.58f;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float dx = x - center;
				float dy = y - center;
				float distance = (float) Math.sqrt(dx * dx + dy * dy);
				float ring = 1f - Math.abs(distance - radius) / thickness;
				float alpha = Math.max(0f, ring) * alphaScale;
				int a = Math.min(255, Math.round(alpha * 255f));
				image.setRGB(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return image;
	}

	private static BufferedImage createSpringPetalImage(int frame)
	{
		int size = 48;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float center = (size - 1) * 0.5f;
		float angle = frame * 0.73f;
		float cos = (float) Math.cos(angle);
		float sin = (float) Math.sin(angle);
		float radiusX = 10.5f + (frame % 3) * 1.7f;
		float radiusY = 3.2f + (frame % 4) * 0.45f;
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float dx = x - center;
				float dy = y - center;
				float rx = (dx * cos - dy * sin) / radiusX;
				float ry = (dx * sin + dy * cos) / radiusY;
				float body = Math.max(0f, 1f - (rx * rx + ry * ry));
				float notch = 1f - Math.min(1f, Math.abs(rx + 0.35f) * 0.65f + Math.max(0f, -ry) * 0.08f);
				float alpha = body * body * (0.72f + notch * 0.28f);
				int a = Math.min(255, Math.round(alpha * 230f));
				int red = 255;
				int green = 190 + (frame % 4) * 8;
				int blue = 208 + (frame % 3) * 10;
				image.setRGB(x, y, a << 24 | red << 16 | green << 8 | blue);
			}
		}
		return image;
	}

	private static BufferedImage createSpringGroundPetalImage(int frame)
	{
		int size = 64;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float center = (size - 1) * 0.5f;
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float dx = x - center;
				float dy = y - center;
				float alpha = 0f;
				for (int petal = 0; petal < 5; petal++)
				{
					float angle = frame * 0.41f + petal * TWO_PI / 5f;
					float cos = (float) Math.cos(angle);
					float sin = (float) Math.sin(angle);
					float px = dx - cos * 9f;
					float py = dy - sin * 9f;
					float rx = (px * cos + py * sin) / 9f;
					float ry = (-px * sin + py * cos) / 3.2f;
					alpha = Math.max(alpha, Math.max(0f, 1f - (rx * rx + ry * ry)));
				}
				alpha *= alpha * 0.72f;
				int a = Math.min(255, Math.round(alpha * 210f));
				image.setRGB(x, y, a << 24 | 0xFFD7E2);
			}
		}
		return image;
	}

	private static BufferedImage createSpringGroundLeafImage(int frame)
	{
		int size = 52;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float center = (size - 1) * 0.5f;
		float angle = frame * 0.79f;
		float cos = (float) Math.cos(angle);
		float sin = (float) Math.sin(angle);
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float dx = x - center;
				float dy = y - center;
				float rx = (dx * cos - dy * sin) / 13.5f;
				float ry = (dx * sin + dy * cos) / 4.2f;
				float body = Math.max(0f, 1f - (rx * rx + ry * ry));
				float vein = 1f - Math.min(1f, Math.abs(ry) * 4.8f);
				float alpha = body * body * (0.78f + vein * 0.22f);
				int a = Math.min(255, Math.round(alpha * 220f));
				image.setRGB(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return image;
	}

	private static BufferedImage createSpringMoteImage(float progress)
	{
		int size = 42;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float center = (size - 1) * 0.5f;
		float pulse = 0.75f + 0.25f * (float) Math.sin(progress * TWO_PI);
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float dx = (x - center) / center;
				float dy = (y - center) / center;
				float distance2 = dx * dx + dy * dy;
				float core = (float) Math.exp(-distance2 * 72f);
				float glow = (float) Math.exp(-distance2 * 8f);
				float alpha = Math.max(core * 0.75f, glow * 0.28f) * pulse;
				int a = Math.min(255, Math.round(alpha * 190f));
				image.setRGB(x, y, a << 24 | 0xFFF6A2);
			}
		}
		return image;
	}

	private static BufferedImage createSnowFlakeImage(int frame)
	{
		int size = 38;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float center = (size - 1) * 0.5f;
		float armScale = 0.70f + (frame % 4) * 0.08f;
		for (int y = 0; y < size; y++)
		{
			for (int x = 0; x < size; x++)
			{
				float dx = (x - center) / center;
				float dy = (y - center) / center;
				float distance = (float) Math.sqrt(dx * dx + dy * dy);
				float core = (float) Math.exp(-distance * distance * 38f);
				float cross = 0f;
				for (int arm = 0; arm < 3; arm++)
				{
					float angle = frame * 0.11f + arm * (float) Math.PI / 3f;
					float cos = (float) Math.cos(angle);
					float sin = (float) Math.sin(angle);
					float along = Math.abs(dx * cos + dy * sin);
					float across = Math.abs(-dx * sin + dy * cos);
					cross = Math.max(cross, Math.max(0f, 1f - across * 38f) * Math.max(0f, 1f - along / armScale));
				}
				float alpha = Math.max(core, cross * 0.45f) * Math.max(0f, 1f - distance * 0.72f);
				int a = Math.min(255, Math.round(alpha * 220f));
				image.setRGB(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return image;
	}

	private static BufferedImage createMoonBeamImage(int frame, boolean aurora)
	{
		int width = aurora ? 56 : 88;
		int height = 256;
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		float center = (width - 1) * 0.5f;
		for (int y = 0; y < height; y++)
		{
			float progress = y / (float) (height - 1);
			float endFade = (float) Math.sin(progress * Math.PI);
			endFade = (float) Math.pow(Math.max(0f, endFade), aurora ? 0.55f : 0.85f);
			float wave = (float) Math.sin(progress * 8.5f + frame * 0.62f) * (aurora ? 2.2f : 4.5f);
			float beamWidth = center * (aurora ? 0.22f : 0.34f)
				* (0.82f + 0.18f * (float) Math.sin(progress * 5.0f + frame * 0.41f));
			for (int x = 0; x < width; x++)
			{
				float distance = (x - center - wave) / Math.max(1f, beamWidth);
				float core = (float) Math.exp(-distance * distance * (aurora ? 3.6f : 2.4f));
				float halo = (float) Math.exp(-distance * distance * 0.72f);
				float alpha = (core * (aurora ? 0.78f : 0.46f) + halo * (aurora ? 0.16f : 0.22f)) * endFade;
				int a = Math.min(255, Math.round(alpha * 230f));
				image.setRGB(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return image;
	}

	private static List<FramePath> listFrames(Filepath directory, String assetPrefix) throws IOException
	{
		// At most 9 digits, so the number always fits an int; a longer name is simply not a frame.
		Pattern pattern = Pattern.compile("^" + Pattern.quote(assetPrefix) + "(\\d{1,9})\\.png$", Pattern.CASE_INSENSITIVE);
		List<FramePath> framePaths = new ArrayList<>();
		if (!directory.exists())
		{
			return framePaths;
		}
		try (Stream<Filepath> stream = directory.walk(1))
		{
			Iterator<Filepath> paths = stream.filter(Filepath::isFile).iterator();
			while (paths.hasNext())
			{
				Filepath path = paths.next();
				Matcher matcher = pattern.matcher(path.getFileName());
				if (matcher.matches())
				{
					framePaths.add(new FramePath(path, Integer.parseInt(matcher.group(1))));
				}
				else if (path.getFileName().equalsIgnoreCase(assetPrefix + ".png"))
				{
					framePaths.add(new FramePath(path, 0));
				}
				if (framePaths.size() > 2048)
				{
					throw new IOException("Weather sequence exceeds 2048 frames: " + assetPrefix);
				}
			}
		}
		framePaths.sort(Comparator.comparingInt(framePath -> framePath.index));
		return framePaths;
	}

	private static LoadedSpriteSet buildAtlas(List<BufferedImage> images) throws IOException
	{
		List<Float> frameScales = new ArrayList<>(images.size());
		List<Float> frameAlphaScales = new ArrayList<>(images.size());
		for (int i = 0; i < images.size(); i++)
		{
			frameScales.add(1f);
			frameAlphaScales.add(1f);
		}
		return buildAtlas(images, frameScales, frameAlphaScales);
	}

	private static LoadedSpriteSet buildAtlas(List<BufferedImage> images, List<Float> frameScales,
		List<Float> frameAlphaScales) throws IOException
	{
		int maxWidth = 1;
		int maxHeight = 1;
		for (BufferedImage image : images)
		{
			maxWidth = Math.max(maxWidth, image.getWidth());
			maxHeight = Math.max(maxHeight, image.getHeight());
		}

		int columns = (int) Math.ceil(Math.sqrt(images.size()));
		int rows = (int) Math.ceil(images.size() / (double) columns);
		int cellWidth = maxWidth + ATLAS_PADDING * 2;
		int cellHeight = maxHeight + ATLAS_PADDING * 2;
		ImageLoader.checkDimensions((long) columns * cellWidth, (long) rows * cellHeight, 4);
		long workingBytes = (long) columns * cellWidth * rows * cellHeight * 4;
		for (BufferedImage image : images)
		{
			workingBytes += (long) image.getWidth() * image.getHeight() * 4;
		}
		ImageLoader.checkWorkingBytes(workingBytes);
		BufferedImage atlas = new BufferedImage(columns * cellWidth, rows * cellHeight, BufferedImage.TYPE_INT_ARGB);
		SpriteFrame[] frames = new SpriteFrame[images.size()];

		Graphics2D graphics = atlas.createGraphics();
		graphics.setComposite(AlphaComposite.Src);
		try
		{
			for (int i = 0; i < images.size(); i++)
			{
				BufferedImage image = images.get(i);
				int cellX = i % columns * cellWidth;
				int cellY = i / columns * cellHeight;
				int x = cellX + (cellWidth - image.getWidth()) / 2;
				int y = cellY + (cellHeight - image.getHeight()) / 2;
				graphics.drawImage(image, x, y, null);
				frames[i] = new SpriteFrame(
					cellX / (float) atlas.getWidth(),
					cellY / (float) atlas.getHeight(),
					(cellX + cellWidth) / (float) atlas.getWidth(),
					(cellY + cellHeight) / (float) atlas.getHeight(),
					frameScales.get(i),
					frameAlphaScales.get(i));
			}
		}
		finally
		{
			graphics.dispose();
		}

		return new LoadedSpriteSet(atlas, frames, cellWidth, cellHeight);
	}

	static final class LoadedWeatherAssets
	{
		private final GpuSkyExtensionConfig.WeatherEffect effect;
		private final EnumMap<Layer, LoadedSpriteSet> spriteSets = new EnumMap<>(Layer.class);
		private boolean complete = true;
		/** Set at upload when a set's texture was lost with an old context: reload at once, not after a backoff. */
		private volatile boolean foundStale;

		boolean foundStale()
		{
			return foundStale;
		}

		GpuSkyExtensionConfig.WeatherEffect effect()
		{
			return effect;
		}

		boolean isEmpty()
		{
			return spriteSets.isEmpty();
		}

		boolean isComplete()
		{
			return complete;
		}

		private LoadedWeatherAssets(GpuSkyExtensionConfig.WeatherEffect effect)
		{
			this.effect = effect;
		}
	}

	private static final class LoadedSpriteSet
	{
		/** Decoded pixels; released (null) by the client thread once uploaded. */
		private volatile BufferedImage atlas;
		private final SpriteFrame[] frames;
		private final int displayWidth;
		private final int displayHeight;
		/** Identity for sharing: processing kind plus frame files. Set by the loader before publishing. */
		private String key;
		/** Uploaded, released, and that texture lost with its GPU context: must be decoded again. */
		private volatile boolean stale;

		private LoadedSpriteSet(BufferedImage atlas, SpriteFrame[] frames, int displayWidth, int displayHeight)
		{
			this.atlas = atlas;
			this.frames = frames;
			this.displayWidth = displayWidth;
			this.displayHeight = displayHeight;
		}
	}

	/** One GPU texture and the number of layers drawing with it. Client thread. */
	private static final class SharedTexture
	{
		private final int texture;
		private int users;

		private SharedTexture(int texture)
		{
			this.texture = texture;
		}
	}

	/**
	 * Decoded sprite sets of one loader session, by key, so a layer or effect that needs the
	 * same frames reuses the first decode. Entries whose pixels were released and whose texture
	 * was lost (stale) are decoded again.
	 */
	static final class SpriteCache
	{
		private final Map<String, LoadedSpriteSet> sets = new HashMap<>();

		private synchronized LoadedSpriteSet get(String key)
		{
			LoadedSpriteSet set = sets.get(key);
			if (set != null && set.stale)
			{
				sets.remove(key);
				return null;
			}
			return set;
		}

		private synchronized void put(String key, LoadedSpriteSet set)
		{
			sets.put(key, set);
		}

		/** The GPU context changed: every set already uploaded (pixels released) must be decoded again. */
		synchronized void contextLost()
		{
			for (LoadedSpriteSet set : sets.values())
			{
				if (set.atlas == null)
				{
					set.stale = true;
				}
			}
		}

		synchronized int size()
		{
			return sets.size();
		}
	}

	private static final class SpriteSet
	{
		private final int texture;
		private final SpriteFrame[] frames;
		private final int displayWidth;
		private final int displayHeight;

		private SpriteSet(int texture, SpriteFrame[] frames, int displayWidth, int displayHeight)
		{
			this.texture = texture;
			this.frames = frames;
			this.displayWidth = displayWidth;
			this.displayHeight = displayHeight;
		}
	}

	private static final class SpriteFrame
	{
		private final float u0;
		private final float v0;
		private final float u1;
		private final float v1;
		private final float scale;
		private final float alphaScale;

		private SpriteFrame(float u0, float v0, float u1, float v1, float scale, float alphaScale)
		{
			this.u0 = u0;
			this.v0 = v0;
			this.u1 = u1;
			this.v1 = v1;
			this.scale = scale;
			this.alphaScale = alphaScale;
		}
	}

	private static final class WeatherParticle
	{
		private boolean active;
		private float spawnRetrySeconds;
		private float x;
		private float y;
		private float z;
		private float velocityX;
		private float velocityY;
		private float velocityZ;
		private float size;
		private float initialGroundDistance;
		private float alpha;
		private float rotation;
		private float spin;
		private float age;
		private float lifetime;
		private float frameOffset;
		private float animationFps;
		private boolean trail;
		private boolean mirrorHorizontal;
	}

	static final class Billboard
	{
		final float rightX;
		final float rightY;
		final float rightZ;
		final float upX;
		final float upY;
		final float upZ;

		private Billboard(float rightX, float rightY, float rightZ, float upX, float upY, float upZ)
		{
			this.rightX = rightX;
			this.rightY = rightY;
			this.rightZ = rightZ;
			this.upX = upX;
			this.upY = upY;
			this.upZ = upZ;
		}
	}

	private static final class FramePath
	{
		private final Filepath path;
		private final int index;

		private FramePath(Filepath path, int index)
		{
			this.path = path;
			this.index = index;
		}
	}

	private enum Layer
	{
		SIROCCO_SMOKE(GpuSkyExtensionConfig.WeatherEffect.SIROCCO, "alternate_smoke_",
			new LayerTuning(18, 48f, 108f, 90f, 420f, -5f, 3f, 0.125f, 0.275f, -0.05f, 0.05f, 0.18f, 0.4f, 0.9f, 15f, 32f, 2800f, 0.16f, 12f, 0.95f, 0.78f, 0.58f),
			new LayerTuning(20, 5760f, 12960f, 40f, 260f, -38f, 14f, 0.035f, 0.10f, 0f, 0f, 0f, 0.7f, 1.4f, 7f, 15f, 7200f, 0.46f, 42f, 1f, 0.84f, 0.58f)),
		SIROCCO_MIST_GROUND(GpuSkyExtensionConfig.WeatherEffect.SIROCCO, "dust_v2_",
			new LayerTuning(60, 27f, 69f, 18f, 140f, -4f, 3f, 0.125f, 0.325f, -0.08f, 0.08f, 0.22f, 5f, 8f, 10f, 22f, 2400f, 0.32f, 14f, 0.95f, 0.82f, 0.66f),
			new LayerTuning(70, 3240f, 8280f, 20f, 150f, -34f, 16f, 0.045f, 0.14f, 0f, 0f, 0f, 7f, 12f, 5f, 12f, 6800f, 0.64f, 42f, 1f, 0.84f, 0.58f)),
		SIROCCO_MIST_COLUMN(GpuSkyExtensionConfig.WeatherEffect.SIROCCO, "dust_",
			new LayerTuning(65, 36f, 87f, 30f, 260f, -8f, 3f, 0.14f, 0.375f, -0.08f, 0.08f, 0.22f, 5f, 9f, 9f, 20f, 2500f, 0.38f, 17f, 0.96f, 0.84f, 0.64f),
			new LayerTuning(75, 4320f, 10440f, 40f, 220f, -48f, 18f, 0.045f, 0.15f, 0f, 0f, 0f, 7f, 13f, 5f, 12f, 7000f, 0.72f, 50f, 1f, 0.84f, 0.58f)),
		SIROCCO_SWIRL(GpuSkyExtensionConfig.WeatherEffect.SIROCCO, "alternate_dust_",
			new LayerTuning(80, 36f, 93f, 45f, 380f, -6f, 4f, 0.15f, 0.45f, -0.18f, 0.18f, 0.35f, 5f, 9f, 10f, 24f, 2600f, 0.54f, 22f, 0.98f, 0.76f, 0.45f),
			new LayerTuning(95, 4320f, 11160f, 50f, 260f, -44f, 18f, 0.05f, 0.17f, 0f, 0f, 0f, 7f, 13f, 5f, 12f, 7200f, 0.92f, 70f, 1f, 0.82f, 0.52f)),
		SIROCCO_FLECKS(GpuSkyExtensionConfig.WeatherEffect.SIROCCO, "Exfoliate_",
			new LayerTuning(900, 1.8f, 5.7f, 45f, 520f, -16f, 9f, 0.40f, 0.95f, -0.7f, 0.7f, 3.14f, 12f, 24f, 4f, 9f, 2300f, 0.2f, 55f, 1f, 0.80f, 0.48f),
			new LayerTuning(2500, 2.43f, 5.6f, 45f, 620f, -80f, 35f, 0.42f, 0.98f, -0.18f, 0.18f, 3.14f, 0f, 0f, 2.5f, 5.5f, 3100f, 0.45f, 80f, 1f, 0.80f, 0.48f)),
		ASH_SMOKE(GpuSkyExtensionConfig.WeatherEffect.ASH, "ash_smoke",
			new LayerTuning(50, 760f, 1560f, 110f, 320f, -8f, 4f, 0.08f, 0.18f, -0.03f, 0.03f, 0.12f, 1.2f, 2.0f, 5f, 11f, 3400f, 0.10f, 10f, 0.68f, 0.62f, 0.55f)),
		ASH_FLECKS(GpuSkyExtensionConfig.WeatherEffect.ASH, "ash_fleck",
			new LayerTuning(1404, 2.6f, 9.1f, 160f, 760f, 60f, 170f, 0.20f, 0.52f, -0.45f, 0.45f, 3.14f, 0f, 0f, 3.5f, 7.5f, 2500f, 0.18f, 70f, 0.68f, 0.66f, 0.62f)),
		ASH_EMBERS(GpuSkyExtensionConfig.WeatherEffect.ASH, "ash_ember",
			new LayerTuning(400, 3.0f, 13f, 220f, 900f, 50f, 155f, 0.30f, 0.74f, -0.35f, 0.35f, 3.14f, 0f, 0f, 3f, 6.5f, 2500f, 0.16f, 55f, 1f, 0.42f, 0.16f)),
		ASH_SPARKS(GpuSkyExtensionConfig.WeatherEffect.ASH, "ash_spark",
			new LayerTuning(64, 10f, 28f, 30f, 180f, -35f, 30f, 0.18f, 0.45f, -0.2f, 0.2f, 3.14f, 8f, 16f, 1.1f, 2.4f, 1800f, 0.08f, 28f, 1f, 0.34f, 0.18f)),
		ASH_GLOW_DOTS(GpuSkyExtensionConfig.WeatherEffect.ASH, "ash_glow",
			new LayerTuning(60, 20f, 43f, 50f, 300f, -30f, 28f, 0.75f, 0.99f, -0.18f, 0.18f, 0.45f, 9f, 16f, 1.4f, 3.2f, 1900f, 0.12f, 70f, 1f, 1f, 1f)),
		PESTILENCE_RAIN(GpuSkyExtensionConfig.WeatherEffect.PESTILENCE, "pestilence_rain_streak",
			new LayerTuning(2200, 120f, 230f, 420f, 1500f, 3600f, 5400f, 0.30f, 0.8f, 0f, 0f, 0.02f, 0f, 0f, 0.24f, 0.46f, 3800f, 0.16f, 18f, 0.82f, 0.05f, 0.05f)),
		PESTILENCE_RAIN_IMPACT(GpuSkyExtensionConfig.WeatherEffect.PESTILENCE, "pestilence_rain_impact",
			new LayerTuning(220, 24f, 66f, 12f, 12f, 0f, 0f, 0.7f, 1f, -0.25f, 0.25f, 3.14f, 0f, 0f, 0.12f, 0.24f, 3000f, 0f, 0f, 0.82f, 0.05f, 0.05f)),
		PESTILENCE_FLECKS(GpuSkyExtensionConfig.WeatherEffect.PESTILENCE, "scorched_",
			new LayerTuning(900, 4.91f, 17.20f, 160f, 760f, 60f, 170f, 0.20f, 0.52f, -0.45f, 0.45f, 3.14f, 0f, 0f, 3.5f, 7.5f, 2500f, 0.18f, 70f, 0.10f, 0.09f, 0.08f)),
		PESTILENCE_RED_FLECKS(GpuSkyExtensionConfig.WeatherEffect.PESTILENCE, "scorched_",
			new LayerTuning(0, 4.91f, 17.20f, 160f, 760f, 60f, 170f, 0.6f, 1f, -0.45f, 0.45f, 3.14f, 0f, 0f, 3.5f, 7.5f, 2500f, 0.18f, 70f, 0.82f, 0.05f, 0.05f)),
		RAIN_STREAKS(GpuSkyExtensionConfig.WeatherEffect.RAIN, "rain_streak",
			new LayerTuning(2900, 120f, 230f, 420f, 1500f, 3600f, 5400f, 0.06f, 0.38f, 0f, 0f, 0.02f, 0f, 0f, 0.24f, 0.46f, 3800f, 0.16f, 18f, 0.72f, 0.88f, 1f)),
		RAIN_IMPACT(GpuSkyExtensionConfig.WeatherEffect.RAIN, "rain_impact",
			new LayerTuning(220, 24f, 66f, 12f, 12f, 0f, 0f, 0.7f, 1f, -0.25f, 0.25f, 3.14f, 0f, 0f, 0.12f, 0.24f, 3000f, 0f, 0f, 0.72f, 0.90f, 1f)),
		SPRING_PETALS(GpuSkyExtensionConfig.WeatherEffect.SPRING, "spring_petal",
			new LayerTuning(120, 9f, 19f, 150f, 980f, 42f, 115f, 0.54f, 0.92f, -0.85f, 0.85f, 3.14f, 0f, 0f, 5.5f, 11f, 3200f, 0.11f, 42f, 1f, 0.78f, 0.88f)),
		SPRING_LEAVES(GpuSkyExtensionConfig.WeatherEffect.SPRING, "spring_leaf",
			new LayerTuning(60, 10f, 25f, 170f, 980f, 36f, 100f, 1f, 1f, -0.72f, 0.72f, 3.14f, 0f, 0f, 6f, 12f, 3200f, 0.10f, 46f, 0.55f, 0.72f, 0.23f)),
		SPRING_GROUND_PETALS(GpuSkyExtensionConfig.WeatherEffect.SPRING, "spring_ground_petal",
			new LayerTuning(200, 9f, 19f, 14f, 14f, 0f, 0f, 0.20f, 0.48f, -0.04f, 0.04f, 3.14f, 0f, 0f, 6f, 13f, 2800f, 0f, 0f, 1f, 0.78f, 0.88f)),
		SPRING_GROUND_LEAVES(GpuSkyExtensionConfig.WeatherEffect.SPRING, "spring_ground_leaf",
			new LayerTuning(70, 10f, 25f, 14f, 14f, 0f, 0f, 0.65f, 0.85f, -0.04f, 0.04f, 3.14f, 0f, 0f, 7f, 15f, 2800f, 0f, 0f, 0.55f, 0.72f, 0.23f)),
		SPRING_MOTES(GpuSkyExtensionConfig.WeatherEffect.SPRING, "spring_mote",
			new LayerTuning(720, 3f, 6f, 90f, 620f, -10f, 36f, 0.8f, 0.1f, -0.10f, 0.10f, 3.14f, 0f, 0f, 4f, 8f, 2600f, 0.05f, 32f, 1f, 0.96f, 0.62f)),
		SNOW_FLAKES(GpuSkyExtensionConfig.WeatherEffect.SNOW, "snow_flake",
			new LayerTuning(5200, 6f, 18f, 190f, 1280f, 115f, 260f, 0.8f, 1f, -0.34f, 0.34f, 3.14f, 0f, 0f, 5f, 10f, 3600f, 0.10f, 62f, 0.82f, 0.92f, 1f)),
		SNOW_FLURRIES(GpuSkyExtensionConfig.WeatherEffect.SNOW, "snow_flurry",
			new LayerTuning(1600, 14f, 40f, 120f, 900f, 60f, 150f, 0.8f, 1f, -0.22f, 0.22f, 3.14f, 0f, 0f, 5f, 11f, 3200f, 0.16f, 120f, 0.88f, 0.96f, 1f)),
		SNOW_DRIFT(GpuSkyExtensionConfig.WeatherEffect.SNOW, "snow_drift",
			new LayerTuning(100, 420f, 1050f, 30f, 185f, -4f, 9f, 0.2f, 0.5f, -0.035f, 0.035f, 0.35f, 0.5f, 1.3f, 6f, 12f, 3300f, 0.18f, 36f, 0.78f, 0.88f, 1f)),
		MOONLIGHT_BEAMS(GpuSkyExtensionConfig.WeatherEffect.MOONLIGHT, "moonlight_beam",
			new LayerTuning(0, 936f, 1680f, 0f, 35f, -0.4f, -0.4f, 0.09f, 0.24f, 0f, 0f, 0f, 0f, 0f, 5f, 7f, 3400f, 0.0005f, 0f, 0.82f, 0.90f, 1f)),
		MOONLIGHT_SMOKE(GpuSkyExtensionConfig.WeatherEffect.MOONLIGHT, "moonlight_smoke",
			new LayerTuning(0, 936f, 1680f, 0f, 35f, -0.4f, -0.4f, 0.09f, 0.24f, 0f, 0f, 0f, 0f, 0f, 5f, 7f, 3400f, 0.0005f, 0f, 0.74f, 0.84f, 0.98f)),
		MOONLIGHT_AURORA(GpuSkyExtensionConfig.WeatherEffect.MOONLIGHT, "moonlight_aurora",
			new LayerTuning(24, 936f, 1680f, 0f, 35f, -0.4f, -0.4f, 0.09f, 0.24f, 0f, 0f, 0f, 0f, 0f, 5f, 7f, 3400f, 0.0005f, 0f, 0.78f, 0.88f, 1f)),
		MOONLIGHT_FILL(GpuSkyExtensionConfig.WeatherEffect.MOONLIGHT, "moonlight_fill",
			new LayerTuning(16, 936f, 1680f, 0f, 35f, -0.4f, -0.4f, 0.04f, 0.10f, 0f, 0f, 0f, 0f, 0f, 5f, 7f, 3400f, 0.0005f, 0f, 0.82f, 0.90f, 1f));

		private final GpuSkyExtensionConfig.WeatherEffect effect;
		private final String assetPrefix;
		private final LayerTuning ashTuning;
		private final int baseCount;
		private final float minSize;
		private final float maxSize;
		private final float minHeight;
		private final float maxHeight;
		private final float minVelocityY;
		private final float maxVelocityY;
		private final float minAlpha;
		private final float maxAlpha;
		private final float minSpin;
		private final float maxSpin;
		private final float maxInitialRotation;
		private final float minAnimationFps;
		private final float maxAnimationFps;
		private final float minLifetime;
		private final float maxLifetime;
		private final float radius;
		private final float windScale;
		private final float turbulence;
		private final float red;
		private final float green;
		private final float blue;

		Layer(GpuSkyExtensionConfig.WeatherEffect effect, String assetPrefix, LayerTuning tuning)
		{
			this(effect, assetPrefix, null, tuning);
		}

		Layer(GpuSkyExtensionConfig.WeatherEffect effect, String assetPrefix, LayerTuning ashTuning, LayerTuning siroccoTuning)
		{
			this.effect = effect;
			this.assetPrefix = assetPrefix;
			this.ashTuning = ashTuning;
			this.baseCount = siroccoTuning.baseCount;
			this.minSize = siroccoTuning.minSize;
			this.maxSize = siroccoTuning.maxSize;
			this.minHeight = siroccoTuning.minHeight;
			this.maxHeight = siroccoTuning.maxHeight;
			this.minVelocityY = siroccoTuning.minVelocityY;
			this.maxVelocityY = siroccoTuning.maxVelocityY;
			this.minAlpha = siroccoTuning.minAlpha;
			this.maxAlpha = siroccoTuning.maxAlpha;
			this.minSpin = siroccoTuning.minSpin;
			this.maxSpin = siroccoTuning.maxSpin;
			this.maxInitialRotation = siroccoTuning.maxInitialRotation;
			this.minAnimationFps = siroccoTuning.minAnimationFps;
			this.maxAnimationFps = siroccoTuning.maxAnimationFps;
			this.minLifetime = siroccoTuning.minLifetime;
			this.maxLifetime = siroccoTuning.maxLifetime;
			this.radius = siroccoTuning.radius;
			this.windScale = siroccoTuning.windScale;
			this.turbulence = siroccoTuning.turbulence;
			this.red = siroccoTuning.red;
			this.green = siroccoTuning.green;
			this.blue = siroccoTuning.blue;
		}

		private float groundLift()
		{
			switch (this)
			{
				case SIROCCO_SMOKE:
					return 0.10f;
				case SIROCCO_MIST_GROUND:
					return 0.04f;
				case SIROCCO_MIST_COLUMN:
					return 0.07f;
				case SIROCCO_SWIRL:
					return 0.09f;
				case ASH_SMOKE:
				case SNOW_DRIFT:
					return 0.04f;
				case MOONLIGHT_BEAMS:
				case MOONLIGHT_SMOKE:
				case MOONLIGHT_AURORA:
				case MOONLIGHT_FILL:
					return 0.50f;
				default:
					return 0f;
			}
		}

		private float bottomVertexAlpha()
		{
			return softFootFade() ? 0f : 1f;
		}

		private boolean rainStreakLayer()
		{
			return normalRainStreakLayer() || pestilenceRainLayer();
		}

		private boolean normalRainStreakLayer()
		{
			return this == RAIN_STREAKS;
		}

		private boolean pestilenceRainLayer()
		{
			return this == PESTILENCE_RAIN;
		}

		private float widthScale()
		{
			if (normalRainStreakLayer())
			{
				return 0.58f;
			}
			if (pestilenceRainLayer())
			{
				return 0.58f;
			}
			if (this == MOONLIGHT_BEAMS || this == MOONLIGHT_SMOKE)
			{
				return 2.20f;
			}
			if (moonlightPlasmaLayer())
			{
				return 0.40f;
			}
			return 1f;
		}

		private float verticalScale()
		{
			return skywardGrowth() ? 2f : 1f;
		}

		private float faintRainAlphaScale()
		{
			if (pestilenceRainLayer())
			{
				return FAINT_PESTILENCE_RAIN_ALPHA_SCALE;
			}
			return FAINT_RAIN_ALPHA_SCALE;
		}

		private boolean wrapAnimation()
		{
			return this == ASH_GLOW_DOTS;
		}

		private boolean resetAtGround()
		{
			return this == ASH_FLECKS || this == ASH_EMBERS || this == PESTILENCE_FLECKS || this == PESTILENCE_RED_FLECKS
				|| this == RAIN_STREAKS || this == PESTILENCE_RAIN || this == SPRING_PETALS || this == SPRING_LEAVES
				|| this == SNOW_FLAKES || this == SNOW_FLURRIES;
		}

		private boolean rainImpactSource()
		{
			return this == RAIN_STREAKS || this == PESTILENCE_RAIN;
		}

		private boolean impactRingLayer()
		{
			return this == RAIN_IMPACT || this == PESTILENCE_RAIN_IMPACT;
		}

		private boolean shrinkNearGround()
		{
			return this == ASH_FLECKS || this == ASH_EMBERS || this == PESTILENCE_FLECKS || this == PESTILENCE_RED_FLECKS;
		}

		private boolean thinNearGround()
		{
			return rainStreakLayer();
		}

		private boolean fadeOutNearGround()
		{
			return this == PESTILENCE_RAIN;
		}

		private boolean animateFrames()
		{
			return this != ASH_SMOKE && this != SIROCCO_FLECKS && this != ASH_FLECKS
				&& this != ASH_EMBERS && this != PESTILENCE_FLECKS && this != PESTILENCE_RED_FLECKS && this != SPRING_PETALS
				&& this != SPRING_LEAVES && this != SPRING_GROUND_PETALS && this != SPRING_GROUND_LEAVES
				&& this != SPRING_MOTES && this != SNOW_FLAKES && this != SNOW_FLURRIES
				&& !moonlightLayer();
		}

		private float fadeInFraction()
		{
			return this == ASH_SMOKE || this == SNOW_DRIFT ? 0.30f : 0.15f;
		}

		private float fadeOutFraction()
		{
			return this == ASH_SMOKE || this == SNOW_DRIFT ? 0.18f : 0.25f;
		}

		private boolean fitAnimationToLifetime()
		{
			return this == ASH_SPARKS || impactRingLayer();
		}

		private boolean triggeredOnly()
		{
			return impactRingLayer();
		}

		private boolean playerAnchoredHeight()
		{
			return !triggeredOnly() && this != SPRING_GROUND_PETALS && this != SPRING_GROUND_LEAVES
				&& !moonlightLayer();
		}

		private float triggerChance()
		{
			return impactRingLayer() ? 0.12f : 1f;
		}

		private boolean spawnUpwind()
		{
			return effect == GpuSkyExtensionConfig.WeatherEffect.SIROCCO || this == SPRING_PETALS || this == SPRING_LEAVES
				|| this == SNOW_FLAKES || this == SNOW_FLURRIES;
		}

		private boolean innerCoverage()
		{
			return !triggeredOnly();
		}

		private float minimumSpawnRadiusScale()
		{
			return moonlightPlasmaLayer() ? 0.35f : 0f;
		}

		private boolean opacityWave()
		{
			switch (this)
			{
				case SIROCCO_SMOKE:
				case SIROCCO_MIST_GROUND:
				case SIROCCO_MIST_COLUMN:
				case SIROCCO_SWIRL:
				case SNOW_DRIFT:
				case MOONLIGHT_BEAMS:
				case MOONLIGHT_SMOKE:
				case MOONLIGHT_AURORA:
				case MOONLIGHT_FILL:
					return true;
				default:
					return false;
			}
		}

		private boolean trailCandidate()
		{
			return this == ASH_GLOW_DOTS;
		}

		private boolean groundAligned()
		{
			return impactRingLayer() || this == SPRING_GROUND_PETALS || this == SPRING_GROUND_LEAVES;
		}

		private boolean velocityAligned()
		{
			return rainStreakLayer() || moonlightLayer();
		}

		private boolean screenAligned()
		{
			return false;
		}

		private float screenRotation()
		{
			return 0f;
		}

		private float screenDriftScale()
		{
			return 0f;
		}

		private boolean proceduralSprite()
		{
			return this == ASH_SMOKE || this == ASH_FLECKS || this == ASH_EMBERS || this == ASH_SPARKS || this == ASH_GLOW_DOTS
				|| this == RAIN_STREAKS || this == PESTILENCE_RAIN || this == RAIN_IMPACT || this == PESTILENCE_RAIN_IMPACT
				|| this == SPRING_PETALS || this == SPRING_LEAVES || this == SPRING_GROUND_PETALS || this == SPRING_GROUND_LEAVES
				|| this == SPRING_MOTES || this == SNOW_FLAKES || this == SNOW_FLURRIES
				|| moonlightLayer();
		}

		private boolean cylindricalBillboard()
		{
			return softFootFade();
		}

		private boolean softVolumeBillboard()
		{
			return false;
		}

		private boolean avoidLargeNeighbors()
		{
			return softVolumeBillboard();
		}

		private float largeSizeThreshold()
		{
			return minSize + (maxSize - minSize) * 0.72f;
		}

		private String externalAssetPrefix()
		{
			switch (this)
			{
				case MOONLIGHT_BEAMS:
					return "light_03_v2";
				case MOONLIGHT_SMOKE:
					return "light_03";
				case MOONLIGHT_AURORA:
				case MOONLIGHT_FILL:
					return "light_";
				case ASH_SMOKE:
					return "smoke_v3_";
				case ASH_EMBERS:
					return "scorched_";
				case SPRING_PETALS:
					return "petal_";
				case SPRING_LEAVES:
					return "leaf_";
				case SPRING_GROUND_PETALS:
					return "petal_";
				case SPRING_GROUND_LEAVES:
					return "leaf_";
				case SPRING_MOTES:
					return "Exfoliate_";
				case SNOW_DRIFT:
					return "dust_v2_";
				default:
					return null;
			}
		}

		private boolean softFootFade()
		{
			switch (this)
			{
				case SIROCCO_SMOKE:
				case SIROCCO_MIST_GROUND:
				case SIROCCO_MIST_COLUMN:
				case SIROCCO_SWIRL:
				case ASH_SMOKE:
				case SNOW_DRIFT:
					return true;
				default:
					return false;
			}
		}

		private boolean softFrameEdges()
		{
			return this == ASH_SMOKE || this == SNOW_DRIFT;
		}

		private boolean softBeamFrame()
		{
			return moonlightLayer();
		}

		private boolean skywardGrowth()
		{
			return moonlightLayer();
		}

		private boolean discreteTextureOrientation()
		{
			return moonlightPlasmaLayer();
		}

		private boolean cameraProximityFade()
		{
			return moonlightLayer();
		}

		private boolean moonlightLayer()
		{
			return this == MOONLIGHT_BEAMS || this == MOONLIGHT_SMOKE
				|| this == MOONLIGHT_AURORA || this == MOONLIGHT_FILL;
		}

		private boolean moonlightPlasmaLayer()
		{
			return this == MOONLIGHT_AURORA || this == MOONLIGHT_FILL;
		}

		private float tintStrength()
		{
			switch (this)
			{
				case SIROCCO_SMOKE:
				case SIROCCO_MIST_GROUND:
				case SIROCCO_MIST_COLUMN:
				case SIROCCO_SWIRL:
					return 0.78f;
				case ASH_SMOKE:
				case SNOW_DRIFT:
					return 0.82f;
				case MOONLIGHT_BEAMS:
				case MOONLIGHT_SMOKE:
				case MOONLIGHT_AURORA:
				case MOONLIGHT_FILL:
					return 0.76f;
				default:
					return 0f;
			}
		}

		private float renderAlphaScale()
		{
			switch (this)
			{
				case MOONLIGHT_SMOKE:
					return 0.06f;
				case MOONLIGHT_BEAMS:
					return 0.12f;
				default:
					return 1f;
			}
		}

		private boolean depthTest()
		{
			return !softFootFade();
		}

		private OpenGlContext.Depth depth()
		{
			if (!depthTest())
			{
				return OpenGlContext.Depth.NONE;
			}
			return impactRingLayer() || this == SPRING_GROUND_PETALS || this == SPRING_GROUND_LEAVES
				? OpenGlContext.Depth.SCENE_EQUAL
				: OpenGlContext.Depth.SCENE;
		}

		private float flutterStrength()
		{
			switch (this)
			{
				case SPRING_PETALS:
					return 7.5f;
				case SPRING_LEAVES:
					return 6.4f;
				case SPRING_MOTES:
					return 3.2f;
				case SNOW_FLAKES:
					return 3.0f;
				case SNOW_FLURRIES:
					return 5.8f;
				default:
					return 0f;
			}
		}

		private float flutterFrequency()
		{
			switch (this)
			{
				case SPRING_PETALS:
					return 2.6f;
				case SPRING_LEAVES:
					return 2.1f;
				case SPRING_MOTES:
					return 1.4f;
				case SNOW_FLAKES:
					return 1.8f;
				case SNOW_FLURRIES:
					return 2.3f;
				default:
					return 0f;
			}
		}
	}

	private static final class LayerTuning
	{
		private final int baseCount;
		private final float minSize;
		private final float maxSize;
		private final float minHeight;
		private final float maxHeight;
		private final float minVelocityY;
		private final float maxVelocityY;
		private final float minAlpha;
		private final float maxAlpha;
		private final float minSpin;
		private final float maxSpin;
		private final float maxInitialRotation;
		private final float minAnimationFps;
		private final float maxAnimationFps;
		private final float minLifetime;
		private final float maxLifetime;
		private final float radius;
		private final float windScale;
		private final float turbulence;
		private final float red;
		private final float green;
		private final float blue;

		private LayerTuning(int baseCount, float minSize, float maxSize,
			float minHeight, float maxHeight, float minVelocityY, float maxVelocityY,
			float minAlpha, float maxAlpha, float minSpin, float maxSpin,
			float maxInitialRotation, float minAnimationFps, float maxAnimationFps, float minLifetime, float maxLifetime,
			float radius, float windScale, float turbulence, float red, float green, float blue)
		{
			this.baseCount = baseCount;
			this.minSize = minSize;
			this.maxSize = maxSize;
			this.minHeight = minHeight;
			this.maxHeight = maxHeight;
			this.minVelocityY = minVelocityY;
			this.maxVelocityY = maxVelocityY;
			this.minAlpha = minAlpha;
			this.maxAlpha = maxAlpha;
			this.minSpin = minSpin;
			this.maxSpin = maxSpin;
			this.maxInitialRotation = maxInitialRotation;
			this.minAnimationFps = minAnimationFps;
			this.maxAnimationFps = maxAnimationFps;
			this.minLifetime = minLifetime;
			this.maxLifetime = maxLifetime;
			this.radius = radius;
			this.windScale = windScale;
			this.turbulence = turbulence;
			this.red = red;
			this.green = green;
			this.blue = blue;
		}
	}
}
