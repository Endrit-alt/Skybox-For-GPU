package com.endrit.gpusky.water;

import org.lwjgl.opengl.GL33C;

/**
 * Read-only bridge to the "Weather for GPU" skybox. That plugin injects its own
 * uniforms into the shared scene program and sets them every frame; this class
 * only reads them back from OpenGL (no dependency on its code or classes):
 * the panorama textures, transition blend, exposure, yaw/pitch offsets and the
 * world direction of the brightest point in the sky (sun or moon).
 *
 * If those uniforms are absent (plugin off), or anything looks invalid, the
 * bridge reports unavailable and the water keeps its built-in sky colors.
 */
final class SkyboxBridge
{
	private int program;
	private int enabledLoc = -1, currentLoc = -1, previousLoc = -1, blendLoc = -1,
		yawLoc = -1, pitchLoc = -1, exposureLoc = -1, lightLoc = -1, contrastLoc = -1,
		fovLoc = -1, aspectLoc = -1, viewportLoc = -1, originLoc = -1, drawDistanceLoc = -1, worldLightLoc = -1;
	private final float[] pair = new float[2];
	private int analysedTexture;
	private long analysedAt;
	/** Brightest point of the current panorama (texture UV) and how much it stands out (0..1+). */
	float sunU = 0.5f, sunV = 0.75f, sunStandOut;
	/** Per-texture sun analysis (u, v, stand-out), so a crossfade can blend old and new sky. */
	private final java.util.Map<Integer, float[]> sunByTexture = new java.util.LinkedHashMap<Integer, float[]>(8, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<Integer, float[]> eldest)
		{
			return size() > 6;
		}
	};
	/** Published to the shader: sun position, stand-out blended over the sky crossfade, glint fade. */
	float shownSunU = 0.5f, shownSunV = 0.75f, shownStandOut, shownGlintFade = 1f;
	private final float[] light = new float[3];

	boolean available;
	int currentTexture, previousTexture;
	float blend, yawOffset, pitchOffset, exposure;
	/** How much the brightest point stands out: 0 = flat/overcast sky, 1 = clear sun or moon. */
	float sourceContrast = 1f;
	/** The skybox's own screen projection and distance fade, so water fog matches the scene. */
	float fov, aspect, viewportW, viewportH, originX, originY, drawDistance, worldLight;
	float lightX, lightY = 1, lightZ;

	/** Call after the scene program is (re)created or changed. */
	void onProgram(int program)
	{
		this.program = program;
		if (program == 0) { enabledLoc = -1; return; }
		enabledLoc = GL33C.glGetUniformLocation(program, "gpuSkyEnabled");
		currentLoc = GL33C.glGetUniformLocation(program, "gpuSkyCurrent");
		previousLoc = GL33C.glGetUniformLocation(program, "gpuSkyPrevious");
		blendLoc = GL33C.glGetUniformLocation(program, "gpuSkyBlend");
		yawLoc = GL33C.glGetUniformLocation(program, "gpuSkyYawOffset");
		pitchLoc = GL33C.glGetUniformLocation(program, "gpuSkyPitchOffset");
		exposureLoc = GL33C.glGetUniformLocation(program, "gpuSkyExposure");
		lightLoc = GL33C.glGetUniformLocation(program, "gpuSkyLightDirection");
		contrastLoc = GL33C.glGetUniformLocation(program, "gpuSkySourceContrast");
		fovLoc = GL33C.glGetUniformLocation(program, "gpuSkyFov");
		aspectLoc = GL33C.glGetUniformLocation(program, "gpuSkyAspect");
		viewportLoc = GL33C.glGetUniformLocation(program, "gpuSkyViewport");
		originLoc = GL33C.glGetUniformLocation(program, "gpuSkyViewportOrigin");
		drawDistanceLoc = GL33C.glGetUniformLocation(program, "gpuSkyDrawDistance");
		worldLightLoc = GL33C.glGetUniformLocation(program, "gpuSkyWorldLight");
	}

	/** Reads the values the skybox plugin last set on the scene program. */
	void update()
	{
		available = false;
		if (program == 0 || enabledLoc < 0 || currentLoc < 0 || exposureLoc < 0 || lightLoc < 0) return;
		if (GL33C.glGetUniformi(program, enabledLoc) == 0) return;
		currentTexture = boundTexture(GL33C.glGetUniformi(program, currentLoc));
		previousTexture = previousLoc < 0 ? 0 : boundTexture(GL33C.glGetUniformi(program, previousLoc));
		if (currentTexture == 0) return;
		if (previousTexture == 0) previousTexture = currentTexture;
		blend = blendLoc < 0 ? 1f : GL33C.glGetUniformf(program, blendLoc);
		yawOffset = yawLoc < 0 ? 0f : GL33C.glGetUniformf(program, yawLoc);
		pitchOffset = pitchLoc < 0 ? 0f : GL33C.glGetUniformf(program, pitchLoc);
		exposure = GL33C.glGetUniformf(program, exposureLoc);
		sourceContrast = contrastLoc < 0 ? 1f : Math.max(0f, Math.min(1f, GL33C.glGetUniformf(program, contrastLoc)));
		GL33C.glGetUniformfv(program, lightLoc, light);
		float length = (float) Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);
		if (!(length > 0.0001f) || !(exposure > 0f)) return;
		lightX = light[0] / length; lightY = light[1] / length; lightZ = light[2] / length;
		fov = fovLoc < 0 ? 1.2f : GL33C.glGetUniformf(program, fovLoc);
		aspect = aspectLoc < 0 ? 1.6f : GL33C.glGetUniformf(program, aspectLoc);
		if (viewportLoc >= 0) { GL33C.glGetUniformfv(program, viewportLoc, pair); viewportW = pair[0]; viewportH = pair[1]; }
		if (originLoc >= 0) { GL33C.glGetUniformfv(program, originLoc, pair); originX = pair[0]; originY = pair[1]; }
		drawDistance = drawDistanceLoc < 0 ? 0f : GL33C.glGetUniformf(program, drawDistanceLoc);
		worldLight = worldLightLoc < 0 ? 0f : GL33C.glGetUniformf(program, worldLightLoc);
		available = true;
		// Re-check every few seconds too: the sun moves within one texture over the day.
		if (System.nanoTime() - analysedAt > 3_000_000_000L) sunByTexture.remove(currentTexture);
		float[] current = sunFor(currentTexture);
		float[] previous = previousTexture == currentTexture ? current : sunFor(previousTexture);
		float t = Math.max(0f, Math.min(1f, blend));
		// Stand-out (reflection strength on dull skies, glint presence) blends with the sky;
		// the glint itself fades out at the old sun and back in at the new one.
		shownStandOut = previous[2] + (current[2] - previous[2]) * t;
		boolean early = previous != current && t < 0.5f;
		float[] sun = early ? previous : current;
		shownSunU = sun[0];
		shownSunV = sun[1];
		shownGlintFade = previous == current ? 1f : Math.abs(2f * t - 1f);
	}

	/**
	 * Finds the sun/moon in the panorama itself: reads one small mip level once per
	 * texture (read-only), takes the brightest smoothed texel in the upper half and
	 * measures how far it stands out from the sky's average.
	 */
	private float[] sunFor(int texture)
	{
		float[] cached = sunByTexture.get(texture);
		if (cached != null) return cached;
		analyse(texture);
		float[] result = {sunU, sunV, sunStandOut};
		sunByTexture.put(texture, result);
		return result;
	}

	private void analyse(int texture)
	{
		analysedTexture = texture;
		analysedAt = System.nanoTime();
		sunStandOut = 0f;
		int previousActive = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
		int previousBinding;
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
		previousBinding = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		int previousPack = GL33C.glGetInteger(GL33C.GL_PIXEL_PACK_BUFFER_BINDING);
		try
		{
			GL33C.glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, 0);
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
			int width = GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_TEXTURE_WIDTH);
			int level = 0;
			while ((width >> level) > 128) level++;
			int w = GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, level, GL33C.GL_TEXTURE_WIDTH);
			int h = GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, level, GL33C.GL_TEXTURE_HEIGHT);
			if (w <= 0 || h <= 0 || w > 512 || h > 512) return;
			float[] rgba = new float[w * h * 4];
			GL33C.glGetTexImage(GL33C.GL_TEXTURE_2D, level, GL33C.GL_RGBA, GL33C.GL_FLOAT, rgba);
			float[] lum = new float[w * h];
			float total = 0;
			for (int i = 0; i < w * h; i++)
			{
				lum[i] = rgba[i * 4] * 0.2126f + rgba[i * 4 + 1] * 0.7152f + rgba[i * 4 + 2] * 0.0722f;
				total += lum[i];
			}
			float mean = total / (w * h);
			float best = -1;
			int bestX = 0, bestY = h * 3 / 4;
			// Upper hemisphere only (v >= 0.5 in the panorama's mapping), 3x3 smoothed.
			for (int y = h / 2; y < h; y++) for (int x = 0; x < w; x++)
			{
				float sum = 0; int n = 0;
				for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
				{
					int yy = Math.max(0, Math.min(h - 1, y + dy)), xx = Math.floorMod(x + dx, w);
					sum += lum[yy * w + xx]; n++;
				}
				float v = sum / n;
				if (v > best) { best = v; bestX = x; bestY = y; }
			}
			sunU = (bestX + 0.5f) / w;
			sunV = (bestY + 0.5f) / h;
			sunStandOut = (best - mean) / Math.max(mean, 0.05f);
		}
		catch (RuntimeException e) { sunStandOut = 0f; }
		finally
		{
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, previousBinding);
			GL33C.glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, previousPack);
			GL33C.glActiveTexture(previousActive);
		}
	}

	/** The 2D texture currently bound on a texture unit (the sampler's unit). */
	private static int boundTexture(int unit)
	{
		if (unit < 0 || unit > 31) return 0;
		int previousActive = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
		try
		{
			GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
			int texture = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
			return texture != 0 && GL33C.glIsTexture(texture) ? texture : 0;
		}
		finally { GL33C.glActiveTexture(previousActive); }
	}
}
