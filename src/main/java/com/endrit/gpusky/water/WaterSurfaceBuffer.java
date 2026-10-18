package com.endrit.gpusky.water;

import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL33C.*;

/** Viewport-sized water-only color/depth snapshot; never contains the stock water or scene background. */
final class WaterSurfaceBuffer
{
	static final int SCENE_DEPTH_UNIT = 4, COLOR_UNIT = 5, DEPTH_UNIT = 6;
	private int framebuffer, sceneFramebuffer, sceneDepth, color, depth, width, height;
	private final int[] viewport = new int[4];
	private boolean valid;

	boolean isValid() { return valid; }
	int[] viewport() { return viewport; }
	void invalidate() { valid = false; }

	/** Resolve opaque depth before water blending, including multisampled scenes. */
	void captureSceneDepth()
	{
		glGetIntegerv(GL_VIEWPORT, viewport);
		CaptureState state = new CaptureState();
		try
		{
			allocate(viewport[2], viewport[3]);
			glDisable(GL_SCISSOR_TEST);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, state.drawFramebuffer);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneFramebuffer);
			glBlitFramebuffer(viewport[0], viewport[1], viewport[0] + width, viewport[1] + height,
				0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
		}
		finally { state.restore(viewport); }
	}

	void capture(Runnable drawWater)
	{
		valid = false;
		glGetIntegerv(GL_VIEWPORT, viewport);
		if (viewport[2] <= 0 || viewport[3] <= 0) return;
		CaptureState state = new CaptureState();
		try
		{
			allocate(viewport[2], viewport[3]);
			glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
			glViewport(0, 0, width, height);
			glDisable(GL_SCISSOR_TEST);
			glDisable(GL_BLEND);
			glEnable(GL_DEPTH_TEST);
			glDepthFunc(GL_GREATER);
			glDepthMask(true);
			glColorMask(true, true, true, true);
			glClearColor(0, 0, 0, 0);
			glClearDepth(0);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
			drawWater.run();
			valid = true;
		}
		finally { state.restore(viewport); }
	}

	private void allocate(int nextWidth, int nextHeight)
	{
		if (framebuffer != 0 && width == nextWidth && height == nextHeight) return;
		int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
		int previousUnpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
		try
		{
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			if (framebuffer == 0)
			{
				framebuffer = glGenFramebuffers();
				color = glGenTextures();
				depth = glGenTextures();
				sceneDepth = glGenTextures();
				sceneFramebuffer = glGenFramebuffers();
			}
			glBindTexture(GL_TEXTURE_2D, color);
			textureParameters();
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, nextWidth, nextHeight, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
			glBindTexture(GL_TEXTURE_2D, depth);
			textureParameters();
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, nextWidth, nextHeight, 0,
				GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
			glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
			glDrawBuffer(GL_COLOR_ATTACHMENT0);
			glReadBuffer(GL_COLOR_ATTACHMENT0);
			int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
			if (status != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Water surface framebuffer: " + status);
			glBindTexture(GL_TEXTURE_2D, sceneDepth);
			textureParameters();
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, nextWidth, nextHeight, 0,
				GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
			glBindFramebuffer(GL_FRAMEBUFFER, sceneFramebuffer);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, sceneDepth, 0);
			glDrawBuffer(GL_NONE); glReadBuffer(GL_NONE);
			if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
				throw new IllegalStateException("Water scene-depth framebuffer incomplete");
			width = nextWidth; height = nextHeight;
		}
		finally
		{
			glBindTexture(GL_TEXTURE_2D, previousTexture);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, previousUnpack);
		}
	}

	private static void textureParameters()
	{
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}

	Bindings bind() { return new Bindings(color, depth, sceneDepth); }

	void destroy()
	{
		valid = false;
		if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
		if (color != 0) glDeleteTextures(color);
		if (depth != 0) glDeleteTextures(depth);
		if (sceneDepth != 0) glDeleteTextures(sceneDepth);
		if (sceneFramebuffer != 0) glDeleteFramebuffers(sceneFramebuffer);
		framebuffer = sceneFramebuffer = sceneDepth = color = depth = width = height = 0;
	}

	/** Scoped bindings: no persistent takeover of another extension's texture units. */
	static final class Bindings implements AutoCloseable
	{
		private final int active = glGetInteger(GL_ACTIVE_TEXTURE);
		private final int colorTexture, depthTexture, sceneTexture, colorSampler, depthSampler, sceneSampler;
		Bindings(int color, int depth, int sceneDepth)
		{
			sceneSampler = glGetIntegeri(GL_SAMPLER_BINDING, SCENE_DEPTH_UNIT);
			glActiveTexture(GL_TEXTURE0 + SCENE_DEPTH_UNIT);
			sceneTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
			glBindTexture(GL_TEXTURE_2D, sceneDepth); glBindSampler(SCENE_DEPTH_UNIT, 0);
			colorSampler = glGetIntegeri(GL_SAMPLER_BINDING, COLOR_UNIT);
			depthSampler = glGetIntegeri(GL_SAMPLER_BINDING, DEPTH_UNIT);
			glActiveTexture(GL_TEXTURE0 + COLOR_UNIT);
			colorTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
			glBindTexture(GL_TEXTURE_2D, color); glBindSampler(COLOR_UNIT, 0);
			glActiveTexture(GL_TEXTURE0 + DEPTH_UNIT);
			depthTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
			glBindTexture(GL_TEXTURE_2D, depth); glBindSampler(DEPTH_UNIT, 0);
			glActiveTexture(active);
		}
		@Override public void close()
		{
			glActiveTexture(GL_TEXTURE0 + SCENE_DEPTH_UNIT);
			glBindTexture(GL_TEXTURE_2D, sceneTexture); glBindSampler(SCENE_DEPTH_UNIT, sceneSampler);
			glActiveTexture(GL_TEXTURE0 + COLOR_UNIT);
			glBindTexture(GL_TEXTURE_2D, colorTexture); glBindSampler(COLOR_UNIT, colorSampler);
			glActiveTexture(GL_TEXTURE0 + DEPTH_UNIT);
			glBindTexture(GL_TEXTURE_2D, depthTexture); glBindSampler(DEPTH_UNIT, depthSampler);
			glActiveTexture(active);
		}
	}

	private static final class CaptureState
	{
		final int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		final int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
		final boolean blend = glIsEnabled(GL_BLEND), depthTest = glIsEnabled(GL_DEPTH_TEST);
		final boolean scissor = glIsEnabled(GL_SCISSOR_TEST), depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
		final int depthFunc = glGetInteger(GL_DEPTH_FUNC);
		final double clearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE);
		final float[] clearColor = new float[4];
		final int[] colorMask = new int[4];
		CaptureState()
		{
			glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
			glGetIntegerv(GL_COLOR_WRITEMASK, colorMask);
		}
		void restore(int[] viewport)
		{
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
			glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
			if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
			if (depthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
			if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
			glDepthMask(depthMask); glDepthFunc(depthFunc);
			glColorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
			glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
			glClearDepth(clearDepth);
		}
	}
}
