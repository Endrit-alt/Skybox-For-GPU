package com.endrit.gpusky.water;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Pure CPU shoreline processing. Owned by one worker; scratch arrays are reused. */
final class ShoreDistanceField
{
	static final float MAX_DISTANCE = 8f;
	private final int size;
	private final int resolution;
	private final float[] signed;
	private final float[] scratch;
	private final float[] work;
	private final float[] line;
	private final float[] transformed;
	private final int[] sites;
	private final double[] breaks;
	private ByteBuffer pixels;

	ShoreDistanceField(int size, int resolution)
	{
		this.size = size;
		this.resolution = resolution;
		signed = new float[size * size];
		scratch = new float[signed.length];
		work = new float[signed.length];
		line = new float[size];
		transformed = new float[size];
		sites = new int[size];
		breaks = new double[size + 1];
	}

	ByteBuffer build(boolean[] water)
	{
		if (water.length != signed.length) throw new IllegalArgumentException("Incorrect mask size");
		if (pixels == null) pixels = ByteBuffer.allocateDirect(signed.length * 8).order(ByteOrder.nativeOrder());
		distance(water, false, signed);
		for (int i = 0; i < signed.length; i++)
		{
			signed[i] = water[i] ? Math.min(MAX_DISTANCE, ((float) Math.sqrt(signed[i]) - 0.5f) / resolution) : 0;
		}
		distance(water, true, work);
		for (int i = 0; i < signed.length; i++)
		{
			if (!water[i]) signed[i] = -Math.min(MAX_DISTANCE, ((float) Math.sqrt(work[i]) - 0.5f) / resolution);
			pixels.putShort(i * 8, half(signed[i]));
		}
		// Signed land distances avoid the old padded-land -> deep-water discontinuity.
		blurSigned(Math.max(1, resolution / 3), 1);
		blurSigned(resolution, 2);
		channelWidths(water);
		for (int i = 0; i < signed.length; i++) pixels.putShort(i * 8 + 6, half(work[i]));
		pixels.position(0);
		pixels.limit(pixels.capacity());
		return pixels;
	}

	private void distance(boolean[] water, boolean seed, float[] output)
	{
		// Separable lower envelope of squared-distance parabolas (Felzenszwalb/Huttenlocher).
		for (int y = 0; y < size; y++)
		{
			checkInterrupted();
			for (int x = 0; x < size; x++) line[x] = water[y * size + x] == seed ? 0 : 1e10f;
			transformLine();
			System.arraycopy(transformed, 0, scratch, y * size, size);
		}
		for (int x = 0; x < size; x++)
		{
			checkInterrupted();
			for (int y = 0; y < size; y++) line[y] = scratch[y * size + x];
			transformLine();
			for (int y = 0; y < size; y++) output[y * size + x] = transformed[y];
		}
	}

	private void transformLine()
	{
		int k = 0;
		sites[0] = 0;
		breaks[0] = Double.NEGATIVE_INFINITY;
		breaks[1] = Double.POSITIVE_INFINITY;
		for (int q = 1; q < size; q++)
		{
			double intersection;
			do
			{
				int v = sites[k];
				intersection = ((double) line[q] + (double) q * q - line[v] - (double) v * v) / (2.0 * (q - v));
				if (intersection > breaks[k]) break;
				k--;
			}
			while (k >= 0);
			sites[++k] = q;
			breaks[k] = intersection;
			breaks[k + 1] = Double.POSITIVE_INFINITY;
		}
		k = 0;
		for (int q = 0; q < size; q++)
		{
			while (breaks[k + 1] < q) k++;
			float d = q - sites[k];
			transformed[q] = d * d + line[sites[k]];
		}
	}

	private void blurSigned(int radius, int channel)
	{
		// Two separable box passes: continuous, symmetric, O(pixels), no per-fragment taps.
		int count = radius * 2 + 1;
		for (int y = 0; y < size; y++)
		{
			checkInterrupted();
			int row = y * size;
			float sum = 0;
			for (int x = -radius; x <= radius; x++) sum += signed[row + clamp(x)];
			for (int x = 0; x < size; x++)
			{
				scratch[row + x] = sum / count;
				sum += signed[row + clamp(x + radius + 1)] - signed[row + clamp(x - radius)];
			}
		}
		for (int x = 0; x < size; x++)
		{
			checkInterrupted();
			float sum = 0;
			for (int y = -radius; y <= radius; y++) sum += scratch[clamp(y) * size + x];
			for (int y = 0; y < size; y++)
			{
				pixels.putShort((y * size + x) * 8 + channel * 2, half(sum / count));
				sum += scratch[clamp(y + radius + 1) * size + x] - scratch[clamp(y - radius) * size + x];
			}
		}
	}

