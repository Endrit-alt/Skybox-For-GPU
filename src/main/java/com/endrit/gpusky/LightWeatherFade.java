package com.endrit.gpusky;

import com.endrit.gpusky.GpuSkyExtensionConfig.WeatherEffect;

final class LightWeatherFade
{
	private static final long FADE_NANOS = 750_000_000L;
	private WeatherEffect previousEffect;
	private long fadeStart = Long.MIN_VALUE;

	float update(WeatherEffect effect, boolean sourceReady, long now)
	{
		if (effect != previousEffect)
		{
			reset();
			previousEffect = effect;
		}
		if (effect != WeatherEffect.SUNLIGHT && effect != WeatherEffect.MOONLIGHT)
		{
			return 1f;
		}
		if (!sourceReady)
		{
			fadeStart = Long.MIN_VALUE;
			return 0f;
		}
		if (fadeStart == Long.MIN_VALUE)
		{
			fadeStart = now;
		}
		float progress = Math.max(0f, Math.min(1f, (now - fadeStart) / (float) FADE_NANOS));
		return progress * progress * (3f - 2f * progress);
	}

	void reset()
	{
		previousEffect = null;
		fadeStart = Long.MIN_VALUE;
	}
}
