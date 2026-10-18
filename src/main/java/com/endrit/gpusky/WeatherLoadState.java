package com.endrit.gpusky;

import java.util.concurrent.TimeUnit;

/** One in-flight attempt per effect, with bounded backoff after decode or upload failures. */
final class WeatherLoadState
{
	WeatherRenderer.LoadedWeatherAssets assets;
	private boolean inFlight;
	private boolean complete;
	private int failures;
	private long retryAt;

	synchronized boolean begin(long now)
	{
		if (inFlight || complete || failures > 0 && now - retryAt < 0)
		{
			return false;
		}
		inFlight = true;
		return true;
	}

	synchronized void finish(boolean success, long now)
	{
		inFlight = false;
		complete = success;
		if (success)
		{
			failures = 0;
		}
		else
		{
			failures = Math.min(failures + 1, 5);
			retryAt = now + TimeUnit.SECONDS.toNanos(Math.min(60, 5L << (failures - 1)));
		}
	}
}
