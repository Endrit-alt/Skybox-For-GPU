package com.endrit.gpusky.water;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic world-anchored events; no scene/model objects or per-frame allocations. */
final class BubbleEvents
{
	static final int MAX_EVENTS = 16;
	static final float LIFE_SECONDS = 3.5f;
	static final float PLAYBACK_SPEED = 1.3f;
	static final float DURATION_SECONDS = LIFE_SECONDS / PLAYBACK_SPEED;
	static final float MIN_PERIOD_SECONDS = DURATION_SECONDS + .05f;
	static final float BASE_DELAY_SECONDS = 8f;
	static final int SITE_CELL_TILES = 14;
	static final float MIN_SITE_DISTANCE_TILES = 12;
	static final float SMALL_WATER_DISTANCE_TILES = 10;
	static final float MIN_SCALE = .60f;
	static final Site[] NO_SITES = new Site[0];
	final float[] uniforms = new float[MAX_EVENTS * 4];
	final float[] scales = new float[MAX_EVENTS];
	private final float[] activeX = new float[MAX_EVENTS], activeY = new float[MAX_EVENTS];
	private final float[] activeScale = new float[MAX_EVENTS], activeSeed = new float[MAX_EVENTS];
	private final float[] activeGap = new float[MAX_EVENTS];
	private final double[] activeStart = new double[MAX_EVENTS];
	private int activeCount;
	private double previousTime = Double.NEGATIVE_INFINITY;
	private Site[] currentSites = NO_SITES;
	private Map<Long, Schedule> schedules = new HashMap<>();
	private static final Schedule[] NO_SCHEDULES = new Schedule[0];
	private Schedule[] currentSchedules = NO_SCHEDULES, attempts = NO_SCHEDULES;

	private static final class Schedule
	{
		Site site;
		long lastCycle = Long.MIN_VALUE, cycle;
		double lastPeriod = -1, eventStart;
		Schedule(Site site) { this.site = site; }
	}

	static final class Site
	{
		final float x, y, seed;
		final int gx, gy;
		// Water-safe positions and maximum ripple scale, built on the mask worker.
		final float[] candidates;
		final float cadenceBoost;
		Site(float x, float y, float seed)
		{
			this((int) Math.floor(x / SITE_CELL_TILES), (int) Math.floor(y / SITE_CELL_TILES), seed, new float[]{x, y, 1}, 1);
		}
		Site(int gx, int gy, float seed, float[] candidates)
		{
			this(gx, gy, seed, candidates, 1 + 2 * (1 - Math.min(1,
				(candidates.length / 3f) / (SITE_CELL_TILES * SITE_CELL_TILES * 4 * .6f))));
		}
		Site(int gx, int gy, float seed, float[] candidates, float cadenceBoost)
		{
			this.gx = gx; this.gy = gy; this.seed = seed; this.candidates = candidates;
			this.cadenceBoost = cadenceBoost;
			x = candidates[0]; y = candidates[1];
		}
		int candidate(long cycle)
		{
			return (int) (hash((int) (seed * 16777216), (int) cycle,
				(int) (cycle >>> 32) ^ 0x71a5) * (candidates.length / 3)) * 3;
		}
		float eventScale(long cycle)
		{
			return MIN_SCALE + (1 - MIN_SCALE) * hash((int) (seed * 16777216), (int) cycle,
				(int) (cycle >>> 32) ^ 0x37e9);
		}
		float gap() { return cadenceBoost >= 2 ? SMALL_WATER_DISTANCE_TILES : MIN_SITE_DISTANCE_TILES; }
		int candidate(long cycle, float scale)
		{
			int preferred = candidate(cycle);
			if (candidates[preferred + 2] >= scale) return preferred;
			int nearest = -1; float best = Float.POSITIVE_INFINITY;
			for (int i = 0; i < candidates.length; i += 3)
			{
				if (candidates[i + 2] < scale) continue;
				float dx = candidates[i] - candidates[preferred], dy = candidates[i + 1] - candidates[preferred + 1];
				float distance = dx * dx + dy * dy;
				if (distance < best) { best = distance; nearest = i; }
			}
			return nearest;
		}
	}

