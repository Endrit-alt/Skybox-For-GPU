package com.endrit.gpusky.water;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scene capture/upload are client-thread only. The worker sees only a water bitmap. */
final class WaterShoreMask
{
	private static final Logger log = LoggerFactory.getLogger(WaterShoreMask.class);
	static final int TEXELS_PER_TILE = 12;
	static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	static final int SIZE = Constants.EXTENDED_SCENE_SIZE * TEXELS_PER_TILE;
	static final long BLEND_NANOS = 350_000_000L;
	private static final int TILES = Constants.EXTENDED_SCENE_SIZE;
	private final AtomicLong revision = new AtomicLong();
	private long requestedRevision = -1;
	private long pendingRevision;
	private long sceneGeneration, pendingGeneration;
	private long retryAfterNanos;
	private boolean snapshotUploaded;
	private boolean[] captureWater;
	private boolean[] captureKnown;
	private int textureId, previousTextureId, coverageTextureId;
	private ShoreSnapshot currentSnapshot, previousSnapshot, targetSnapshot, pendingSnapshot;
	private boolean previousValid;
	private long blendStarted;
	private final ByteBuffer coveragePixels = ByteBuffer.allocateDirect(TILES * TILES * 2);
	private Scene lastScene;
	private int lastBaseX, lastBaseY;
	private volatile boolean valid;
	private ExecutorService executor;
	private Future<Result> pending;
	private Worker worker;
	private BubbleEvents.Site[] sites = BubbleEvents.NO_SITES;

