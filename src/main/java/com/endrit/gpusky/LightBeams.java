package com.endrit.gpusky;

/**
 * Where Sunlight/Moonlight beams come from, and how visible they are, for each sky state.
 * Only the weather beams use this; world lighting keeps its own (blended) direction.
 */
final class LightBeams
{
	/** Below this height the detected light is used as is: every bundled sun or moon sits at 3-11 degrees. */
	static final float OVERHEAD_FADE_START = (float) Math.toRadians(70);
	/** At or above this the detected "light" is a bright overhead area, not a sun or moon: no beams. */
	static final float OVERHEAD_HIDDEN = (float) Math.toRadians(80);

	/** Sunlight's classic yellow (its fixed beam colour before beams took the sky's colour). */
	static final float[] SUN_YELLOW = {1f, 0.84f, 0.38f};
	/** How much of that yellow a pale, warm light (a real sun) gets back. */
	static final float SUN_YELLOW_AMOUNT = 0.5f;

	/** The rays aim this far below the middle of the sun or moon (user choice: the middle read as too high). */
	static final float RAY_ORIGIN_DROP = (float) Math.toRadians(3);

	/** Sunlight and Moonlight count the Weather intensity setting double (user choice): 50% now gives what 100% gave. */
	static final float LIGHT_WEATHER_GAIN = 2f;

	private LightBeams()
	{
	}

	/** Particle density for a Weather intensity setting (0-200%): 0-2, or 0-4 for Sunlight/Moonlight. */
	static float weatherIntensity(boolean lightWeather, int intensityPercent)
	{
		float intensity = Math.max(0f, Math.min(2f, intensityPercent / 100f));
		return lightWeather ? intensity * LIGHT_WEATHER_GAIN : intensity;
	}

	/**
	 * A sky-image direction (x, y, z; y is minus the sine of the height above the horizon)
	 * moved straight down by an angle, keeping its compass direction. Stops at straight down.
	 */
	static float[] lowered(float x, float y, float z, float angle)
	{
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		float horizontal = (float) Math.sqrt(x * x + z * z);
		if (length < 0.0001f || horizontal < 0.0001f)
		{
			return new float[]{x, y, z};
		}
		double elevation = Math.max(-Math.PI / 2, Math.atan2(-y, horizontal) - angle);
		float newHorizontal = (float) Math.cos(elevation);
		return new float[]{
			x / horizontal * newHorizontal,
			-(float) Math.sin(elevation),
			z / horizontal * newHorizontal};
	}

	/**
	 * Sunlight beam colour from the sampled light. A sun's centre is nearly white in the
	 * image, so pale warm lights get part of Sunlight's yellow back. Neutral or cool lights
	 * (white or bluish clouds) and strongly coloured ones (a red glow) are left as sampled.
	 */
	static float[] sunlightColor(float red, float green, float blue)
	{
		float warm = clamp01((red - blue) / 0.15f);
		float pale = clamp01((Math.min(red, Math.min(green, blue)) - 0.4f) / 0.2f);
		float amount = SUN_YELLOW_AMOUNT * warm * pale;
		return new float[]{
			red + (SUN_YELLOW[0] - red) * amount,
			green + (SUN_YELLOW[1] - green) * amount,
			blue + (SUN_YELLOW[2] - blue) * amount};
	}

	private static float clamp01(float value)
	{
		return Math.max(0f, Math.min(1f, value));
	}

	/** Time to fade out at the old light, and again to fade in at the new one (half the sky crossfade). */
	static final long HALF_SWAP_NANOS = 500_000_000L;

	/**
	 * Which sky's light the beams are showing and how visible they are while it changes. The
	 * beams fade out at the light they show, switch to the newest sky's light only once hidden,
	 * then fade in. A sky change in the middle carries on from the current visibility at the
	 * light still shown, so neither the brightness nor the position jumps.
	 */
	static final class Source<T>
	{
		private T shown;
		private float level = 1f;
		private long last;

		void update(T target, long now, boolean instant)
		{
			if (shown == null || instant)
			{
				shown = target;
				level = 1f;
			}
			else
			{
				float step = Math.max(0f, (now - last) / (float) HALF_SWAP_NANOS);
				if (shown != target)
				{
					level -= step;
					if (level <= 0f)
					{
						shown = target;
						level = 0f;
					}
				}
				else
				{
					level = Math.min(1f, level + step);
				}
			}
			last = now;
		}

		/** The sky light the beams come from this frame. */
		T shown()
		{
			return shown;
		}

		float opacity()
		{
			return smoothstep(level);
		}

		void reset()
		{
			shown = null;
			level = 1f;
		}
	}

	/** Hides beams whose source is detected near straight up (no sun or moon found low in the sky). */
	static float overheadOpacity(float elevation)
	{
		return 1f - smoothstep((elevation - OVERHEAD_FADE_START) / (OVERHEAD_HIDDEN - OVERHEAD_FADE_START));
	}

	private static float smoothstep(float value)
	{
		float t = Math.max(0f, Math.min(1f, value));
		return t * t * (3f - 2f * t);
	}
}
