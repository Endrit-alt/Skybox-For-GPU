package com.endrit.gpusky;

import com.endrit.gpusky.GpuSkyExtensionConfig.WeatherEffect;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

/**
 * The soft glow around the sun or moon in Sunlight/Moonlight weather, and the matching
 * edge light on models that stand in front of it. Both follow the beams: same light,
 * same colour, same fades, so a sky without a sun or moon shows neither.
 */
final class SunHalo
{
	/** Glow opacity at the light's centre, at 100% strength. */
	static final float TIGHT_PEAK = 0.24f;
	static final float WIDE_PEAK = 0.15f;
	/** Angular size (radians) of the tight glow and the wide haze. */
	static final float TIGHT_RADIUS = 0.24f;
	static final float WIDE_RADIUS = 0.75f;
	/** Share of the tight glow that the scene shader also adds to models (not terrain) in front of the light. */
	static final float BLEED = 0.35f;
	/**
	 * How much stronger the glow's colour is than the beams'. A screen blend swaps part of the
	 * sky's own colour for the glow's, so a pale glow washes the area out; a richer one keeps it vivid.
	 * It is a vibrance, not a flat saturation: a pale light (Sunlight's sun) gains a lot, an
	 * already strongly coloured one (Bloodfall's red) hardly anything.
	 */
	static final float VIBRANCE = 4.5f;
	static final float VIBRANCE_FALLOFF = 2.5f;
	/** Skies whose glow is weaker than full strength, by file name (without extension, lower case). */
	private static final String[] SOFT_SKIES = {"moonlight", "bloodfall"};
	static final float SOFT_SKY_STRENGTH = 0.30f;

	/** The light's middle is looked for this far around the detected point. */
	private static final float CENTRE_SEARCH_DEGREES = 8f;
	/** Pixels this far from the surroundings' typical brightness to the brightest one count as the light. */
	private static final float CENTRE_BRIGHTNESS = 0.6f;
	/** Disc search: edge points used, circles tried, outline sectors, and the share of them an accepted disc covers. */
	private static final int DISC_EDGE_POINTS = 800;
	private static final int DISC_ATTEMPTS = 1500;
	private static final int DISC_SECTORS = 36;
	private static final float DISC_MIN_COVERAGE = 0.45f;

	/**
	 * Drawn onto the sky (screen blend) with the sky pass' fullscreen quad, before the world.
	 * Each pixel takes the same sky ray as the sky pass, so the glow sits exactly on the sun
	 * or moon in the image.
	 */
	static final String FRAGMENT_SHADER = String.join("\n",
		"#version 330",
		"layout(std140) uniform uniforms {",
		"  mat4 worldProj;",
		"  vec3 cameraPos;",
		"  float cameraYaw;",
		"  float cameraPitch;",
		"};",
		"uniform float horizontalFov;",
		"uniform float aspect;",
		"uniform float yawOffset;",
		"uniform float pitchOffset;",
		"uniform vec3 haloDirection;",
		"uniform vec3 haloColor;",
		"uniform float haloStrength;",
		"in vec2 fragUv;",
		"out vec4 fragColor;",
		"vec3 skyDirection(vec2 screen) {",
		"  float hh = horizontalFov * 0.5;",
		"  float hv = atan(tan(hh) / max(aspect, 0.1));",
		"  vec3 ray = normalize(vec3(screen.x * tan(hh), screen.y * tan(hv), 1.0));",
		"  float ps = sin(cameraPitch + pitchOffset);",
		"  float pc = cos(cameraPitch + pitchOffset);",
		"  ray = vec3(ray.x, ray.y * pc - ray.z * ps, ray.y * ps + ray.z * pc);",
		"  float yaw = -cameraYaw + yawOffset;",
		"  float ys = sin(yaw);",
		"  float yc = cos(yaw);",
		"  ray = vec3(ray.x * yc + ray.z * ys, ray.y, ray.z * yc - ray.x * ys);",
		"  return ray;",
		"}",
		"void main() {",
		"  vec2 screen = vec2(fragUv.x * 2.0 - 1.0, (1.0 - fragUv.y) * 2.0 - 1.0);",
		"  float angle = acos(clamp(dot(skyDirection(screen), haloDirection), -1.0, 1.0));",
		"  float tight = angle / " + TIGHT_RADIUS + ";",
		"  float wide = angle / " + WIDE_RADIUS + ";",
		"  float glow = " + TIGHT_PEAK + " * exp(-tight * tight) + " + WIDE_PEAK + " * exp(-wide * wide);",
		"  // Tiny dither so the wide haze does not band on dark skies.",
		"  float dither = (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;",
		"  float alpha = glow * haloStrength;",
		"  alpha = clamp(alpha + dither * step(0.004, alpha), 0.0, 1.0);",
		"  // Multiplied by alpha for the screen blend.",
		"  fragColor = vec4(clamp(haloColor, 0.0, 1.0) * alpha, alpha);",
		"}");