	static Site[] findSites(ByteBuffer pixels, int size, int resolution, int offset, int baseX, int baseY)
	{
		List<Site> result = new ArrayList<>();
		int minX = baseX - offset, minY = baseY - offset, tiles = size / resolution;
		// Complete cells retain the same candidate list across overlapping regions.
		for (int gy = Math.floorDiv(minY + SITE_CELL_TILES - 1, SITE_CELL_TILES);
			(gy + 1) * SITE_CELL_TILES <= minY + tiles; gy++)
		{
			for (int gx = Math.floorDiv(minX + SITE_CELL_TILES - 1, SITE_CELL_TILES);
				(gx + 1) * SITE_CELL_TILES <= minX + tiles; gx++)
			{
				float[] scratch = new float[SITE_CELL_TILES * SITE_CELL_TILES * 4 * 3];
				int count = 0;
				for (int sy = 0; sy < SITE_CELL_TILES * 2; sy++) for (int sx = 0; sx < SITE_CELL_TILES * 2; sx++)
				{
					float x = gx * SITE_CELL_TILES + sx * .5f;
					float y = gy * SITE_CELL_TILES + sy * .5f;
					int px = (int) Math.floor((x - minX) * resolution), py = (int) Math.floor((y - minY) * resolution);
					float clearance = ShoreDistanceField.unhalf(pixels.getShort((py * size + px) * 8));
					// Original contour fits within .95 tiles; keep raster uncertainty at banks.
					float scale = Math.min(1, (clearance - .12f) / .95f);
					if (scale < MIN_SCALE) continue;
					scratch[count++] = x; scratch[count++] = y; scratch[count++] = scale;
				}
				if (count == 0) continue;
				Site site = new Site(gx, gy, hash(gx, gy, 0), java.util.Arrays.copyOf(scratch, count));
				result.add(site);
			}
		}
		return result.toArray(NO_SITES);
	}

	static double period(float frequency) { return LIFE_SECONDS + BASE_DELAY_SECONDS / frequency; }

	static double period(float frequency, float cadenceBoost)
	{
		// Two successive doublings quarter the original sparse-water cycle. Blend
		// in across partial-water cells while keeping full ocean cells unchanged.
		float extraRate = 1 + 3 * Math.min(1, Math.max(0, cadenceBoost - 1));
		return Math.max(MIN_PERIOD_SECONDS, period(frequency * cadenceBoost) / extraRate);
	}

	private static double start(Site site, long cycle, double period)
	{
		return (cycle - site.seed) * period
			+ hash((int) (site.seed * 16777216), (int) cycle, (int) (cycle >>> 32)) * Math.max(0, period - LIFE_SECONDS);
	}

	private boolean spaced(float x, float y, float gap)
	{
		for (int i = 0; i < activeCount; i++)
		{
			float dx = x - activeX[i], dy = y - activeY[i];
			float pairGap = Math.max(gap, activeGap[i]);
			if (dx * dx + dy * dy < pairGap * pairGap) return false;
		}
		return true;
	}

