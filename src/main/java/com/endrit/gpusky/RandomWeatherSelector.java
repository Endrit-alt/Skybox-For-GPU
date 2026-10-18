package com.endrit.gpusky;

import com.endrit.gpusky.GpuSkyExtensionConfig.WeatherEffect;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** One loader session owns the random selection and its monotonic timer. */
final class RandomWeatherSelector
{
	private static final WeatherEffect[] CHOICES = Arrays.stream(WeatherEffect.values())
		.filter(effect -> effect != WeatherEffect.DISABLED && effect != WeatherEffect.RANDOM)
		.toArray(WeatherEffect[]::new);

	private final Random random;
	private WeatherEffect selected;
	private long selectedAt;

	RandomWeatherSelector()
	{
		this(new Random());
	}

	RandomWeatherSelector(Random random)
	{
		this.random = random;
	}

	WeatherEffect select(WeatherEffect configured, int minutes, long now)
	{
		if (configured != WeatherEffect.RANDOM)
		{
			selected = null;
			return configured;
		}
		long interval = TimeUnit.MINUTES.toNanos(Math.max(1, Math.min(1440, minutes)));
		if (selected == null || now - selectedAt >= interval)
		{
			int choice = random.nextInt(CHOICES.length - (selected == null ? 0 : 1));
			for (WeatherEffect effect : CHOICES)
			{
				if (effect != selected && choice-- == 0)
				{
					selected = effect;
					break;
				}
			}
			selectedAt = now;
		}
		return selected;
	}
}
