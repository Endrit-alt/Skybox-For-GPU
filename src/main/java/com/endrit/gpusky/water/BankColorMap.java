package com.endrit.gpusky.water;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terrain color next to water, rasterized at RES texels per tile and extended
 * a few tiles INTO the water, for the swash foam's terrain-colored film.
 *
 * Mirrors WaterShoreMask's loading model: the client thread only copies the
 * land-triangle colors out of the scene (cheap); rasterizing, spreading and
 * smoothing run on a background thread. Until a new map is ready, the previous
 * one stays bound and is re-anchored by the base offset, so a region load
 * neither stalls a frame nor makes the film disappear.
 */
final class BankColorMap
{
	private static final Logger log = LoggerFactory.getLogger(BankColorMap.class);
	private static final int TILES = Constants.EXTENDED_SCENE_SIZE;
	private static final int OFFSET = WaterShoreMask.SCENE_OFFSET;
	static final int RES = 4;
	private static final int SIZE = TILES * RES;
	private static final int HIDDEN = 12345678;

	private int textureId;
	private ExecutorService executor;
	private Builder builder;
	private Capture capture;
	private Future<Capture> pending;
	private Scene lastScene;
	private int lastBaseX, lastBaseY;
	private double lastBrightness = -1;
	private boolean lastInstance;
	private boolean dirty = true;
	// The map currently in the texture.
	private boolean valid, builtInstance;
	private int builtBaseX, builtBaseY;
	private int currentBaseX, currentBaseY;
	private boolean currentInstance;

