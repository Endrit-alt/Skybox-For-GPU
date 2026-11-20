package com.endrit.gpusky.water;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.runelite.api.FloatProjection;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Replays only opaque zones actually submitted this frame, using the host's existing
 * geometry, roof filtering and transforms. Pass 1 removes water AND its depth;
 * pass 2 draws only replacement water over the opaque scene, before alpha objects,
 * without depth writes. A final-scene callback is only a no-alpha-pass fallback.
 * A water-only color/depth snapshot lets later alpha faces inherit water opacity
 * only on submerged pixels. No stock-water layer, scene mutation or private GPU access.
 */
final class WaterTransparency
{
	static final int WATER_OPACITY_PERCENT = 90;
	private static final Logger log = LoggerFactory.getLogger(WaterTransparency.class);
	private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	private final List<ZoneDraw> zones = new ArrayList<>();
	private final WaterSurfaceBuffer surface = new WaterSurfaceBuffer();
	private int program, passLocation = -1, opacityLocation = -1;
	private int entityLocation = -1, tintLocation = -1, baseLocation = -1;
	private int underwaterLocation = -1, surfaceLocation = -1, depthLocation = -1, viewportLocation = -1;
	private int sceneDepthLocation = -1, depthFadeLocation = -1, depthBiasLocation = -1;
	private boolean captureFailed;
	private boolean active;
	private float opacity, cameraX, cameraY, cameraZ;

	void onProgramCreate(int nextProgram)
	{
		clear();
		program = nextProgram;
		if (program == 0) return;
		passLocation = glGetUniformLocation(program, "mawWaterPass");
		opacityLocation = glGetUniformLocation(program, "mawWaterOpacity");
		entityLocation = glGetUniformLocation(program, "entityProj");
		tintLocation = glGetUniformLocation(program, "entityTint");
		baseLocation = glGetUniformLocation(program, "base");
		underwaterLocation = glGetUniformLocation(program, "mawUnderwaterAlpha");
		surfaceLocation = glGetUniformLocation(program, "mawWaterSurface");
		depthLocation = glGetUniformLocation(program, "mawWaterDepth");
		viewportLocation = glGetUniformLocation(program, "mawWaterViewport");
		sceneDepthLocation = glGetUniformLocation(program, "mawSceneDepth");
		depthFadeLocation = glGetUniformLocation(program, "mawDepthFade");
		depthBiasLocation = glGetUniformLocation(program, "mawDepthBias");
	}

	void beginFrame(boolean enabled, int opacityPercent, float x, float y, float z)
	{
		clear();
		opacity = Math.max(0, Math.min(100, opacityPercent)) / 100f;
		cameraX = x; cameraY = y; cameraZ = z;
		active = enabled && opacity < 1 && program != 0 && passLocation >= 0 && opacityLocation >= 0;
	}

