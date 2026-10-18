package com.endrit.gpusky;

import net.runelite.api.Constants;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherGroundHeightTest
{
	@Test
	public void bridgeUsesUpperHeightWithExtendedCoordinatesAndInterpolation()
	{
		int offset = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
		int[][][] heights = new int[4][offset + 4][offset + 4];
		byte[][][] flags = new byte[4][offset + 4][offset + 4];
		int x = offset + 1;
		int z = offset + 2;
		flags[1][x][z] = Constants.TILE_FLAG_BRIDGE;
		heights[1][x][z] = -200;
		heights[1][x + 1][z] = -240;
		heights[1][x][z + 1] = -280;
		heights[1][x + 1][z + 1] = -320;

		assertEquals(-270f, WeatherRenderer.groundHeight(heights, flags, offset, 0,
			128 + 32, 2 * 128 + 96), 0.001f);
		flags[1][x][z] = 0;
		assertEquals(0f, WeatherRenderer.groundHeight(heights, flags, offset, 0,
			128 + 32, 2 * 128 + 96), 0f);
	}

	@Test
	public void bridgeSupportsDirectCoordinatesAndDoesNotExceedTopPlane()
	{
		int[][][] heights = new int[4][2][2];
		byte[][][] flags = new byte[4][2][2];
		flags[1][0][0] = Constants.TILE_FLAG_BRIDGE;
		heights[2][0][0] = -400;
		heights[3][0][0] = -600;

		assertEquals(-400f, WeatherRenderer.groundHeight(heights, flags, 0, 1, 0, 0), 0f);
		assertEquals(-600f, WeatherRenderer.groundHeight(heights, flags, 0, 2, 0, 0), 0f);
		assertEquals(-600f, WeatherRenderer.groundHeight(heights, flags, 0, 3, 0, 0), 0f);
	}

	@Test
	public void unavailableFlagsKeepOriginalPlaneAndInvalidHeightsStayInvalid()
	{
		int[][][] heights = new int[4][2][2];
		heights[0][0][0] = -80;
		assertEquals(-80f, WeatherRenderer.groundHeight(heights, null, 0, 0, 0, 0), 0f);
		assertEquals(-80f, WeatherRenderer.groundHeight(heights, new byte[4][0][0], 0, 0, 0, 0), 0f);
		assertTrue(Float.isNaN(WeatherRenderer.groundHeight(heights, null, 0, 0, -1, 0)));
		assertTrue(Float.isNaN(WeatherRenderer.groundHeight(heights, null, 0, 0, 128, 0)));
		assertTrue(Float.isNaN(WeatherRenderer.groundHeight(heights, null, 0, 4, 0, 0)));
	}

	@Test
	public void bridgeAndVisibleBelowAreNotRoofFlags()
	{
		byte[][][] flags = new byte[4][2][2];
		flags[1][0][0] = Constants.TILE_FLAG_BRIDGE | Constants.TILE_FLAG_VIS_BELOW;
		assertFalse(WeatherRenderer.isUnderRoof(flags, 0, 64, 64));
		assertFalse(WeatherRenderer.isUnderRoof(flags, 1, 64, 64));
		flags[1][0][0] |= Constants.TILE_FLAG_UNDER_ROOF;
		assertTrue(WeatherRenderer.isUnderRoof(flags, 1, 64, 64));
	}
}
