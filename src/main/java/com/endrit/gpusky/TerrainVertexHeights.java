package com.endrit.gpusky;

import java.util.Arrays;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.Tile;

/** Sparse corrections for the interior vertices of shaped terrain tiles. */
final class TerrainVertexHeights
{
	static final int VALUE_WIDTH = 256;
	static final float MISSING = 1e20f;

	static Data build(Scene scene, int[][][] heights, int offset)
	{
		Builder builder = new Builder(heights);
		Tile[][][] tiles = scene.getExtendedTiles();
		if (tiles != null)
		{
			for (Tile[][] level : tiles)
			{
				if (level == null) continue;
				for (int x = 0; x < Math.min(builder.width - 1, level.length); x++)
				{
					if (level[x] == null) continue;
					for (int z = 0; z < Math.min(builder.height - 1, level[x].length); z++)
					{
						Tile tile = level[x][z];
						for (int n = 0; tile != null && n < 4; n++, tile = tile.getBridge())
						{
							SceneTileModel model = tile.getSceneTileModel();
							int plane = tile.getRenderLevel();
							if (model == null || plane < 0 || plane >= Math.min(4, heights.length)) continue;
							int[] vx = model.getVertexX(), vy = model.getVertexY(), vz = model.getVertexZ();
							if (vx == null || vy == null || vz == null) continue;
							for (int v = 0; v < Math.min(vx.length, Math.min(vy.length, vz.length)); v++)
								builder.add(plane, x, z, vx[v] - (x - offset) * 128, vz[v] - (z - offset) * 128, vy[v]);
						}
					}
				}
			}
		}
		return builder.finish();
	}

	static final class Data
	{
		final float[] offsets, values;
		final int rows, vertices;

		Data(float[] offsets, float[] values, int rows, int vertices)
		{
			this.offsets = offsets; this.values = values; this.rows = rows; this.vertices = vertices;
		}
	}

	static final class Builder
	{
		private final int[][][] heights;
		final int width, height;
		private final float[] offsets;
		private float[] values = new float[256];
		private int count, vertices;

		Builder(int[][][] heights)
		{
			this.heights = heights;
			width = heights[0].length; height = heights[0][0].length;
			offsets = new float[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
		}

		void add(int plane, int x, int z, int localX, int localZ, int actual)
		{
			// Corners/edge midpoints already agree within the one-unit rounding tolerance.
			if (localX <= 0 || localX >= 128 || localZ <= 0 || localZ >= 128
				|| localX % 32 != 0 || localZ % 32 != 0) return;
			float fx = localX / 128f, fz = localZ / 128f;
			int[][] h = heights[plane];
			float south = h[x][z] * (1f - fx) + h[x + 1][z] * fx;
			float north = h[x][z + 1] * (1f - fx) + h[x + 1][z + 1] * fx;
			if (Math.abs(south * (1f - fz) + north * fz - actual) < .01f) return;
			int cell = (z * width + x) * 4 + plane;
			int start = (int) offsets[cell] - 1;
			if (start < 0)
			{
				start = count; count += 9;
				if (count > values.length) values = Arrays.copyOf(values, Math.max(count, values.length * 2));
				Arrays.fill(values, start, count, MISSING);
				offsets[cell] = start + 1;
			}
			int index = start + (localZ / 32 - 1) * 3 + localX / 32 - 1;
			if (values[index] == MISSING) vertices++;
			values[index] = actual;
		}

		Data finish()
		{
			int rows = Math.max(1, (count + VALUE_WIDTH - 1) / VALUE_WIDTH);
			// Use the extension's existing owned RGBA float-texture API; red stores height.
			float[] packed = new float[rows * VALUE_WIDTH * 4];
			for (int i = 0; i < count; i++) packed[i * 4] = values[i];
			return new Data(offsets, packed, rows, vertices);
		}
	}
}