	void drawZone(DrawCallbacks delegate, Projection projection, Scene scene, int x, int z)
	{
		boolean topLevel = scene.getWorldViewId() == WorldView.TOPLEVEL;
		if (!active || (!topLevel && !(projection instanceof FloatProjection))
			|| glGetInteger(GL_CURRENT_PROGRAM) != program)
		{
			delegate.drawZoneOpaque(projection, scene, x, z);
			return;
		}
		float[] transform = topLevel ? IDENTITY : ((FloatProjection) projection).getProjection().clone();
		ZoneDraw draw = new ZoneDraw(delegate, projection, scene, x, z, transform,
			scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		// Capture before submitting. If the call fails, do not replay a partially drawn zone.
		glUniform1i(passLocation, 1);
		try { delegate.drawZoneOpaque(projection, scene, x, z); }
		finally { glUniform1i(passLocation, 0); }
		if (opacity > 0) zones.add(draw);
	}

	void drawWater()
	{
		// Seal capture at the alpha boundary. Late opaque submissions use the normal
		// material path rather than being hidden and replayed over transparent objects.
		active = false; // Calling the saved delegate cannot recursively capture our replay.
		if (zones.isEmpty()) return;
		// The surfaces are ordinarily non-overlapping terrain. Sort zones back-to-front
		// as well, for separate heights/world views; this is not general per-triangle OIT.
		zones.sort(Comparator.comparingDouble(this::distanceSquared).reversed());
		GlState state = new GlState(program, entityLocation, tintLocation, baseLocation);
		try
		{
			glUseProgram(program);
			glUniform1i(passLocation, 2);
			glUniform1f(opacityLocation, opacity);
			glEnable(GL_DEPTH_TEST);
			glDepthFunc(GL_GREATER); // The shared RuneLite client uses reversed depth.
			glDepthMask(false);
			glEnable(GL_BLEND);
			glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
			boolean haveSceneDepth = false;
			if (!captureFailed)
			{
				try { surface.captureSceneDepth(); haveSceneDepth = true; }
				catch (RuntimeException ex) { captureFailed = true; surface.destroy(); log.warn("Water scene-depth capture unavailable", ex); }
			}
			boolean zeroToOne = (org.lwjgl.opengl.GL.getCapabilities().OpenGL45
				|| org.lwjgl.opengl.GL.getCapabilities().GL_ARB_clip_control)
				&& glGetInteger(org.lwjgl.opengl.GL45C.GL_CLIP_DEPTH_MODE) == org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
			glUniform1f(depthBiasLocation, zeroToOne ? 0f : .5f);
			glUniform1i(sceneDepthLocation, WaterSurfaceBuffer.SCENE_DEPTH_UNIT);
			glUniform4iv(viewportLocation, surface.viewport());
			try (WaterSurfaceBuffer.Bindings ignored = surface.bind())
			{
				glUniform1i(depthFadeLocation, haveSceneDepth ? 1 : 0);
				try { replayZones(); }
				finally { glUniform1i(depthFadeLocation, 0); }
			}
			if (!captureFailed)
			{
				try { surface.capture(this::replayZones); }
				catch (RuntimeException ex)
				{
					// Main water is already drawn. Keep that working if allocation fails.
					captureFailed = true;
					surface.destroy();
					log.warn("Water alpha-depth capture unavailable until the next GPU context", ex);
				}
			}
		}
		finally
		{
			glUseProgram(program);
			glUniform1i(passLocation, 0);
			state.restore(program, entityLocation, tintLocation, baseLocation);
			zones.clear(); // Keep the surface snapshot available through the alpha pass.
		}
	}

	private void replayZones()
	{
		for (ZoneDraw draw : zones)
		{
			glUniformMatrix4fv(entityLocation, false, draw.transform);
			glUniform4i(tintLocation, draw.hue, draw.saturation, draw.lightness, draw.tint);
			draw.delegate.drawZoneOpaque(draw.projection, draw.scene, draw.x, draw.z);
		}
	}

	void drawAlpha(DrawCallbacks delegate, Projection projection, Scene scene, int level, int x, int z)
	{
		if (!surface.isValid() || underwaterLocation < 0 || glGetInteger(GL_CURRENT_PROGRAM) != program)
		{
			delegate.drawZoneAlpha(projection, scene, level, x, z);
			return;
		}
		try (WaterSurfaceBuffer.Bindings ignored = surface.bind())
		{
			glUniform1i(surfaceLocation, WaterSurfaceBuffer.COLOR_UNIT);
			glUniform1i(depthLocation, WaterSurfaceBuffer.DEPTH_UNIT);
			glUniform4iv(viewportLocation, surface.viewport());
			glUniform1i(underwaterLocation, 1);
			try { delegate.drawZoneAlpha(projection, scene, level, x, z); }
			finally { glUniform1i(underwaterLocation, 0); }
		}
	}

	/** Before the top-level scene's frame: prepares this frame's snapshot outside the scene's passes. */
	void beforeScene()
	{
		if (captureFailed) return;
		try { surface.clearAhead(); }
		catch (RuntimeException ex)
		{
			captureFailed = true;
			surface.destroy();
			log.warn("Water alpha-depth capture unavailable until the next GPU context", ex);
		}
	}

	void clear() { active = false; zones.clear(); surface.invalidate(); }

	/** Frees the viewport snapshots (reallocated by the next capture); keeps the program locations. */
	void releaseBuffers()
	{
		clear();
		surface.destroy();
	}

	void destroy()
	{
		onProgramCreate(0);
		surface.destroy();
		captureFailed = false;
	}

	private double distanceSquared(ZoneDraw draw)
	{
		int offset = draw.scene.getWorldViewId() == WorldView.TOPLEVEL ? WaterShoreMask.SCENE_OFFSET / 8 : 0;
		float x = (draw.x - offset) * 1024 + 512, z = (draw.z - offset) * 1024 + 512;
		float[] m = draw.transform;
		double dx = m[0] * x + m[8] * z + m[12] - cameraX;
		double dy = m[1] * x + m[9] * z + m[13] - cameraY;
		double dz = m[2] * x + m[10] * z + m[14] - cameraZ;
		return dx * dx + dy * dy + dz * dz;
	}

	private static final class ZoneDraw
	{
		final DrawCallbacks delegate;
		final Projection projection;
		final Scene scene;
		final int x, z, hue, saturation, lightness, tint;
		final float[] transform;
		ZoneDraw(DrawCallbacks delegate, Projection projection, Scene scene, int x, int z,
			float[] transform, int hue, int saturation, int lightness, int tint)
		{
			this.delegate = delegate; this.projection = projection; this.scene = scene;
			this.x = x; this.z = z; this.transform = transform;
			this.hue = hue; this.saturation = saturation; this.lightness = lightness; this.tint = tint;
		}
	}

	/** Only records state modified by this pass or by the host's drawZoneOpaque implementation. */
	private static final class GlState
	{
		final int previousProgram = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		final boolean depthTest = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND);
		final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
		final int depthFunc = glGetInteger(GL_DEPTH_FUNC);
		final int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
		final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
		final int equationRgb = glGetInteger(GL_BLEND_EQUATION_RGB), equationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
		final float[] entity = new float[16];
		final int[] tint = new int[4], base = new int[3];
		GlState(int program, int entityLocation, int tintLocation, int baseLocation)
		{
			if (entityLocation >= 0) glGetUniformfv(program, entityLocation, entity);
			if (tintLocation >= 0) glGetUniformiv(program, tintLocation, tint);
			if (baseLocation >= 0) glGetUniformiv(program, baseLocation, base);
		}
		void restore(int program, int entityLocation, int tintLocation, int baseLocation)
		{
			glUniformMatrix4fv(entityLocation, false, entity);
			glUniform4iv(tintLocation, tint);
			glUniform3iv(baseLocation, base);
			glBindVertexArray(vao);
			glDepthMask(depthMask);
			glDepthFunc(depthFunc);
			if (depthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
			if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
			glBlendEquationSeparate(equationRgb, equationAlpha);
			glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
			glUseProgram(previousProgram);
		}
	}
}
