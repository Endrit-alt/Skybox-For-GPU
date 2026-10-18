package com.endrit.gpusky;

import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.client.callback.RenderCallbackManager;

/** Existing drawable floor tiles; the extension API does not expose per-frame hidden roof IDs. */
final class TerrainFloorPresence
{
	static float[] build(Scene scene, int width, int height, RenderCallbackManager callbacks)
	{
		float[] present = new float[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
		Tile[][][] tiles = scene.getExtendedTiles();
		byte[][][] settings = scene.getExtendedTileSettings();
		if (tiles == null) return present;
		for (int level = 0; level < Math.min(4, tiles.length); level++)
		{
			if (tiles[level] == null) continue;
			for (int x = 0; x < Math.min(width - 1, tiles[level].length); x++)
			{
				if (tiles[level][x] == null) continue;
				for (int z = 0; z < Math.min(height - 1, tiles[level][x].length); z++)
				{
					boolean bridge = (setting(settings, 1, x, z) & Constants.TILE_FLAG_BRIDGE) != 0;
					int mapLevel = level + (bridge ? 1 : 0);
					boolean below = mapLevel <= 3 && (setting(settings, mapLevel, x, z) & Constants.TILE_FLAG_VIS_BELOW) != 0;
					if (!includedLevel(level, below, scene.getMinLevel())) continue;
					Tile tile = tiles[level][x][z];
					for (int n = 0; tile != null && n < 4; n++, tile = tile.getBridge())
					{
						int plane = tile.getRenderLevel();
						if (plane < 0 || plane >= 4 || !hasFaces(tile) || !callbacks.drawTile(scene, tile)) continue;
						present[(z * width + x) * 4 + plane] = 1;
					}
				}
			}
		}
		return present;
	}

	static boolean includedLevel(int level, boolean below, int minLevel)
	{
		return (below ? 0 : level) >= minLevel;
	}

	private static boolean hasFaces(Tile tile)
	{
		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null && paint.getNeColor() != 12345678) return true;
		SceneTileModel model = tile.getSceneTileModel();
		return model != null && hasVisibleFace(model.getTriangleColorA());
	}

	static boolean hasVisibleFace(int[] colors)
	{
		if (colors != null) for (int color : colors) if (color != 12345678) return true;
		return false;
	}

	private static int setting(byte[][][] settings, int plane, int x, int z)
	{
		return settings != null && plane >= 0 && plane < settings.length && settings[plane] != null
			&& x < settings[plane].length && settings[plane][x] != null && z < settings[plane][x].length
			? settings[plane][x][z] : 0;
	}
}
