package com.endrit.gpusky;

import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherLoadStateTest
{
	@Test
	public void waitsForUploadAcknowledgementIncludingGpuUnavailable()
	{
		WeatherLoadState state = new WeatherLoadState();
		assertTrue(state.begin(0));
		assertFalse(state.begin(TimeUnit.MINUTES.toNanos(10)));
		state.finish(true, TimeUnit.MINUTES.toNanos(10));
		assertFalse(state.begin(TimeUnit.MINUTES.toNanos(11)));
	}

	@Test
	public void failuresBackOffAndEventuallyRecover()
	{
		WeatherLoadState state = new WeatherLoadState();
		long now = 0;
		for (int delay : new int[]{5, 10, 20, 40, 60, 60})
		{
			assertTrue(state.begin(now));
			state.finish(false, now);
			now += TimeUnit.SECONDS.toNanos(delay);
			assertFalse(state.begin(now - 1));
		}
		assertTrue(state.begin(now));
		state.finish(true, now);
		assertFalse(state.begin(now + TimeUnit.DAYS.toNanos(1)));
	}

	@Test
	public void failedContextReuploadCanRetryPreviouslySuccessfulEffect()
	{
		WeatherLoadState state = new WeatherLoadState();
		assertTrue(state.begin(0));
		state.finish(true, 0);
		state.finish(false, 100);
		assertTrue(state.begin(100 + TimeUnit.SECONDS.toNanos(5)));
	}
}