	private void channelWidths(boolean[] water)
	{
		Arrays.fill(work, MAX_DISTANCE);
		// Each chord stops at the first land pixel. Nearby disconnected ponds cannot contribute.
		for (int i = 0; i < size; i++)
		{
			checkInterrupted();
			chords(water, 0, i, 1, 0);
			chords(water, i, 0, 0, 1);
			chords(water, i, 0, 1, 1);
			chords(water, i, 0, -1, 1);
			if (i > 0)
			{
				chords(water, 0, i, 1, 1);
				chords(water, size - 1, i, -1, 1);
			}
		}
		// Smooth over a meaningful shoreline span, rather than a few mask texels.
		// Each sliding window stays inside a contiguous water run: no bank crossing,
		// no mixing disconnected ponds, and constant-width rivers remain unchanged.
		int radius = Math.max(1, resolution / 2);
		for (int pass = 0; pass < 2; pass++)
		{
			smoothWidthRuns(water, work, scratch, radius, false);
			smoothWidthRuns(water, scratch, work, radius, true);
		}
	}

	private void smoothWidthRuns(boolean[] water, float[] input, float[] output, int radius, boolean vertical)
	{
		int step = vertical ? size : 1;
		for (int row = 0; row < size; row++)
		{
			checkInterrupted();
			int base = vertical ? row : row * size;
			int x = 0;
			while (x < size)
			{
				if (!water[base + x * step]) { output[base + x * step] = 0; x++; continue; }
				int start = x;
				while (x < size && water[base + x * step]) x++;
				int end = x;
				float sum = 0;
				for (int j = start; j < Math.min(end, start + radius + 1); j++) sum += input[base + j * step];
				for (int j = start; j < end; j++)
				{
					int count = Math.min(end, j + radius + 1) - Math.max(start, j - radius);
					int at = base + j * step;
					output[at] = Math.max(signed[at], Math.min(MAX_DISTANCE, sum / count));
					if (j - radius >= start) sum -= input[base + (j - radius) * step];
					if (j + radius + 1 < end) sum += input[base + (j + radius + 1) * step];
				}
			}
		}
	}

	private void chords(boolean[] water, int x, int y, int dx, int dy)
	{
		float step = dx != 0 && dy != 0 ? 1.41421356f : 1f;
		boolean boundedStart = false;
		while (x >= 0 && y >= 0 && x < size && y < size)
		{
			if (!water[y * size + x]) { boundedStart = true; x += dx; y += dy; continue; }
			int sx = x, sy = y, length = 0;
			while (x >= 0 && y >= 0 && x < size && y < size && water[y * size + x])
			{
				length++; x += dx; y += dy;
			}
			boolean boundedEnd = x >= 0 && y >= 0 && x < size && y < size;
			float halfWidth = MAX_DISTANCE;
			if (boundedStart && boundedEnd)
			{
				float chordWidth = Math.min(MAX_DISTANCE, length * step / (2f * resolution));
				float facing = oppositeBankWeight(sx, sy, x - dx, y - dy);
				halfWidth += (chordWidth - MAX_DISTANCE) * facing;
			}
			for (int j = 0; j < length; j++)
			{
				int i = (sy + j * dy) * size + sx + j * dx;
				work[i] = Math.min(work[i], halfWidth);
			}
		}
	}

	private float oppositeBankWeight(int ax, int ay, int bx, int by)
	{
		// A short chord may skim a dent in ONE coastline. It is not a river width:
		// counting it switches off contact foam in triangular patches at the bank.
		// True opposite banks have inward distance gradients facing each other.
		int radius = Math.max(1, resolution / 3);
		float anx = signed[ay * size + clamp(ax + radius)] - signed[ay * size + clamp(ax - radius)];
		float any = signed[clamp(ay + radius) * size + ax] - signed[clamp(ay - radius) * size + ax];
		float bnx = signed[by * size + clamp(bx + radius)] - signed[by * size + clamp(bx - radius)];
		float bny = signed[clamp(by + radius) * size + bx] - signed[clamp(by - radius) * size + bx];
		float magnitude = (float) Math.sqrt((anx * anx + any * any) * (bnx * bnx + bny * bny));
		// Degenerate sub-texel slivers cannot provide stable normals; keep them narrow.
		if (magnitude < 0.000001f) return 1;
		// Fade the width constraint in rather than introducing a new hard cutoff
		// where two curved banks change from perpendicular to opposing directions.
		float facing = Math.max(0, Math.min(1, -2 * (anx * bnx + any * bny) / magnitude));
		return facing * facing * (3 - 2 * facing);
	}

	private int clamp(int i) { return Math.max(0, Math.min(size - 1, i)); }
	private static void checkInterrupted() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }

	static short half(float value)
	{
		// All field values are finite and bounded by 8 tiles; IEEE half precision is ample.
		int bits = Float.floatToIntBits(value);
		int sign = (bits >>> 16) & 0x8000;
		int exp = ((bits >>> 23) & 255) - 127 + 15;
		int mantissa = bits & 0x7fffff;
		if (exp <= 0) return (short) sign;
		int rounded = (mantissa + 0x1000) >> 13;
		if (rounded == 1024) { exp++; rounded = 0; }
		return (short) (sign | (exp << 10) | rounded);
	}

	static float unhalf(short value)
	{
		int h = value & 0xffff;
		int exp = (h >>> 10) & 31;
		if (exp == 0) return 0;
		return Float.intBitsToFloat(((h & 0x8000) << 16) | ((exp + 112) << 23) | ((h & 1023) << 13));
	}
}
