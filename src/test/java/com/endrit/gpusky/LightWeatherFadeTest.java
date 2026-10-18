package com.endrit.gpusky;

import com.endrit.gpusky.GpuSkyExtensionConfig.WeatherEffect;
import org.junit.Test;
import static org.junit.Assert.*;

public class LightWeatherFadeTest
{
	@Test
	public void staysHiddenUntilReadyThenFadesBothLightEffects()
	{
		for (WeatherEffect effect : new WeatherEffect[]{WeatherEffect.SUNLIGHT, WeatherEffect.MOONLIGHT})
		{
			LightWeatherFade fade = new LightWeatherFade();
			assertEquals(0f, fade.update(effect, false, 0), 0f);
			assertEquals(0f, fade.update(effect, false, 2_000_000_000L), 0f);
			assertEquals(0f, fade.update(effect, true, 3_000_000_000L), 0f);
			assertEquals(0.5f, fade.update(effect, true, 3_375_000_000L), 0.001f);
			assertEquals(1f, fade.update(effect, true, 3_750_000_000L), 0f);
			assertEquals(1f, fade.update(effect, true, 5_000_000_000L), 0f);
		}
	}

	@Test
	public void anotherSkyTransitionHidesAndRestartsTheFade()
	{
		LightWeatherFade fade = new LightWeatherFade();
		fade.update(WeatherEffect.SUNLIGHT, true, 0);
		assertEquals(1f, fade.update(WeatherEffect.SUNLIGHT, true, 1_000_000_000L), 0f);
		assertEquals(0f, fade.update(WeatherEffect.SUNLIGHT, false, 1_100_000_000L), 0f);
		assertEquals(0f, fade.update(WeatherEffect.SUNLIGHT, true, 2_100_000_000L), 0f);
		assertEquals(0.5f, fade.update(WeatherEffect.SUNLIGHT, true, 2_475_000_000L), 0.001f);
	}

	@Test
	public void changingWeatherDisablingAndContextResetStartFresh()
	{
		LightWeatherFade fade = new LightWeatherFade();
		fade.update(WeatherEffect.MOONLIGHT, true, 0);
		assertEquals(1f, fade.update(WeatherEffect.MOONLIGHT, true, 1_000_000_000L), 0f);
		assertEquals(0f, fade.update(WeatherEffect.SUNLIGHT, true, 1_000_000_000L), 0f);
		assertEquals(1f, fade.update(WeatherEffect.SUNLIGHT, true, 2_000_000_000L), 0f);
		fade.update(null, false, 2_100_000_000L);
		assertEquals(0f, fade.update(WeatherEffect.SUNLIGHT, true, 2_200_000_000L), 0f);
		assertEquals(1f, fade.update(WeatherEffect.SUNLIGHT, true, 3_200_000_000L), 0f);
		fade.reset();
		assertEquals(0f, fade.update(WeatherEffect.SUNLIGHT, true, 3_300_000_000L), 0f);
	}

	@Test
	public void otherWeatherIsUnaffected()
	{
		LightWeatherFade fade = new LightWeatherFade();
		for (WeatherEffect effect : WeatherEffect.values())
		{
			if (effect != WeatherEffect.SUNLIGHT && effect != WeatherEffect.MOONLIGHT)
			{
				assertEquals(1f, fade.update(effect, false, 0), 0f);
			}
		}
	}
}