	private SunHalo()
	{
	}

	/**
	 * Always on in Sunlight and Moonlight weather, 0 in any other: the beams' current opacity
	 * scaled by the sky's own strength.
	 */
	static float strength(WeatherEffect effect, float beamOpacity, float skyStrength)
	{
		if (effect != WeatherEffect.SUNLIGHT && effect != WeatherEffect.MOONLIGHT
			|| !(beamOpacity > 0f) || !(skyStrength > 0f))
		{
			return 0f;
		}
		return Math.min(1f, beamOpacity) * Math.min(1f, skyStrength);
	}

	/** Glow strength for a sky file (name or path): 30% on the Moonlight and Bloodfall skies, full on any other. */
	static float skyStrength(String file)
	{
		if (file == null)
		{
			return 1f;
		}
		String name = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
		int extension = name.lastIndexOf('.');
		if (extension > 0)
		{
			name = name.substring(0, extension);
		}
		for (String soft : SOFT_SKIES)
		{
			if (soft.equalsIgnoreCase(name.trim()))
			{
				return SOFT_SKY_STRENGTH;
			}
		}
		return 1f;
	}

	/**
	 * The beams' colour (Sunlight gets part of its yellow back, Moonlight uses the light as
	 * sampled), made more vivid.
	 */
	static float[] color(WeatherEffect effect, float red, float green, float blue)
	{
		return vivid(effect == WeatherEffect.SUNLIGHT
			? LightBeams.sunlightColor(red, green, blue)
			: new float[]{red, green, blue});
	}

	/** How many times further from the brightest channel the others go, for a colour's saturation (0-1). */
	static float vibranceGain(float saturation)
	{
		float pale = Math.max(0f, Math.min(1f, 1f - saturation));
		return 1f + VIBRANCE * (float) Math.pow(pale, VIBRANCE_FALLOFF);
	}

	/** Same hue and brightest channel, with the other channels pushed further from it; white stays white. */
	static float[] vivid(float[] color)
	{
		float max = Math.max(color[0], Math.max(color[1], color[2]));
		float min = Math.min(color[0], Math.min(color[1], color[2]));
		float gain = max <= 0f ? 1f : vibranceGain((max - min) / max);
		float[] vivid = new float[3];
		for (int i = 0; i < 3; i++)
		{
			vivid[i] = Math.max(0f, max - (max - color[i]) * gain);
		}
		return vivid;
	}

	/**
	 * Middle of the light around a detected point of an equirectangular sky image, as {u, v}
	 * fractions of the image. First the centre of the pixels that stand out from their
	 * surroundings (the visible part of a sun or moon). Then, when a round disc with a clear
	 * edge surrounds that point, the middle of that disc, also where clouds or the horizon
	 * hide part of it. The detected point itself when nothing stands out.
	 */
	static float[] lightCentre(BufferedImage image, float u, float imageV)
	{
		float[] centre = {u, imageV};
		// The second pass re-centres the search window, for a light near the first window's edge.
		for (int pass = 0; pass < 2; pass++)
		{
			centre = brightCentre(image, centre[0], centre[1]);
		}
		float[] disc = discCentre(image, centre[0], centre[1]);
		return disc != null ? disc : centre;
	}