	void create()
	{
		destroy();
		int binding = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		textureId = GL33C.glGenTextures();
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, textureId);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
		GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, SIZE, SIZE, 0, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, binding);
		builder = new Builder();
		capture = new Capture();
		executor = Executors.newSingleThreadExecutor(task ->
		{
			Thread thread = new Thread(task, "Water-bank-colors");
			thread.setDaemon(true);
			return thread;
		});
		dirty = true;
		valid = false;
	}

	void markDirty() { dirty = true; }

	/** Valid only while the bound map belongs to the same kind of scene and still overlaps it. */
	boolean isValid()
	{
		return valid && builtInstance == currentInstance
			&& Math.abs(currentBaseX - builtBaseX) < TILES && Math.abs(currentBaseY - builtBaseY) < TILES;
	}

	/** Tile offset from the current scene base to the base the bound map was built for. */
	int offsetX() { return currentBaseX - builtBaseX; }
	int offsetY() { return currentBaseY - builtBaseY; }

	void update(Scene scene, int baseX, int baseY, double brightness)
	{
		if (textureId == 0 || scene == null) { valid = false; return; }
		currentBaseX = baseX; currentBaseY = baseY; currentInstance = scene.isInstance();
		if (pending != null && pending.isDone())
		{
			try
			{
				Capture done = pending.get();
				upload(builder.pixels);
				builtBaseX = done.baseX; builtBaseY = done.baseY; builtInstance = done.instance;
				valid = true;
			}
			catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			catch (ExecutionException | java.util.concurrent.CancellationException e)
			{
				log.warn("Water bank color build failed; keeping the previous map", e);
				dirty = true;
			}
			finally { pending = null; }
		}
		boolean changed = dirty || scene != lastScene || baseX != lastBaseX || baseY != lastBaseY
			|| currentInstance != lastInstance || Math.abs(brightness - lastBrightness) > 1e-6;
		// One build at a time; a change during a build is picked up when it finishes.
		if (!changed || pending != null) return;
		lastScene = scene; lastBaseX = baseX; lastBaseY = baseY; lastBrightness = brightness; lastInstance = currentInstance;
		dirty = false;
		capture.take(scene, baseX, baseY, brightness);
		Capture submitted = capture;
		Builder owner = builder;
		pending = executor.submit(() -> { owner.build(submitted); return submitted; });
	}

	void bind(int unit)
	{
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, isValid() ? textureId : 0);
	}

	void destroy()
	{
		if (pending != null) pending.cancel(true);
		if (executor != null) executor.shutdownNow();
		pending = null;
		executor = null;
		builder = null;
		capture = null;
		if (textureId != 0) GL33C.glDeleteTextures(textureId);
		textureId = 0;
		valid = false;
		lastScene = null;
		dirty = true;
	}

	private void upload(ByteBuffer pixels)
	{
		int binding = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		int buffer = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
		int alignment = GL33C.glGetInteger(GL33C.GL_UNPACK_ALIGNMENT);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, textureId);
		GL33C.glTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, binding);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, alignment);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, buffer);
	}

	/** Client-thread copy of every land surface next to water: plain data, no scene references. */
	private static final class Capture
	{
		int baseX, baseY;
		boolean instance;
		double brightness;
		// Paint quads: tx, ty, sw, se, ne, nw (HSL).
		int[] quads = new int[6 * 4096];
		int quadCount;
		// Triangles: 6 texel coordinates, 3 HSL colors.
		float[] triPos = new float[6 * 8192];
		int[] triColor = new int[3 * 8192];
		int triCount;

		void take(Scene scene, int baseX, int baseY, double brightness)
		{
			this.baseX = baseX; this.baseY = baseY; this.instance = scene.isInstance(); this.brightness = brightness;
			quadCount = 0; triCount = 0;
			Tile[][][] tiles = scene.getExtendedTiles();
			int indexOffset = 0;
			if (tiles == null) { tiles = scene.getTiles(); indexOffset = OFFSET; }
			if (tiles == null) return;
			boolean[] tileDone = new boolean[TILES * TILES];
			// Lowest plane first; the first plane with visible land color owns a tile.
			for (Tile[][] plane : tiles)
			{
				if (plane == null) continue;
				for (int x = 0; x < plane.length; x++)
				{
					Tile[] column = plane[x];
					if (column == null) continue;
					for (int y = 0; y < column.length; y++)
					{
						int tx = x + indexOffset, ty = y + indexOffset;
						if (column[y] == null || tx < 0 || ty < 0 || tx >= TILES || ty >= TILES) continue;
						if (tileDone[ty * TILES + tx]) continue;
						// Under a bridge the scene tile holds the DECK; the real ground is the
						// bridge slot. Never color the bank with the deck.
						Tile ground = column[y].getBridge();
						if (ground != null)
						{
							take(ground, tx, ty);
							tileDone[ty * TILES + tx] = true;
							continue;
						}
						tileDone[ty * TILES + tx] = take(column[y], tx, ty);
					}
				}
			}
		}

		private boolean take(Tile tile, int tx, int ty)
		{
			boolean any = false;
			SceneTilePaint paint = tile.getSceneTilePaint();
			if (paint != null && !WaterSurfaceClassifier.isWaterTexture(paint.getTexture())
				&& visible(paint.getSwColor()) && visible(paint.getSeColor())
				&& visible(paint.getNeColor()) && visible(paint.getNwColor()))
			{
				if ((quadCount + 1) * 6 > quads.length) quads = Arrays.copyOf(quads, quads.length * 2);
				int q = quadCount++ * 6;
				quads[q] = tx; quads[q + 1] = ty;
				quads[q + 2] = paint.getSwColor(); quads[q + 3] = paint.getSeColor();
				quads[q + 4] = paint.getNeColor(); quads[q + 5] = paint.getNwColor();
				any = true;
			}
			SceneTileModel model = tile.getSceneTileModel();
			if (model == null) return any;
			int[] ca = model.getTriangleColorA(), cb = model.getTriangleColorB(), cc = model.getTriangleColorC();
			int[] textures = model.getTriangleTextureId();
			int[] faceX = model.getFaceX(), faceY = model.getFaceY(), faceZ = model.getFaceZ();
			int[] vertexX = model.getVertexX(), vertexZ = model.getVertexZ();
			if (ca == null || cb == null || cc == null || faceX == null || vertexX == null || vertexZ == null) return any;
			for (int face = 0; face < ca.length; face++)
			{
				// Blended shoreline tiles: only the land triangles describe the bank.
				if (textures != null && face < textures.length && WaterSurfaceClassifier.isWaterTexture(textures[face])) continue;
				if (!visible(ca[face]) || !visible(cb[face]) || !visible(cc[face])) continue;
				if ((triCount + 1) * 6 > triPos.length)
				{
					triPos = Arrays.copyOf(triPos, triPos.length * 2);
					triColor = Arrays.copyOf(triColor, triColor.length * 2);
				}
				int p = triCount * 6, c = triCount * 3;
				triPos[p] = texel(vertexX[faceX[face]]); triPos[p + 1] = texel(vertexZ[faceX[face]]);
				triPos[p + 2] = texel(vertexX[faceY[face]]); triPos[p + 3] = texel(vertexZ[faceY[face]]);
				triPos[p + 4] = texel(vertexX[faceZ[face]]); triPos[p + 5] = texel(vertexZ[faceZ[face]]);
				triColor[c] = ca[face]; triColor[c + 1] = cb[face]; triColor[c + 2] = cc[face];
				triCount++;
				any = true;
			}
			return any;
		}

		private static boolean visible(int hsl) { return hsl != HIDDEN && hsl >= 0; }

		private static float texel(int local) { return (local / 128f + OFFSET) * RES; }
	}

	/** Background-thread rasterizer; owns all large working arrays and the upload buffer. */
	private static final class Builder
	{
		private static final int DILATE_TEXELS = 6 * RES;
		final ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
		private final float[] r = new float[SIZE * SIZE], g = new float[SIZE * SIZE], b = new float[SIZE * SIZE];
		private final boolean[] set = new boolean[SIZE * SIZE];
		private final int[] queue = new int[SIZE * SIZE];
		private final byte[] depth = new byte[SIZE * SIZE];
		private final float[] tr = new float[SIZE * SIZE], tg = new float[SIZE * SIZE], tb = new float[SIZE * SIZE];
		private final float[] weight = new float[SIZE * SIZE];
		private final float[] rgbA = new float[3], rgbB = new float[3], rgbC = new float[3], rgbD = new float[3];

		void build(Capture c)
		{
			Arrays.fill(set, false);
			for (int i = 0; i < c.quadCount; i++)
			{
				int q = i * 6;
				rgb(c.quads[q + 2], c.brightness, rgbA); rgb(c.quads[q + 3], c.brightness, rgbB);
				rgb(c.quads[q + 4], c.brightness, rgbC); rgb(c.quads[q + 5], c.brightness, rgbD);
				int tx = c.quads[q], ty = c.quads[q + 1];
				for (int y = 0; y < RES; y++) for (int x = 0; x < RES; x++)
				{
					float u = (x + 0.5f) / RES, v = (y + 0.5f) / RES;
					int idx = (ty * RES + y) * SIZE + tx * RES + x;
					r[idx] = bilinear(rgbA[0], rgbB[0], rgbC[0], rgbD[0], u, v);
					g[idx] = bilinear(rgbA[1], rgbB[1], rgbC[1], rgbD[1], u, v);
					b[idx] = bilinear(rgbA[2], rgbB[2], rgbC[2], rgbD[2], u, v);
					set[idx] = true;
				}
			}
			for (int i = 0; i < c.triCount; i++)
			{
				int p = i * 6, k = i * 3;
				rgb(c.triColor[k], c.brightness, rgbA); rgb(c.triColor[k + 1], c.brightness, rgbB); rgb(c.triColor[k + 2], c.brightness, rgbC);
				drawTriangle(c.triPos[p], c.triPos[p + 1], c.triPos[p + 2], c.triPos[p + 3], c.triPos[p + 4], c.triPos[p + 5]);
			}
			if (Thread.currentThread().isInterrupted()) return;
			dilate();
			smoothDilated();
			pixels.clear();
			for (int i = 0; i < SIZE * SIZE; i++)
				pixels.put(toByte(r[i])).put(toByte(g[i])).put(toByte(b[i])).put(set[i] ? (byte) 255 : 0);
			pixels.flip();
		}

		private static float bilinear(float sw, float se, float ne, float nw, float u, float v)
		{
			return (sw * (1 - u) + se * u) * (1 - v) + (nw * (1 - u) + ne * u) * v;
		}

		/** Barycentric (Gouraud) fill of rgbA/B/C at texel centers inside the triangle. */
		private void drawTriangle(float ax, float ay, float bx, float by, float cx, float cy)
		{
			float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
			if (Math.abs(area) < 1e-4f) return;
			int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
			int maxX = Math.min(SIZE - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
			int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
			int maxY = Math.min(SIZE - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
			for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++)
			{
				float px = x + 0.5f, py = y + 0.5f;
				float wa = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area;
				float wb = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area;
				float wc = 1 - wa - wb;
				// Small tolerance so texels on a shared edge are covered by either side.
				if (wa < -0.02f || wb < -0.02f || wc < -0.02f) continue;
				int i = y * SIZE + x;
				r[i] = wa * rgbA[0] + wb * rgbB[0] + wc * rgbC[0];
				g[i] = wa * rgbA[1] + wb * rgbB[1] + wc * rgbC[1];
				b[i] = wa * rgbA[2] + wb * rgbB[2] + wc * rgbC[2];
				set[i] = true;
			}
		}

		/**
		 * Ring-by-ring growth into the water: each new texel averages ALL of its
		 * already-colored neighbours, so the far strip settles on the local bank
		 * average instead of whichever single texel happened to be reached first.
		 */
		private void dilate()
		{
			int count = 0;
			for (int i = 0; i < set.length; i++)
			{
				depth[i] = 0;
				if (set[i]) queue[count++] = i;
			}
			Arrays.fill(tr, 0); Arrays.fill(tg, 0); Arrays.fill(tb, 0); Arrays.fill(weight, 0);
			int start = 0;
			for (int ring = 1; ring <= DILATE_TEXELS && start < count; ring++)
			{
				int end = count;
				for (int q = start; q < end; q++)
				{
					int i = queue[q];
					int x = i % SIZE, y = i / SIZE;
					for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
					{
						int nx = x + dx, ny = y + dy;
						if ((dx | dy) == 0 || nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE) continue;
						int j = ny * SIZE + nx;
						if (set[j]) continue;
						if (weight[j] == 0) queue[count++] = j;
						tr[j] += r[i]; tg[j] += g[i]; tb[j] += b[i]; weight[j] += 1;
					}
				}
				for (int q = end; q < count; q++)
				{
					int j = queue[q];
					r[j] = tr[j] / weight[j]; g[j] = tg[j] / weight[j]; b[j] = tb[j] / weight[j];
					set[j] = true;
					depth[j] = (byte) ring;
				}
				start = end;
			}
		}

		/**
		 * Blur ONLY the spread water texels, more strongly further from the bank;
		 * real land texels stay exact, so the seam keeps the terrain's true edge color.
		 */
		private void smoothDilated()
		{
			for (int pass = 0; pass < 4; pass++)
			{
				blurAxis(r, g, b, tr, tg, tb, 1);
				blurAxis(tr, tg, tb, r, g, b, SIZE);
			}
		}

		private void blurAxis(float[] sr, float[] sg, float[] sb, float[] dr, float[] dg, float[] db, int stride)
		{
			for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++)
			{
				int i = y * SIZE + x;
				int d = depth[i] & 0xff;
				if (!set[i] || d == 0)
				{
					dr[i] = sr[i]; dg[i] = sg[i]; db[i] = sb[i];
					continue;
				}
				// Near the bank, blend gently so the first texels stay close to the edge
				// color; further out (up to 1.5 tiles) widen enough to erase streaks.
				int reach = d <= 2 ? 0 : Math.min(6, (d - 1) / 2);
				if (reach == 0) { dr[i] = sr[i]; dg[i] = sg[i]; db[i] = sb[i]; continue; }
				float ar = 0, ag = 0, ab = 0, n = 0;
				for (int k = -reach; k <= reach; k++)
				{
					int cx = stride == 1 ? x + k : x, cy = stride == 1 ? y : y + k;
					if (cx < 0 || cy < 0 || cx >= SIZE || cy >= SIZE) continue;
					int j = cy * SIZE + cx;
					if (!set[j]) continue;
					ar += sr[j]; ag += sg[j]; ab += sb[j]; n++;
				}
				dr[i] = ar / n; dg[i] = ag / n; db[i] = ab / n;
			}
		}

		private static void rgb(int hsl, double brightness, float[] out)
		{
			hsl &= 0xffff;
			double hue = ((hsl >> 10) & 63) / 64.0 + 0.0078125;
			double sat = ((hsl >> 7) & 7) / 8.0 + 0.0625;
			double lum = (hsl & 127) / 128.0;
			double q = lum < 0.5 ? lum * (1 + sat) : lum + sat - lum * sat;
			double p = 2 * lum - q;
			out[0] = (float) Math.pow(hue2rgb(p, q, hue + 1 / 3.0), brightness);
			out[1] = (float) Math.pow(hue2rgb(p, q, hue), brightness);
			out[2] = (float) Math.pow(hue2rgb(p, q, hue - 1 / 3.0), brightness);
		}

		private static double hue2rgb(double p, double q, double t)
		{
			if (t < 0) t += 1;
			if (t > 1) t -= 1;
			if (t < 1 / 6.0) return p + (q - p) * 6 * t;
			if (t < 0.5) return q;
			if (t < 2 / 3.0) return p + (q - p) * (2 / 3.0 - t) * 6;
			return p;
		}

		private static byte toByte(float v)
		{
			return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
		}
	}
}
