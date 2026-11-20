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
	private int context;

	synchronized boolean begin(long now)
	{
		return begin(now, context);
	}

	/**
	 * {@code gpuContext} counts GPU context changes. A new context holds none of the uploaded
	 * textures, whose pixels were released after upload, so a completed effect loads again
	 * (promptly, without the failure backoff).
	 */
	synchronized boolean begin(long now, int gpuContext)
	{
		if (gpuContext != context)
		{
			context = gpuContext;
			complete = false;
			failures = 0;
		}
		if (inFlight || complete || failures > 0 && now - retryAt < 0)
		{
			return false;
		}
		inFlight = true;
		return true;
	}

	/** The upload found sprite sets lost with an old GPU context: not a failure, so load again at once. */
	synchronized void retryNow()
	{
		inFlight = false;
		complete = false;
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
