package com.endrit.gpusky;

import com.endrit.gpusky.GpuSkyExtensionConfig.SkyboxImage;
import com.endrit.gpusky.GpuSkyExtensionConfig.WeatherEffect;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class RandomWeatherSelectorTest
{
	@Test
	public void dayIsDefaultAndMatchesDaylight()
	{
		GpuSkyExtensionConfig config = new GpuSkyExtensionConfig()
		{
		};
		assertEquals(WeatherEffect.DAY, config.weatherEffect());
		assertEquals(SkyboxImage.MATCH_WEATHER, config.skyboxImage());
		assertEquals("Daylight.jpg", config.skyboxImage().resolveFilename(config.weatherEffect()));
		assertEquals(WeatherEffect.SPRING.shaderGrade(), WeatherEffect.DAY.shaderGrade());
	}

	@Test
	public void fallMatchesDaylightAndPreservesExplicitFallPreset()
	{
		assertEquals("Daylight.jpg", SkyboxImage.MATCH_WEATHER.resolveFilename(WeatherEffect.FALL));
		assertEquals("Daylight.jpg", SkyboxImage.FALL.resolveFilename(WeatherEffect.DISABLED));
		assertEquals("Blossom.jpg", SkyboxImage.MATCH_WEATHER.resolveFilename(WeatherEffect.SPRING));
	}

	@Test
	public void keepsSelectionUntilTheMinuteIntervalExpires()
	{
		RandomWeatherSelector selector = new RandomWeatherSelector(new Random(1));
		WeatherEffect first = selector.select(WeatherEffect.RANDOM, 10, 0);
		assertEquals(first, selector.select(WeatherEffect.RANDOM, 10, TimeUnit.MINUTES.toNanos(10) - 1));
		assertNotEquals(first, selector.select(WeatherEffect.RANDOM, 10, TimeUnit.MINUTES.toNanos(10)));
	}

	@Test
	public void choicesIncludeDayAndFallButNeverDisabledRandomOrConsecutiveRepeats()
	{
		RandomWeatherSelector selector = new RandomWeatherSelector(new Random(2));
		Set<WeatherEffect> seen = EnumSet.noneOf(WeatherEffect.class);
		WeatherEffect previous = null;
		for (int minute = 0; minute < 200; minute++)
		{
			WeatherEffect selected = selector.select(WeatherEffect.RANDOM, 1, TimeUnit.MINUTES.toNanos(minute));
			assertNotEquals(WeatherEffect.DISABLED, selected);
			assertNotEquals(WeatherEffect.RANDOM, selected);
			assertNotEquals(previous, selected);
			assertNotNull(SkyboxImage.MATCH_WEATHER.resolveFilename(selected));
			if (selected == WeatherEffect.FALL || selected == WeatherEffect.DAY)
			{
				assertEquals("Daylight.jpg", SkyboxImage.MATCH_WEATHER.resolveFilename(selected));
			}
			seen.add(selected);
			previous = selected;
		}
		Set<WeatherEffect> expected = EnumSet.allOf(WeatherEffect.class);
		expected.remove(WeatherEffect.DISABLED);
		expected.remove(WeatherEffect.RANDOM);
		assertEquals(expected, seen);
	}

	@Test
	public void fixedWeatherBypassesRandomAndReenteringStartsANewTimer()
	{
		RandomWeatherSelector selector = new RandomWeatherSelector(new Random(3));
		selector.select(WeatherEffect.RANDOM, 10, 0);
		assertEquals(WeatherEffect.FALL, selector.select(WeatherEffect.FALL, 10, 1));
		assertEquals(WeatherEffect.DISABLED, selector.select(WeatherEffect.DISABLED, 10, 2));
		long restartedAt = TimeUnit.MINUTES.toNanos(9);
		WeatherEffect restarted = selector.select(WeatherEffect.RANDOM, 10, restartedAt);
		assertEquals(restarted, selector.select(WeatherEffect.RANDOM, 10, TimeUnit.MINUTES.toNanos(18)));
		assertNotEquals(restarted, selector.select(WeatherEffect.RANDOM, 10, TimeUnit.MINUTES.toNanos(19)));
	}

	@Test
	public void intervalChangesApplyWithoutRestartingAndInvalidValuesAreClamped()
	{
		RandomWeatherSelector selector = new RandomWeatherSelector(new Random(4));
		WeatherEffect first = selector.select(WeatherEffect.RANDOM, 10, 0);
		assertNotEquals(first, selector.select(WeatherEffect.RANDOM, 2, TimeUnit.MINUTES.toNanos(2)));
		WeatherEffect next = selector.select(WeatherEffect.RANDOM, 0, TimeUnit.MINUTES.toNanos(2));
		assertEquals(next, selector.select(WeatherEffect.RANDOM, 0, TimeUnit.MINUTES.toNanos(3) - 1));
		assertNotEquals(next, selector.select(WeatherEffect.RANDOM, 0, TimeUnit.MINUTES.toNanos(3)));
	}
}
