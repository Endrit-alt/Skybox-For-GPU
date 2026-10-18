package com.endrit.gpusky;

import net.runelite.api.Scene;
import net.runelite.api.hooks.DrawCallbacks;
import org.junit.Test;
import static org.junit.Assert.*;

public class GpuRegistrationTest
{
	@Test
	public void preloadNoticeDistinguishesWaitingFromLoadingAndCompletion()
	{
		assertEquals(GpuSkyExtensionPlugin.PreloadStatus.IDLE, GpuSkyExtensionPlugin.preloadStatus(false, false));
		assertEquals(GpuSkyExtensionPlugin.PreloadStatus.WAITING_FOR_GPU, GpuSkyExtensionPlugin.preloadStatus(true, false));
		assertEquals(GpuSkyExtensionPlugin.PreloadStatus.LOADING, GpuSkyExtensionPlugin.preloadStatus(true, true));
		assertEquals(GpuSkyExtensionPlugin.PreloadStatus.IDLE, GpuSkyExtensionPlugin.preloadStatus(false, true));
	}

	private static final DrawCallbacks CALLBACKS = new DrawCallbacks()
	{
		@Override
		public void draw(int overlayColor)
		{
		}

		@Override
		public void swapScene(Scene scene)
		{
		}
	};

	@Test
	public void reenableAcceptsCallbacksWrappedByAnotherPlugin()
	{
		DrawCallbacks wrapper = new DrawCallbacks()
		{
			@Override
			public void draw(int overlayColor)
			{
				CALLBACKS.draw(overlayColor);
			}

			@Override
			public void swapScene(Scene scene)
			{
				CALLBACKS.swapScene(scene);
			}
		};
		assertTrue(GpuSkyExtensionPlugin.canRegisterWithGpu(true, true, wrapper));
	}

	@Test
	public void anotherGpuRendererDoesNotCountAsTheBuiltinGpu()
	{
		assertFalse(GpuSkyExtensionPlugin.canRegisterWithGpu(false, true, CALLBACKS));
	}

	@Test
	public void waitsForGpuInitialization()
	{
		assertFalse(GpuSkyExtensionPlugin.canRegisterWithGpu(true, false, CALLBACKS));
		assertFalse(GpuSkyExtensionPlugin.canRegisterWithGpu(true, true, null));
		assertTrue(GpuSkyExtensionPlugin.canRegisterWithGpu(true, true, CALLBACKS));
	}

	@Test
	public void disabledGpuDoesNotRegister()
	{
		assertFalse(GpuSkyExtensionPlugin.canRegisterWithGpu(false, false, null));
	}
}