	int update(Site[] sites, double time, float frequency, float focusX, float focusY)
	{
		if (frequency <= 0 || !Double.isFinite(time) || sites.length == 0) { clear(); return 0; }
		if (time < previousTime) clear();
		previousTime = time;
		if (sites != currentSites)
		{
			Map<Long, Schedule> replacement = new HashMap<>();
			for (Site site : sites)
			{
				long key = key(site.gx, site.gy);
				Schedule schedule = schedules.get(key);
				if (schedule == null) schedule = new Schedule(site);
				else schedule.site = site;
				replacement.put(key, schedule);
			}
			schedules = replacement; currentSites = sites;
			currentSchedules = schedules.values().toArray(NO_SCHEDULES);
			attempts = new Schedule[schedules.size()];
		}
		// Retain accepted events until their animation finishes. Neither a new
		// candidate nor a mask refresh can move or evict an ongoing animation.
		for (int i = activeCount - 1; i >= 0; i--)
		{
			float dx = activeX[i] - focusX, dy = activeY[i] - focusY;
			if (time - activeStart[i] < DURATION_SECONDS && dx * dx + dy * dy <= 32 * 32) continue;
			int last = --activeCount;
			activeX[i] = activeX[last]; activeY[i] = activeY[last];
			activeScale[i] = activeScale[last]; activeSeed[i] = activeSeed[last]; activeStart[i] = activeStart[last];
			activeGap[i] = activeGap[last];
		}
		int attemptCount = 0;
		for (Schedule schedule : currentSchedules)
		{
			Site site = schedule.site;
			double period = period(frequency, site.cadenceBoost);
			if (period != schedule.lastPeriod) { schedule.lastPeriod = period; schedule.lastCycle = Long.MIN_VALUE; }
			long cycle = (long) Math.floor(time / period + site.seed);
			if (cycle == schedule.lastCycle) continue;
			double eventStart = start(site, cycle, period), age = time - eventStart;
			if (age < 0) continue;
			schedule.lastCycle = cycle;
			if (age >= DURATION_SECONDS) continue;
			schedule.cycle = cycle; schedule.eventStart = eventStart;
			// Chronological, deterministic admission for new events in the same frame.
			int at = attemptCount++;
			while (at > 0 && later(attempts[at - 1], schedule)) { attempts[at] = attempts[at - 1]; at--; }
			attempts[at] = schedule;
		}
		for (int attempt = 0; attempt < attemptCount && activeCount < MAX_EVENTS; attempt++)
		{
			Schedule schedule = attempts[attempt]; Site site = schedule.site;
			long cycle = schedule.cycle;
			float scale = site.eventScale(cycle);
			int candidate = site.candidate(cycle, scale);
			if (candidate < 0) continue; // Never shrink the chosen size to fit a bank.
			float x = site.candidates[candidate], y = site.candidates[candidate + 1];
			float dx = x - focusX, dy = y - focusY, distance = dx * dx + dy * dy;
			if (distance > 32 * 32 || !spaced(x, y, site.gap())) continue;
			int slot = activeCount++;
			activeX[slot] = x; activeY[slot] = y; activeStart[slot] = schedule.eventStart;
			activeSeed[slot] = hash((int) (site.seed * 16777216), (int) cycle,
				(int) (cycle >>> 32) ^ 0x4b1d);
			activeScale[slot] = scale;
			activeGap[slot] = site.gap();
		}
		for (int i = 0; i < activeCount; i++)
		{
			uniforms[i * 4] = activeX[i]; uniforms[i * 4 + 1] = activeY[i];
			uniforms[i * 4 + 2] = (float) ((time - activeStart[i]) * PLAYBACK_SPEED);
			uniforms[i * 4 + 3] = activeSeed[i]; scales[i] = activeScale[i];
		}
		return activeCount;
	}

	private static boolean later(Schedule a, Schedule b)
	{
		return a.eventStart > b.eventStart || a.eventStart == b.eventStart
			&& (a.site.gy > b.site.gy || a.site.gy == b.site.gy && a.site.gx > b.site.gx);
	}

	void clear()
	{
		activeCount = 0; currentSites = NO_SITES; schedules.clear();
		currentSchedules = NO_SCHEDULES; attempts = NO_SCHEDULES;
		previousTime = Double.NEGATIVE_INFINITY;
	}

	private static long key(int x, int y) { return ((long) x << 32) ^ (y & 0xffffffffL); }

	private static float hash(int x, int y, int salt)
	{
		int value = x * 0x1f123bb5 ^ y * 0x5f356495 ^ salt * 0x68bc21eb;
		value ^= value >>> 16; value *= 0x7feb352d;
		value ^= value >>> 15; value *= 0x846ca68b;
		value ^= value >>> 16;
		return (value >>> 8) / 16777216f;
	}
}
