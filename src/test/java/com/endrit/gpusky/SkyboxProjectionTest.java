package com.endrit.gpusky;

import org.junit.Test;
import static org.junit.Assert.*;

public class SkyboxProjectionTest
{
	@Test
	public void preservesWideFramingFrom106PercentResizableScaling()
	{
		float legacyOutputHeight = 334f * 2.06f;
		float legacyFov = (float) (2 * Math.atan(Math.tan(Math.toRadians(110) / 2)
			* 0.26219955f * legacyOutputHeight / 512f));
		assertEquals(legacyFov, GpuSkyExtension.horizontalFov(512, 512, 110), 0.000001f);
		assertTrue(legacyFov > 0.47916923f);
	}

	@Test
	public void matchingLogicalViewportAndCameraScalingPreservesFov()
	{
		assertEquals(GpuSkyExtension.horizontalFov(512, 512, 110),
			GpuSkyExtension.horizontalFov(1024, 1024, 110), 0.000001f);
		assertTrue(GpuSkyExtension.horizontalFov(512, 1024, 110)
			< GpuSkyExtension.horizontalFov(512, 512, 110));
	}

	@Test
	public void tallerViewportWithSameVerticalGameFovPreservesSkyFraming()
	{
		for (int configuredFov : new int[] {70, 110, 150})
		{
			double baseline = verticalRayScale(512, 334, 512, configuredFov);
			// RuneLite's projection uses viewport dimensions divided by camera scale.
			// Keep height/scale fixed while making only the viewport taller.
			assertEquals(baseline, verticalRayScale(512, 668, 1024, configuredFov), 0.000001);
			assertEquals(baseline, verticalRayScale(512, 1336, 2048, configuredFov), 0.000001);
		}
	}

	@Test
	public void widerViewportDoesNotChangeVerticalSkyFraming()
	{
		assertEquals(verticalRayScale(512, 334, 512, 110),
			verticalRayScale(1024, 334, 512, 110), 0.000001);
	}

	@Test
	public void zeroSizedViewportAndCameraScaleStayFinite()
	{
		float fov = GpuSkyExtension.horizontalFov(0, 0, 110);
		assertTrue(Float.isFinite(fov));
		assertTrue(fov > 0f && fov < Math.PI);
		assertEquals(GpuSkyExtension.horizontalFov(1, 1, 110), fov, 0f);
	}

	private static double verticalRayScale(int width, int height, int cameraScale, int configuredFov)
	{
		double horizontalFov = GpuSkyExtension.horizontalFov(width, cameraScale, configuredFov);
		return Math.tan(horizontalFov * 0.5) / (width / (double) height);
	}
}
