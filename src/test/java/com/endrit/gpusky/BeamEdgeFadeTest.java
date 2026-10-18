package com.endrit.gpusky;

import org.junit.Test;
import static org.junit.Assert.*;

public class BeamEdgeFadeTest
{
	@Test
	public void normalViewsKeepFullOpacityOnBothSides()
	{
		assertEquals(1f, WeatherRenderer.beamEdgeAlphaScale(1, 0, 0, 0, 1, 0, 0, 0, 100), 0f);
		assertEquals(1f, WeatherRenderer.beamEdgeAlphaScale(1, 0, 0, 0, 1, 0, 0, 0, -100), 0f);
		assertEquals(1f, WeatherRenderer.beamEdgeAlphaScale(1, 0, 0, 0, 1, 0, 0, 100, 30), 0f);
	}

	@Test
	public void edgeOnViewsFadeSmoothlyToZero()
	{
		float previous = 0f;
		for (int i = 0; i <= 30; i++)
		{
			float facing = i / 100f;
			float viewY = (float) Math.sqrt(1f - facing * facing);
			float alpha = WeatherRenderer.beamEdgeAlphaScale(1, 0, 0, 0, 1, 0, 0, viewY, facing);
			assertTrue(alpha >= previous && alpha <= 1f);
			previous = alpha;
			if (i <= 4)
			{
				assertEquals(0f, alpha, 0.0001f);
			}
			if (i == 12)
			{
				assertEquals(0.5f, alpha, 0.0001f);
			}
		}
		assertEquals(1f, previous, 0f);
	}

	@Test
	public void tiltedBeamPlaneAndVectorScaleAreRespected()
	{
		assertEquals(0f, WeatherRenderer.beamEdgeAlphaScale(1, 0, 0, 0, 1, 1, 0, 100, 100), 0f);
		assertEquals(1f, WeatherRenderer.beamEdgeAlphaScale(1, 0, 0, 0, 1, 1, 0, -100, 100), 0f);
		float alpha = WeatherRenderer.beamEdgeAlphaScale(1, 0, 0, 0, 1, 1, 0, 100, 125);
		assertTrue(alpha > 0f && alpha < 1f);
		assertEquals(alpha, WeatherRenderer.beamEdgeAlphaScale(2, 0, 0, 0, 3, 3, 0, 1000, 1250), 0.0001f);
	}

	@Test
	public void degenerateBeamOrCameraAtItsCenterIsHidden()
	{
		assertEquals(0f, WeatherRenderer.beamEdgeAlphaScale(1, 0, 0, 0, 1, 0, 0, 0, 0), 0f);
		assertEquals(0f, WeatherRenderer.beamEdgeAlphaScale(1, 0, 0, 1, 0, 0, 0, 0, 100), 0f);
	}
}