	void create()
	{
		destroy();
		int previousBinding = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		int previousBuffer = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
		textureId = createTexture(GL33C.GL_RGBA16F, SIZE, GL33C.GL_RGBA, GL33C.GL_HALF_FLOAT);
		previousTextureId = createTexture(GL33C.GL_RGBA16F, SIZE, GL33C.GL_RGBA, GL33C.GL_HALF_FLOAT);
		coverageTextureId = createTexture(GL33C.GL_RG8, TILES, GL33C.GL_RG, GL33C.GL_UNSIGNED_BYTE);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, previousBinding);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, previousBuffer);
		worker = new Worker();
		captureWater = new boolean[SIZE * SIZE];
		captureKnown = new boolean[TILES * TILES];
		executor = Executors.newSingleThreadExecutor(task ->
		{
			Thread thread = new Thread(task, "Water-shore-distance");
			thread.setDaemon(true);
			return thread;
		});
		requestedRevision = -1;
		retryAfterNanos = 0;
	}

	private static int createTexture(int format, int size, int channels, int type)
	{
		int texture = GL33C.glGenTextures();
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
		GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, format, size, size, 0, channels, type, (ByteBuffer) null);
		return texture;
	}

	void update(Scene scene, int baseX, int baseY)
	{
		if (textureId == 0 || scene == null)
		{
			clearHistory();
			return;
		}
		boolean captured = false;
		double captureMillis = 0;
		if (scene != lastScene || baseX != lastBaseX || baseY != lastBaseY)
		{
			lastScene = scene;
			lastBaseX = baseX;
			lastBaseY = baseY;
			snapshotUploaded = false;
			sceneGeneration++;
			retryAfterNanos = 0;
			markDirty();
			// Capture independently of the worker: remap already-visible foam on the
			// FIRST new-scene frame, even when an obsolete build is still running.
			captureMillis = capture(scene);
			captured = true;
			refreshCoverage();
			if (!valid) sites = BubbleEvents.NO_SITES;
		}
		// Coalesce same-scene notifications while the worker owns its bitmap.
		// The separate capture buffer above is safe even during an obsolete build.
		if (pending != null && !pending.isDone()) return;
		long currentRevision = revision.get();
		if (pending != null)
		{
			// Two textures suffice if publication waits until the previous handoff
			// finishes. The worker result remains untouched until it is consumed.
			if (blend() < 1f && !pending.isCancelled()) return;
			try
			{
				Result result = pending.get();
				boolean sameScene = pendingGeneration == sceneGeneration;
				boolean current = pendingRevision == currentRevision;
				if (sameScene && !current)
				{
					if (!captured) { captureMillis = capture(scene); captured = true; }
					// Loading/loaded notifications often describe the exact same water.
					// Accept that completed result instead of throwing it away and rebuilding.
					current = Arrays.equals(captureWater, worker.water)
						&& Arrays.equals(captureKnown, pendingSnapshot.known);
				}
				if (sameScene && current)
				{
					long start = System.nanoTime();
					previousSnapshot = currentSnapshot;
					currentSnapshot = targetSnapshot = pendingSnapshot;
					int spare = previousTextureId;
					previousTextureId = textureId;
					textureId = spare;
					upload(textureId, SIZE, GL33C.GL_RGBA, GL33C.GL_HALF_FLOAT, result.pixels);
					refreshCoverage();
					blendStarted = System.nanoTime();
					sites = result.sites;
					snapshotUploaded = true;
					requestedRevision = currentRevision;
					log.debug("Water mask: capture {} ms, worker {} ms, upload {} ms",
						result.captureMillis, result.workerMillis, (System.nanoTime() - start) / 1_000_000.0);
				}
			}
			catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			catch (ExecutionException | CancellationException e)
			{
				// Keep the last good same-scene texture/sites. Back off on persistent
				// failures instead of retrying every frame or losing the bank foam.
				requestedRevision = -1;
				retryAfterNanos = System.nanoTime() + 1_000_000_000L;
				log.warn("Water shoreline build failed; retaining the previous mask and retrying shortly", e);
			}
			finally { pending = null; }
		}
		if (requestedRevision != currentRevision && System.nanoTime() >= retryAfterNanos)
		{
			if (!captured) captureMillis = capture(scene);
			if (snapshotUploaded && Arrays.equals(captureWater, worker.water)
				&& Arrays.equals(captureKnown, currentSnapshot.known))
			{
				targetSnapshot = currentSnapshot;
				requestedRevision = currentRevision;
				return; // No CPU field rebuild, GL upload or visible foam reset.
			}
			boolean[] previous = worker.water;
			worker.water = captureWater;
			captureWater = previous;
			snapshotUploaded = false;
			requestedRevision = pendingRevision = currentRevision;
			pendingGeneration = sceneGeneration;
			pendingSnapshot = targetSnapshot;
			Worker owner = worker; // A replacement context must not share worker buffers.
			double elapsed = captureMillis;
			pending = executor.submit(() -> owner.build(baseX, baseY, elapsed));
		}
	}

	private double capture(Scene scene)
	{
		long start = System.nanoTime();
		rasterize(scene, captureWater, captureKnown);
		targetSnapshot = new ShoreSnapshot(captureWater, captureKnown, TILES, TEXELS_PER_TILE,
			lastBaseX, lastBaseY, scene.isInstance());
		return (System.nanoTime() - start) / 1_000_000.0;
	}

	private void refreshCoverage()
	{
		byte[] current = currentSnapshot == null ? new byte[TILES * TILES] : currentSnapshot.coverageIn(targetSnapshot);
		byte[] previous = previousSnapshot == null ? new byte[TILES * TILES] : previousSnapshot.coverageIn(targetSnapshot);
		valid = previousValid = false;
		coveragePixels.clear();
		for (int i = 0; i < current.length; i++)
		{
			coveragePixels.put(current[i]).put(previous[i]);
			valid |= current[i] != 0;
			previousValid |= previous[i] != 0;
		}
		valid |= previousValid;
		coveragePixels.flip();
		upload(coverageTextureId, TILES, GL33C.GL_RG, GL33C.GL_UNSIGNED_BYTE, coveragePixels);
	}

	private static void upload(int texture, int size, int channels, int type, ByteBuffer pixels)
	{
		int binding = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		int buffer = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
		int alignment = GL33C.glGetInteger(GL33C.GL_UNPACK_ALIGNMENT);
		int rowLength = GL33C.glGetInteger(GL33C.GL_UNPACK_ROW_LENGTH);
		int skipRows = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_ROWS);
		int skipPixels = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_PIXELS);
		int swapBytes = GL33C.glGetInteger(GL33C.GL_UNPACK_SWAP_BYTES);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SWAP_BYTES, 0);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
		GL33C.glTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, 0, 0, size, size, channels, type, pixels);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, binding);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, alignment);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, rowLength);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, skipRows);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, skipPixels);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SWAP_BYTES, swapBytes);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, buffer);
	}

	void bind(int textureUnit)
	{
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + textureUnit);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, valid ? textureId : 0);
	}
	void bindHistory(int previousUnit, int coverageUnit)
	{
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + previousUnit);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, previousValid ? previousTextureId : 0);
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + coverageUnit);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, coverageTextureId);
	}
	float blend()
	{
		if (blendStarted == 0) return 1f;
		float elapsed = Math.max(0f, Math.min(1f, (System.nanoTime() - blendStarted) / (float) BLEND_NANOS));
		return elapsed * elapsed * (3f - 2f * elapsed);
	}
	boolean hasPrevious() { return previousValid && blend() < 1f; }
	int offsetX(boolean previous) { ShoreSnapshot s = previous ? previousSnapshot : currentSnapshot; return s == null ? 0 : lastBaseX - s.baseX; }
	int offsetY(boolean previous) { ShoreSnapshot s = previous ? previousSnapshot : currentSnapshot; return s == null ? 0 : lastBaseY - s.baseY; }

	/** Called on logout/world hop; ordinary LOADING keeps reusable world-space data. */
	void clearHistory()
	{
		if (lastScene != null) { sceneGeneration++; markDirty(); }
		lastScene = null;
		currentSnapshot = previousSnapshot = targetSnapshot = null;
		valid = previousValid = snapshotUploaded = false;
		blendStarted = 0;
		sites = BubbleEvents.NO_SITES;
	}

	void markDirty()
	{
		revision.incrementAndGet();
		// Notifications alone never remove the visible shoreline.
	}
	boolean isValid() { return valid; }
	BubbleEvents.Site[] bubbleSites() { return valid ? sites : BubbleEvents.NO_SITES; }

	void destroy()
	{
		markDirty();
		if (pending != null) pending.cancel(true);
		if (executor != null) executor.shutdownNow();
		pending = null;
		executor = null;
		worker = null;
		captureWater = null;
		captureKnown = null;
		pendingSnapshot = null;
		snapshotUploaded = false;
		if (textureId != 0) GL33C.glDeleteTextures(textureId);
		if (previousTextureId != 0) GL33C.glDeleteTextures(previousTextureId);
		if (coverageTextureId != 0) GL33C.glDeleteTextures(coverageTextureId);
		textureId = previousTextureId = coverageTextureId = 0;
		clearHistory();
	}

	private static final class Worker
	{
		boolean[] water = new boolean[SIZE * SIZE];
		ShoreDistanceField field;
		Result build(int baseX, int baseY, double captureMillis)
		{
			long start = System.nanoTime();
			if (field == null) field = new ShoreDistanceField(SIZE, TEXELS_PER_TILE);
			ByteBuffer pixels = field.build(water);
			BubbleEvents.Site[] candidates = BubbleEvents.findSites(pixels, SIZE, TEXELS_PER_TILE, SCENE_OFFSET, baseX, baseY);
			return new Result(pixels, candidates, captureMillis, (System.nanoTime() - start) / 1_000_000.0);
		}
	}

	private static final class Result
	{
		final ByteBuffer pixels;
		final BubbleEvents.Site[] sites;
		final double captureMillis, workerMillis;
		Result(ByteBuffer pixels, BubbleEvents.Site[] sites, double captureMillis, double workerMillis)
		{
			this.pixels = pixels;
			this.sites = sites;
			this.captureMillis = captureMillis;
			this.workerMillis = workerMillis;
		}
	}

	private static void rasterize(Scene scene, boolean[] water, boolean[] known)
	{
		Arrays.fill(water, false);
		Arrays.fill(known, false);
		Tile[][][] tiles = scene.getExtendedTiles();
		int tileIndexOffset = 0;
		if (tiles == null)
		{
			tiles = scene.getTiles();
			tileIndexOffset = SCENE_OFFSET;
		}
		if (tiles == null)
		{
			return;
		}
		for (Tile[][] plane : tiles)
		{
			if (plane == null)
			{
				continue;
			}
			for (int x = 0; x < plane.length; x++)
			{
				Tile[] column = plane[x];
				if (column == null)
				{
					continue;
				}
				for (int y = 0; y < column.length; y++)
				{
					int tx = x + tileIndexOffset, ty = y + tileIndexOffset;
					if (column[y] != null && tx >= 0 && ty >= 0 && tx < TILES && ty < TILES)
						known[ty * TILES + tx] = true;
					rasterizeTileAndBridge(water, column[y], x + tileIndexOffset, y + tileIndexOffset);
				}
			}
		}
		return;
	}

	private static void rasterizeTileAndBridge(boolean[] water, Tile tile, int tileX, int tileY)
	{
		if (tile == null)
		{
			return;
		}
		rasterizeTile(water, tile, tileX, tileY);
		Tile bridge = tile.getBridge();
		if (bridge != null)
		{
			rasterizeTile(water, bridge, tileX, tileY);
		}
	}

	private static void rasterizeTile(boolean[] water, Tile tile, int tileX, int tileY)
	{
		if (tileX < 0 || tileY < 0 || tileX >= Constants.EXTENDED_SCENE_SIZE || tileY >= Constants.EXTENDED_SCENE_SIZE)
		{
			return;
		}

		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null && WaterSurfaceClassifier.isWaterTexture(paint.getTexture()))
		{
			fillTile(water, tileX, tileY);
		}

		SceneTileModel model = tile.getSceneTileModel();
		if (model == null || model.getTriangleTextureId() == null)
		{
			return;
		}
		int[] textures = model.getTriangleTextureId();
		int[] faceX = model.getFaceX();
		int[] faceY = model.getFaceY();
		int[] faceZ = model.getFaceZ();
		int[] vertexX = model.getVertexX();
		int[] vertexZ = model.getVertexZ();
		for (int face = 0; face < textures.length; face++)
		{
			if (!WaterSurfaceClassifier.isWaterTexture(textures[face]))
			{
				continue;
			}
			rasterizeTriangle(water,
				toMaskCoordinate(vertexX[faceX[face]]), toMaskCoordinate(vertexZ[faceX[face]]),
				toMaskCoordinate(vertexX[faceY[face]]), toMaskCoordinate(vertexZ[faceY[face]]),
				toMaskCoordinate(vertexX[faceZ[face]]), toMaskCoordinate(vertexZ[faceZ[face]]));
		}
	}

	private static int toMaskCoordinate(int localCoordinate)
	{
		return Math.round((localCoordinate / (float) Perspective.LOCAL_TILE_SIZE + SCENE_OFFSET) * TEXELS_PER_TILE);
	}

	private static void fillTile(boolean[] water, int tileX, int tileY)
	{
		int startX = tileX * TEXELS_PER_TILE;
		int startY = tileY * TEXELS_PER_TILE;
		for (int y = startY; y < startY + TEXELS_PER_TILE; y++)
		{
			Arrays.fill(water, y * SIZE + startX, y * SIZE + startX + TEXELS_PER_TILE, true);
		}
	}

	private static void rasterizeTriangle(boolean[] water, int ax, int ay, int bx, int by, int cx, int cy)
	{
		int minX = clamp(Math.min(ax, Math.min(bx, cx)), 0, SIZE - 1);
		int maxX = clamp(Math.max(ax, Math.max(bx, cx)), 0, SIZE - 1);
		int minY = clamp(Math.min(ay, Math.min(by, cy)), 0, SIZE - 1);
		int maxY = clamp(Math.max(ay, Math.max(by, cy)), 0, SIZE - 1);
		float area = edge(ax, ay, bx, by, cx, cy);
		if (Math.abs(area) < 0.001f)
		{
			return;
		}
		for (int y = minY; y <= maxY; y++)
		{
			for (int x = minX; x <= maxX; x++)
			{
				float px = x + 0.5f;
				float py = y + 0.5f;
				float e0 = edge(ax, ay, bx, by, px, py);
				float e1 = edge(bx, by, cx, cy, px, py);
				float e2 = edge(cx, cy, ax, ay, px, py);
				if ((e0 >= 0f && e1 >= 0f && e2 >= 0f) || (e0 <= 0f && e1 <= 0f && e2 <= 0f))
				{
					water[y * SIZE + x] = true;
				}
			}
		}
	}

	private static float edge(float ax, float ay, float bx, float by, float px, float py)
	{
		return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
	}


	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}
}