	/**
	 * Middle of a bright round disc around a point, or null without one. Looks at the
	 * sharpest edges nearby whose brighter side faces inward, and takes the circle that such
	 * edges follow furthest around; it must cover enough of its outline and contain the point.
	 */
	static float[] discCentre(BufferedImage image, float u, float imageV)
	{
		int width = image.getWidth();
		int height = image.getHeight();
		float pixelsPerDegree = width / 360f;
		int centreX = (int) Math.floor(u * width);
		int centreY = Math.max(0, Math.min(height - 1, (int) (imageV * height)));
		int radiusX = Math.max(1, Math.round(CENTRE_SEARCH_DEGREES / 360f * width));
		int radiusY = Math.max(1, Math.round(CENTRE_SEARCH_DEGREES / 180f * height));
		int top = Math.max(0, centreY - radiusY);
		int bottom = Math.min(height - 1, centreY + radiusY);
		int columns = 2 * radiusX + 1;
		int rows = bottom - top + 1;
		int blur = Math.max(1, Math.round(pixelsPerDegree * 0.08f));
		int border = 3 * blur;
		if (columns <= 2 * border + 2 || rows <= 2 * border + 2)
		{
			return null;
		}
		float[] luminance = new float[columns * rows];
		int[] row = new int[columns];
		for (int y = 0; y < rows; y++)
		{
			readRow(image, centreX - radiusX, top + y, row);
			for (int x = 0; x < columns; x++)
			{
				int rgb = row[x];
				luminance[y * columns + x] = ((rgb >> 16) & 0xff) * 0.2126f + ((rgb >> 8) & 0xff) * 0.7152f + (rgb & 0xff) * 0.0722f;
			}
		}
		float[] smooth = boxBlur(luminance, columns, rows, blur);
		float[] gradientX = new float[columns * rows];
		float[] gradientY = new float[columns * rows];
		float[] strength = new float[columns * rows];
		int inside = 0;
		for (int y = border; y < rows - border; y++)
		{
			for (int x = border; x < columns - border; x++)
			{
				int i = y * columns + x;
				gradientX[i] = (smooth[i + 1] - smooth[i - 1]) * 0.5f;
				gradientY[i] = (smooth[i + columns] - smooth[i - columns]) * 0.5f;
				strength[i] = (float) Math.sqrt(gradientX[i] * gradientX[i] + gradientY[i] * gradientY[i]);
				inside++;
			}
		}
		// The sharpest 3% of the window are the edge candidates.
		float[] sorted = strength.clone();
		Arrays.sort(sorted);
		float edge = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.97f))];
		if (!(edge > 0f) || inside == 0)
		{
			return null;
		}
		int total = 0;
		for (float value : strength)
		{
			if (value >= edge)
			{
				total++;
			}
		}
		int step = Math.max(1, total / DISC_EDGE_POINTS);
		int count = (total + step - 1) / step;
		if (count < 3)
		{
			return null;
		}
		float[] pointX = new float[count];
		float[] pointY = new float[count];
		float[] normalX = new float[count];
		float[] normalY = new float[count];
		int seen = 0;
		int stored = 0;
		for (int i = 0; i < strength.length && stored < count; i++)
		{
			if (strength[i] >= edge && seen++ % step == 0)
			{
				pointX[stored] = i % columns;
				pointY[stored] = i / columns;
				normalX[stored] = gradientX[i] / strength[i];
				normalY[stored] = gradientY[i] / strength[i];
				stored++;
			}
		}
		count = stored;
		// Fixed seed: the same image always gives the same centre.
		Random random = new Random(1);
		float minRadius = 1.5f * pixelsPerDegree;
		float maxRadius = 6f * pixelsPerDegree;
		int bestCovered = 0;
		int bestInliers = 0;
		float bestX = 0f;
		float bestY = 0f;
		float bestRadius = 0f;
		boolean[] sectors = new boolean[DISC_SECTORS];
		for (int attempt = 0; attempt < DISC_ATTEMPTS && count >= 3; attempt++)
		{
			int a = random.nextInt(count);
			int b = random.nextInt(count);
			int c = random.nextInt(count);
			float x1 = pointX[a], y1 = pointY[a], x2 = pointX[b], y2 = pointY[b], x3 = pointX[c], y3 = pointY[c];
			float d = 2f * (x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2));
			if (Math.abs(d) < 0.000001f)
			{
				continue;
			}
			float s1 = x1 * x1 + y1 * y1, s2 = x2 * x2 + y2 * y2, s3 = x3 * x3 + y3 * y3;
			float circleX = (s1 * (y2 - y3) + s2 * (y3 - y1) + s3 * (y1 - y2)) / d;
			float circleY = (s1 * (x3 - x2) + s2 * (x1 - x3) + s3 * (x2 - x1)) / d;
			float radius = (float) Math.hypot(x1 - circleX, y1 - circleY);
			if (radius < minRadius || radius > maxRadius
				|| circleX < 0 || circleX >= columns || circleY < 0 || circleY >= rows
				// Around the light that was found, not some other round shape nearby.
				|| Math.hypot(circleX - radiusX, circleY - (centreY - top)) > radius)
			{
				continue;
			}
			float tolerance = Math.max(1.5f, 0.04f * radius);
			Arrays.fill(sectors, false);
			int inliers = 0;
			for (int i = 0; i < count; i++)
			{
				float dx = pointX[i] - circleX;
				float dy = pointY[i] - circleY;
				float distance = (float) Math.sqrt(dx * dx + dy * dy);
				// On the outline, and brighter toward the middle.
				if (Math.abs(distance - radius) < tolerance
					&& -(normalX[i] * dx + normalY[i] * dy) > 0.8f * distance)
				{
					inliers++;
					int sector = (int) ((Math.atan2(dy, dx) / (2 * Math.PI) + 0.5) * DISC_SECTORS);
					sectors[Math.max(0, Math.min(DISC_SECTORS - 1, sector))] = true;
				}
			}
			int covered = 0;
			for (boolean sector : sectors)
			{
				if (sector)
				{
					covered++;
				}
			}
			if (covered > bestCovered || covered == bestCovered && inliers > bestInliers)
			{
				bestCovered = covered;
				bestInliers = inliers;
				bestX = circleX;
				bestY = circleY;
				bestRadius = radius;
			}
		}
		// Enough of the outline to be a disc.
		if (bestCovered < DISC_MIN_COVERAGE * DISC_SECTORS)
		{
			return null;
		}
		float foundU = (centreX - radiusX + bestX + 0.5f) / width;
		float foundV = (top + bestY + 0.5f) / height;
		return new float[]{foundU - (float) Math.floor(foundU), foundV};
	}

	/** Mean over a (2 x radius + 1) square around each pixel; the edges repeat the outermost pixel. */
	private static float[] boxBlur(float[] values, int columns, int rows, int radius)
	{
		float[] horizontal = new float[values.length];
		float[] blurred = new float[values.length];
		for (int y = 0; y < rows; y++)
		{
			for (int x = 0; x < columns; x++)
			{
				float sum = 0f;
				for (int k = -radius; k <= radius; k++)
				{
					sum += values[y * columns + Math.max(0, Math.min(columns - 1, x + k))];
				}
				horizontal[y * columns + x] = sum / (2 * radius + 1);
			}
		}
		for (int y = 0; y < rows; y++)
		{
			for (int x = 0; x < columns; x++)
			{
				float sum = 0f;
				for (int k = -radius; k <= radius; k++)
				{
					sum += horizontal[Math.max(0, Math.min(rows - 1, y + k)) * columns + x];
				}
				blurred[y * columns + x] = sum / (2 * radius + 1);
			}
		}
		return blurred;
	}

	private static float[] brightCentre(BufferedImage image, float u, float imageV)
	{
		int width = image.getWidth();
		int height = image.getHeight();
		int centreX = (int) Math.floor(u * width);
		int centreY = Math.max(0, Math.min(height - 1, (int) (imageV * height)));
		int radiusX = Math.max(1, Math.round(CENTRE_SEARCH_DEGREES / 360f * width));
		int radiusY = Math.max(1, Math.round(CENTRE_SEARCH_DEGREES / 180f * height));
		int top = Math.max(0, centreY - radiusY);
		int bottom = Math.min(height - 1, centreY + radiusY);
		int columns = 2 * radiusX + 1;
		int[] row = new int[columns];
		int[] luminance = new int[columns * (bottom - top + 1)];
		int[] histogram = new int[256];
		int brightest = 0;
		int index = 0;
		for (int y = top; y <= bottom; y++)
		{
			readRow(image, centreX - radiusX, y, row);
			for (int rgb : row)
			{
				int value = Math.round(((rgb >> 16) & 0xff) * 0.2126f + ((rgb >> 8) & 0xff) * 0.7152f + (rgb & 0xff) * 0.0722f);
				luminance[index++] = value;
				histogram[value]++;
				brightest = Math.max(brightest, value);
			}
		}
		int median = 0;
		for (int seen = histogram[0]; seen * 2 < luminance.length; seen += histogram[median])
		{
			median++;
		}
		if (brightest <= median)
		{
			return new float[]{u, imageV};
		}
		float threshold = median + (brightest - median) * CENTRE_BRIGHTNESS;
		double sumX = 0;
		double sumY = 0;
		long count = 0;
		index = 0;
		for (int y = top; y <= bottom; y++)
		{
			for (int column = 0; column < columns; column++)
			{
				if (luminance[index++] >= threshold)
				{
					sumX += column - radiusX;
					sumY += y;
					count++;
				}
			}
		}
		float foundU = (float) ((centreX + sumX / count + 0.5) / width);
		float foundV = (float) ((sumY / count + 0.5) / height);
		return new float[]{foundU - (float) Math.floor(foundU), foundV};
	}

	/** One row of pixels starting at x, wrapping around the image's left and right edges. */
	private static void readRow(BufferedImage image, int x, int y, int[] row)
	{
		int width = image.getWidth();
		for (int i = 0; i < row.length; i++)
		{
			row[i] = image.getRGB(Math.floorMod(x + i, width), y);
		}
	}

	/** The glow's opacity at an angle from the light (the shader's curve, for tests). */
	static float glow(float angle, float strength)
	{
		float tight = angle / TIGHT_RADIUS;
		float wide = angle / WIDE_RADIUS;
		float glow = TIGHT_PEAK * (float) Math.exp(-tight * tight) + WIDE_PEAK * (float) Math.exp(-wide * wide);
		return Math.max(0f, Math.min(1f, glow * strength));
	}
}
