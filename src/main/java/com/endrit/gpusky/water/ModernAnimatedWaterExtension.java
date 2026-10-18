package com.endrit.gpusky.water;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.api.GpuExtension;
import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ModernAnimatedWaterExtension extends GpuExtension implements WaterDrawCallbacks.Listener
{
	/** Per-region water color (region overrides); null = use the Water color setting. */
	private volatile java.util.function.Supplier<java.awt.Color> waterColorOverride = () -> null;

	void setWaterColorOverride(java.util.function.Supplier<java.awt.Color> source)
	{
		waterColorOverride = source == null ? () -> null : source;
	}

	/** Same as the skybox crossfade (GpuSkyExtension.TRANSITION_NANOS). */
	private static final long WATER_COLOR_FADE_NANOS = 1_000_000_000L;
	private volatile java.util.function.BooleanSupplier instantTransitions = () -> false;
	private java.awt.Color lastRegionColor;
	private boolean regionColorKnown;
	private float[] fadeFrom;
	private long fadeStart;

	void setInstantTransitions(java.util.function.BooleanSupplier source)
	{
		instantTransitions = source == null ? () -> false : source;
	}

	/**
	 * Water color for this frame. A region override switch (its color is published
	 * together with the skybox) fades over the sky's crossfade; editing the color
	 * setting itself applies immediately so the picker preview stays live.
	 */
	private float[] currentWaterColor()
	{
		java.awt.Color regionColor = waterColorOverride.get();
		java.awt.Color main = config.waterColor() == null ? WaterConfig.DEFAULT_WATER_COLOR : config.waterColor();
		java.awt.Color target = regionColor != null ? regionColor : main;
		float[] targetRgb = {target.getRed() / 255f, target.getGreen() / 255f, target.getBlue() / 255f};
		long now = System.nanoTime();
		if (regionColorKnown && !java.util.Objects.equals(regionColor, lastRegionColor) && !instantTransitions.getAsBoolean())
		{
			fadeFrom = displayedWaterColor != null ? displayedWaterColor.clone() : targetRgb;
			fadeStart = now;
		}
		lastRegionColor = regionColor;
		regionColorKnown = true;
		float progress = fadeFrom == null ? 1f : Math.min(1f, (now - fadeStart) / (float) WATER_COLOR_FADE_NANOS);
		if (progress >= 1f) fadeFrom = null;
		float[] shown = new float[3];
		for (int i = 0; i < 3; i++)
		{
			shown[i] = fadeFrom == null ? targetRgb[i] : fadeFrom[i] + (targetRgb[i] - fadeFrom[i]) * progress;
		}
		displayedWaterColor = shown;
		return shown;
	}

	private float[] displayedWaterColor;

	private static final Logger log = LoggerFactory.getLogger(ModernAnimatedWaterExtension.class);
	private static final int SHORE_TEXTURE_UNIT = 7;
	private static final int PREVIOUS_SHORE_TEXTURE_UNIT = 8;
	private static final int SHORE_COVERAGE_TEXTURE_UNIT = 9;
	private static final int BANK_COLOR_TEXTURE_UNIT = 10;
	private static final int SKY_CURRENT_TEXTURE_UNIT = 11;
	private static final int SKY_PREVIOUS_TEXTURE_UNIT = 12;
	private static final String VERTEX_DEFINITIONS = "out vec3 mawWorldPosition;\nout vec3 mawToCamera;\nuniform int mawWaterPass;";
	private static final String VERTEX_MAIN_POST = "mawWorldPosition = worldPos.xyz;\nmawToCamera = cameraPos - worldPos.xyz;\n"
		+ "if (mawWaterPass == 2 && !(" + WaterSurfaceClassifier.GPU_SHADER_PREDICATE + ")) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);";
	private static final String FRAGMENT_DEFINITIONS = readShader("water-definitions.glsl");
	private static final String FRAGMENT_MAIN_POST = readShader("water-fragment.glsl")
		.replace("__WATER_PREDICATE__", WaterSurfaceClassifier.GPU_SHADER_PREDICATE);
	private final Client client;
	private final WaterConfig config;
	private final WaterShoreMask shoreMask = new WaterShoreMask();
	private final BankColorMap bankColors = new BankColorMap();
	private final SkyboxBridge skybox = new SkyboxBridge();
	private final FoamSettings foam = new FoamSettings();
	private final BubbleEvents bubbles = new BubbleEvents();
	private final WaterTransparency transparency = new WaterTransparency();
	private WaterDrawCallbacks callbacks;
	private final Map<String, Integer> locations = new HashMap<>();
	private static final String[] SCENE_UNIFORMS = {
		"mawEnabled", "mawTime", "mawSpeed", "mawWaveStrength", "mawWaterBrightness", "mawWaterTint",
		"mawHighlightIntensity", "mawNormalDetailStrength", "mawAlternateWaves", "mawShoreFollowingWaves", "mawOpenRippleStrength", "mawSurfaceGrainStrength", "mawSurfaceDepth", "mawSoftReliefShadows", "mawSwashFoam", "mawSwashOpacity", "mawHazeStyle", "mawSkyEnabled", "mawSkyFog", "mawSkyScreen", "mawSkyViewport", "mawSkyDistanceFade", "mawSkyCurrent", "mawSkyPrevious", "mawSkyBlend", "mawSkyYawOffset", "mawSkyPitchOffset", "mawSkyExposure", "mawSkyLight", "mawSkyContrast", "mawSkySun", "mawCameraAngles", "mawSkyReflection", "mawSkyGlint", "mawReflectionNoise", "mawGlintFollowsCamera", "mawBankColor", "mawBankColorValid", "mawBankOffset", "mawDebugView",
		"mawFoamOpacity[0]", "mawFoamWidth[0]", "mawFoamSoftness[0]", "mawShoreMask",
		"mawShoreTransform", "mawBubbleCount", "mawBubbleOpacity", "mawShoreMaskValid",
		"mawWorldBase", "mawBubbles[0]", "mawBubbleScale[0]", "mawShoreHistory", "mawPreviousShoreMask",
		"mawShoreCoverage", "mawShoreOffset", "mawPreviousShoreOffset", "mawShoreBlend", "mawPreviousShoreValid"
	};
	private int cachedProgram;
	private boolean contextActive;
	private boolean updatedBeforeScene;
	private long previousNanos;
	private int framesWithoutScene;
	private double animationTime;

	ModernAnimatedWaterExtension(Client client, WaterConfig config)
	{
		this.client = client;
		this.config = config;
	}

	@Override
	public String injectShaderExtension(String hook)
	{
		switch (hook)
		{
			case "rlst_vert_definitions": return VERTEX_DEFINITIONS;
			case "rlst_vert_main_post": return VERTEX_MAIN_POST;
			case "rlst_frag_definitions": return FRAGMENT_DEFINITIONS;
			case "rlst_frag_main_post": return FRAGMENT_MAIN_POST;
			default: return null;
		}
	}

	@Override
	public void onContextCreate()
	{
		checkClientThread();
		if (contextActive) return;
		shoreMask.create();
		bankColors.create();
		locations.clear();
		cachedProgram = 0;
		previousNanos = System.nanoTime();
		contextActive = true;
		// The GPU replaces client draw callbacks on every startup, not just the
		// plugin's first registration. Reattach after it installs the new callbacks.
		attachDrawCallbacks();
		log.info("Water for GPU context created: shared GPU API, RGBA16F shore field, independent layers and bubbles");
	}

	@Override
	public void onContextDestroy()
	{
		checkClientThread();
		detachDrawCallbacks(); // Clear the old wrapper before the GPU clears/replaces it.
		shoreMask.destroy();
		bankColors.destroy();
		bubbles.clear();
		transparency.destroy();
		locations.clear();
		cachedProgram = 0;
		updatedBeforeScene = false;
		contextActive = false;
	}

	@Override
	public void onProgramCreate(int program)
	{
		checkClientThread();
		if (!contextActive) onContextCreate();
		transparency.onProgramCreate(program);
		cachedProgram = program;
		skybox.onProgram(program);
		locations.clear(); // Refresh even if GL reuses the same ID after relinking.
		updatedBeforeScene = false;
		if (program == 0) return;
		for (String name : SCENE_UNIFORMS) location(name);
		// Only query locations here: the previously bound program can be pending
		// deletion during relinking, so binding/restoring it here would be unsafe.
		log.debug("Water for GPU scene uniform cache refreshed for program {}", program);
	}

	@Override
	public boolean drawSkybox()
	{
		// This supported callback precedes terrain when the scene has no native skybox.
		// Only prepare our water material; never claim or replace the sky.
		updateSceneUniforms();
		updatedBeforeScene = true;
		return false;
	}

	void attachDrawCallbacks()
	{
		checkClientThread();
		if (callbacks != null) return;
		DrawCallbacks delegate = client.getDrawCallbacks();
		if (delegate == null) return;
		callbacks = new WaterDrawCallbacks(delegate, this);
		client.setDrawCallbacks(callbacks.proxy);
	}

	void detachDrawCallbacks()
	{
		if (callbacks == null) return;
		checkClientThread();
		callbacks.deactivate();
		// Another plugin may have wrapped us since startup. Do not remove its wrapper;
		// our deactivated proxy becomes pure passthrough in that case.
		if (client.getDrawCallbacks() == callbacks.proxy) client.setDrawCallbacks(callbacks.delegate);
		callbacks = null;
		transparency.clear();
	}

	@Override public void afterScene(Scene scene, float cameraX, float cameraY, float cameraZ)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL) return;
		framesWithoutScene = 0;
		// Runs AFTER the GPU has established the framebuffer/program, including native
		// skybox scenes which do not call the extension's drawSkybox hook.
		// Self-heal: another extension registering/unregistering recompiles the shared
		// scene program. If that program-created notification did not reach us, we
		// would keep writing uniforms to the old (deleted) program and the new one
		// would render stock water until Water was toggled. Adopt the bound program.
		int boundProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
		if (boundProgram != 0 && boundProgram != cachedProgram
			&& GL33C.glGetUniformLocation(boundProgram, "mawEnabled") >= 0)
		{
			log.info("Water for GPU: adopting recompiled scene program {} (was {})", boundProgram, cachedProgram);
			onProgramCreate(boundProgram);
		}
		if (!updatedBeforeScene) updateSceneUniforms();
		else if (contextActive && cachedProgram != 0 && config.animatedWater())
		{
			// Skybox may run after our drawSkybox callback; read its current-frame values now.
			int previousProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
			try
			{
				GL33C.glUseProgram(cachedProgram);
				updateSkyUniforms(WaterDebugView.NORMAL.ordinal());
			}
			finally { GL33C.glUseProgram(previousProgram); }
		}
		updatedBeforeScene = true;
		transparency.beginFrame(contextActive && config.animatedWater(), WaterTransparency.WATER_OPACITY_PERCENT,
			cameraX, cameraY, cameraZ);
	}

	@Override public void drawZone(DrawCallbacks delegate, Projection projection, Scene scene, int x, int z)
	{
		transparency.drawZone(delegate, projection, scene, x, z);
	}

	@Override public void beforeAlpha()
	{
		// Crystals/ghosts and other alpha faces do not populate the depth buffer.
		// Compositing water after them would paint over them regardless of height.
		transparency.drawWater();
	}

	@Override public void drawAlpha(DrawCallbacks delegate, Projection projection, Scene scene, int level, int x, int z)
	{
		transparency.drawAlpha(delegate, projection, scene, level, x, z);
	}

	private void updateSceneUniforms()
	{
		if (!contextActive || cachedProgram == 0) return;
		checkClientThread();
		int previousProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
		try
		{
			GL33C.glUseProgram(cachedProgram);
			writeSceneUniforms();
		}
		finally { GL33C.glUseProgram(previousProgram); }
	}

	private void writeSceneUniforms()
	{
		long now = System.nanoTime();
		animationTime += Math.min(1.0, Math.max(0, (now - previousNanos) / 1_000_000_000.0))
			* Math.max(0, config.animationSpeed()) / 100.0;
		previousNanos = now;
		set1i("mawEnabled", config.animatedWater() ? 1 : 0);
		if (!config.animatedWater()) { bubbles.clear(); return; } // No scene capture/worker/upload while disabled.

		foam.update(config);
		int debug = WaterDebugView.NORMAL.ordinal();
		boolean bubbleVisible = config.bubbles() && config.bubbleFrequency() > 0 && config.bubbleOpacity() > 0
			&& (debug == 0 || WaterDebugView.NORMAL == WaterDebugView.BUBBLES);
		set1f("mawTime", (float) animationTime);
		set1f("mawSpeed", 1);
		set1f("mawWaveStrength", config.waveStrength() / 100f);
		set1f("mawWaterBrightness", (245f + config.waterBrightnessAdjustment()) / 100f);
		float[] tint = currentWaterColor();
		GL33C.glUniform3f(location("mawWaterTint"), tint[0], tint[1], tint[2]);
		// The original built-in highlights/sheen are removed (skybox reflection and
		// sun glint replace them); the shader terms are kept but switched off.
		set1f("mawHighlightIntensity", 0);
		set1f("mawNormalDetailStrength", debug == 0 ? 0.14f : 0);
		set1i("mawAlternateWaves", 1); // former "Wider reflective waves" checkbox, kept at its default
		set1i("mawShoreFollowingWaves", debug == 0 && config.shoreFollowingWaves() ? 1 : 0);
		set1f("mawOpenRippleStrength", debug == 0 ? config.openRippleStrength() / 100f : 0);
		set1f("mawSurfaceGrainStrength", debug == 0 ? config.surfaceGrainStrength() / 100f : 0);
		set1f("mawSurfaceDepth", debug == 0 ? config.surfaceDepth() / 100f * 0.15f : 0);
		set1i("mawSoftReliefShadows", config.softSurfaceShadows() ? 1 : 0);
		boolean swash = debug == 0 && config.shoreFoam() && config.swashFoam();
		set1i("mawSwashFoam", swash ? 1 : 0);
		set1f("mawSwashOpacity", swash ? config.swashFoamOpacity() / 100f : 0);
		set1i("mawHazeStyle", config.swashHazeStyle().ordinal());
		updateSkyUniforms(debug);
		set1i("mawDebugView", debug);
		GL33C.glUniform1fv(location("mawFoamOpacity[0]"), foam.opacity);
		GL33C.glUniform1fv(location("mawFoamWidth[0]"), foam.width);
		GL33C.glUniform1fv(location("mawFoamSoftness[0]"), foam.softness);
		set1i("mawShoreMask", SHORE_TEXTURE_UNIT);
		set1i("mawPreviousShoreMask", PREVIOUS_SHORE_TEXTURE_UNIT);
		set1i("mawShoreCoverage", SHORE_COVERAGE_TEXTURE_UNIT);
		set1i("mawShoreHistory", 1);
		set1i("mawPreviousShoreValid", 0);
		set1f("mawShoreBlend", 1);
		GL33C.glUniform2f(location("mawShoreTransform"), WaterShoreMask.SCENE_OFFSET,
			1f / net.runelite.api.Constants.EXTENDED_SCENE_SIZE);
		set1i("mawBubbleCount", 0);
		set1f("mawBubbleOpacity", bubbleVisible ? config.bubbleOpacity() / 100f : 0);
		set1i("mawShoreMaskValid", 0);
		set1i("mawBankColor", BANK_COLOR_TEXTURE_UNIT);
		set1i("mawBankColorValid", 0);
		WorldView view = client.getTopLevelWorldView();
		if (view == null) { shoreMask.update(null, 0, 0); return; }
		GL33C.glUniform2f(location("mawWorldBase"), view.getBaseX() * 128f, view.getBaseY() * 128f);

		// Depth-faded transparency also needs the bank-contact guard, even with
		// all cosmetic shoreline effects switched off.
		boolean needMask = debug == 0 || foam.anyVisible() || bubbleVisible || debug == 6 || debug == 7;
		if (!needMask) return;
		int previousTexture = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
		try
		{
			GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + SHORE_TEXTURE_UNIT);
			shoreMask.update(view.getScene(), view.getBaseX(), view.getBaseY());
			shoreMask.bind(SHORE_TEXTURE_UNIT);
			shoreMask.bindHistory(PREVIOUS_SHORE_TEXTURE_UNIT, SHORE_COVERAGE_TEXTURE_UNIT);
			if (swash)
			{
				double brightness = client.getTextureProvider() != null ? client.getTextureProvider().getBrightness() : 0.8;
				bankColors.update(view.getScene(), view.getBaseX(), view.getBaseY(), brightness);
				bankColors.bind(BANK_COLOR_TEXTURE_UNIT);
			}
		}
		finally { GL33C.glActiveTexture(previousTexture); }
		set1i("mawShoreMaskValid", shoreMask.isValid() ? 1 : 0);
		set1i("mawBankColorValid", swash && bankColors.isValid() ? 1 : 0);
		GL33C.glUniform2f(location("mawBankOffset"), bankColors.offsetX(), bankColors.offsetY());
		set1i("mawPreviousShoreValid", shoreMask.hasPrevious() ? 1 : 0);
		set1f("mawShoreBlend", shoreMask.blend());
		GL33C.glUniform2f(location("mawShoreOffset"), shoreMask.offsetX(false), shoreMask.offsetY(false));
		GL33C.glUniform2f(location("mawPreviousShoreOffset"), shoreMask.offsetX(true), shoreMask.offsetY(true));
		if (bubbleVisible && shoreMask.isValid())
		{
			float focusX = view.getBaseX() + 52, focusY = view.getBaseY() + 52;
			if (client.getLocalPlayer() != null)
			{
				focusX = view.getBaseX() + client.getLocalPlayer().getLocalLocation().getX() / 128f;
				focusY = view.getBaseY() + client.getLocalPlayer().getLocalLocation().getY() / 128f;
			}
			int count = bubbles.update(shoreMask.bubbleSites(), animationTime,
				config.bubbleFrequency() / 100f, focusX, focusY);
			set1i("mawBubbleCount", count);
			if (count > 0)
			{
				GL33C.glUniform4fv(location("mawBubbles[0]"), bubbles.uniforms);
				GL33C.glUniform1fv(location("mawBubbleScale[0]"), bubbles.scales);
			}
		}
		else bubbles.clear();
	}

	@Override
	public void onPostDrawToplevel()
	{
		// Self-heal the draw-callback wrapper: if logged-in frames keep drawing without
		// reaching our afterScene, something replaced the callbacks we wrapped (seen
		// at launch and when another GPU extension toggles). Wrap the current ones.
		if (contextActive && config.animatedWater() && client.getGameState() == net.runelite.api.GameState.LOGGED_IN
			&& ++framesWithoutScene > 5 && (callbacks == null || client.getDrawCallbacks() != callbacks.proxy))
		{
			log.info("Water for GPU: draw callbacks were replaced; re-attaching");
			detachDrawCallbacks();
			attachDrawCallbacks();
			framesWithoutScene = 0;
		}
		try
		{
			// Only a fallback for frames without an alpha pass; normally already flushed.
			transparency.drawWater();
			// Fallback if another renderer has replaced our public callback wrapper.
			if (!updatedBeforeScene) updateSceneUniforms();
		}
		finally { updatedBeforeScene = false; }
	}

	/** Mirrors the Weather for GPU skybox into our own sampler units (read-only). */
	private void updateSkyUniforms(int debug)
	{
		// Fog matching always follows the skybox (it is the scene's fog); the
		// reflection/glint is the optional part.
		skybox.update();
		boolean fog = skybox.available;
		boolean on = fog && debug == 0 && config.skyReflections();
		set1i("mawSkyFog", fog ? 1 : 0);
		set1i("mawSkyEnabled", on ? 1 : 0);
		set1i("mawSkyCurrent", SKY_CURRENT_TEXTURE_UNIT);
		set1i("mawSkyPrevious", SKY_PREVIOUS_TEXTURE_UNIT);
		set1f("mawSkyReflection", config.skyReflectionStrength() * 0.03f); // 100% = 3x the original scale
		set1f("mawSkyGlint", config.sunGlintStrength() * 0.002f); // 100% = former 20%
		set1i("mawReflectionNoise", config.reflectionNoise() ? 1 : 0);
		set1i("mawGlintFollowsCamera", config.glintFollowsCamera() ? 1 : 0);
		if (!fog) return;
		GL33C.glUniform2f(location("mawSkyScreen"), skybox.fov, skybox.aspect);
		GL33C.glUniform4f(location("mawSkyViewport"), skybox.originX, skybox.originY, skybox.viewportW, skybox.viewportH);
		GL33C.glUniform2f(location("mawSkyDistanceFade"), skybox.drawDistance, skybox.worldLight);
		set1f("mawSkyBlend", skybox.blend);
		set1f("mawSkyYawOffset", skybox.yawOffset);
		set1f("mawSkyPitchOffset", skybox.pitchOffset);
		set1f("mawSkyExposure", skybox.exposure);
		set1f("mawSkyContrast", skybox.sourceContrast);
		GL33C.glUniform4f(location("mawSkySun"), skybox.shownSunU, skybox.shownSunV, skybox.shownStandOut, skybox.shownGlintFade);
		GL33C.glUniform3f(location("mawSkyLight"), skybox.lightX, skybox.lightY, skybox.lightZ);
		GL33C.glUniform2f(location("mawCameraAngles"), (float) client.getCameraFpYaw(), (float) client.getCameraFpPitch());
		int previousActive = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
		try
		{
			GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + SKY_CURRENT_TEXTURE_UNIT);
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, skybox.currentTexture);
			GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + SKY_PREVIOUS_TEXTURE_UNIT);
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, skybox.previousTexture);
		}
		finally { GL33C.glActiveTexture(previousActive); }
	}

	void markShoreMaskDirty() { shoreMask.markDirty(); bankColors.markDirty(); }
	void clearShoreHistory() { shoreMask.clearHistory(); bubbles.clear(); }
	void close() { detachDrawCallbacks(); if (contextActive) onContextDestroy(); }

	private int location(String name)
	{
		return locations.computeIfAbsent(name, key -> GL33C.glGetUniformLocation(cachedProgram, key));
	}
	private void set1f(String name, float value) { GL33C.glUniform1f(location(name), value); }
	private void set1i(String name, int value) { GL33C.glUniform1i(location(name), value); }

	private void checkClientThread()
	{
		if (!client.isClientThread()) throw new IllegalStateException("Water for GPU calls must run on the client thread");
	}

	static String readShader(String name)
	{
		try (InputStream input = ModernAnimatedWaterExtension.class.getResourceAsStream(name))
		{
			if (input == null) throw new IllegalStateException("Missing water shader: " + name);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) { throw new IllegalStateException("Cannot read water shader: " + name, e); }
	}
}
