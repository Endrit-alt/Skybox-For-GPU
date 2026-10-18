package com.endrit.gpusky.water;

/** Immutable, exact per-tile water signatures for validating world-space mask reuse. */
final class ShoreSnapshot
{
	// Width chords reach up to 16 tiles, then gradient/smoothing taps add one tile.
	// Retained foam must not depend on changed/unavailable data in that neighborhood.
	static final int GUARD_TILES = 18;
	final int tiles, resolution, baseX, baseY;
	final boolean instance;
	final boolean[] known;
	private final int wordsPerTile;
	private final long[] water;

	ShoreSnapshot(boolean[] bitmap, boolean[] known, int tiles, int resolution, int baseX, int baseY, boolean instance)
	{
		this.tiles = tiles; this.resolution = resolution; this.baseX = baseX; this.baseY = baseY; this.instance = instance;
		this.known = known.clone();
		wordsPerTile = (resolution * resolution + 63) / 64;
		water = new long[tiles * tiles * wordsPerTile];
		int size = tiles * resolution;
		for (int ty = 0; ty < tiles; ty++) for (int tx = 0; tx < tiles; tx++)
		{
			int tile = (ty * tiles + tx) * wordsPerTile;
			for (int y = 0; y < resolution; y++) for (int x = 0; x < resolution; x++)
				if (bitmap[(ty * resolution + y) * size + tx * resolution + x])
				{
					int bit = y * resolution + x;
					water[tile + bit / 64] |= 1L << (bit % 64);
				}
		}
	}

	/** Coverage in target-scene tile coordinates; unknown/changed neighborhoods are excluded. */
	byte[] coverageIn(ShoreSnapshot target)
	{
		byte[] result = new byte[tiles * tiles];
		if (target == this)
		{
			for (int i = 0; i < result.length; i++) result[i] = known[i] ? (byte) 255 : 0;
			return result;
		}
		// Never between the open world and an instance (coordinates are unrelated).
		// Between two loads of the same kind, the exact per-tile water comparison
		// below decides; a mismatched offset simply finds no matching tiles.
		if (instance != target.instance || tiles != target.tiles || resolution != target.resolution) return result;
		int dx = target.baseX - baseX, dy = target.baseY - baseY;
		if (Math.abs((long) dx) >= tiles || Math.abs((long) dy) >= tiles) return result;
		int[] distance = new int[result.length];
		for (int y = 0; y < tiles; y++) for (int x = 0; x < tiles; x++)
		{
			int ox = x + dx, oy = y + dy, at = y * tiles + x;
			boolean same = ox >= 0 && oy >= 0 && ox < tiles && oy < tiles && target.known[at];
			int old = oy * tiles + ox;
			if (same) same = known[old];
			for (int w = 0; same && w < wordsPerTile; w++)
				same = water[old * wordsPerTile + w] == target.water[at * wordsPerTile + w];
			distance[at] = same ? Math.min(Math.min(x + 1, tiles - x), Math.min(y + 1, tiles - y)) : 0;
		}
		// Exact chessboard distance to changed/unknown tiles, including outside the map.
		for (int y = 0; y < tiles; y++) for (int x = 0; x < tiles; x++)
		{
			int i = y * tiles + x;
			if (x > 0) distance[i] = Math.min(distance[i], distance[i - 1] + 1);
			if (y > 0) for (int xx = Math.max(0, x - 1); xx <= Math.min(tiles - 1, x + 1); xx++)
				distance[i] = Math.min(distance[i], distance[(y - 1) * tiles + xx] + 1);
		}
		for (int y = tiles - 1; y >= 0; y--) for (int x = tiles - 1; x >= 0; x--)
		{
			int i = y * tiles + x;
			if (x + 1 < tiles) distance[i] = Math.min(distance[i], distance[i + 1] + 1);
			if (y + 1 < tiles) for (int xx = Math.max(0, x - 1); xx <= Math.min(tiles - 1, x + 1); xx++)
				distance[i] = Math.min(distance[i], distance[(y + 1) * tiles + xx] + 1);
			float fade = Math.max(0, Math.min(1, (distance[i] - GUARD_TILES) / 2f));
			result[i] = (byte) Math.round(255 * fade * fade * (3 - 2 * fade));
		}
		return result;
	}
}
