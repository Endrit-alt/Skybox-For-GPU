package com.endrit.gpusky;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loader-owned state is never reused by a later plugin startup. */
final class AssetLoadSession
{
	final AtomicBoolean refreshQueued = new AtomicBoolean();
	final SkyboxImageCache images = new SkyboxImageCache();
	final RandomWeatherSelector randomWeather = new RandomWeatherSelector();
	final Map<String, SkyboxAsset> preloaded = new LinkedHashMap<>();
	final Map<GpuSkyExtensionConfig.WeatherEffect, WeatherLoadState> weather =
		new EnumMap<>(GpuSkyExtensionConfig.WeatherEffect.class);
	String skyboxSignature;
	String preloadSignature;
	String regionState;
	GpuSkyExtensionConfig.WeatherEffect publishedMainWeather;
	int skyboxContext = -1;
	volatile SkyboxUploadBatch uploads;
	private volatile boolean cancelled;

	void cancel()
	{
		cancelled = true;
		cancelUploads();
	}

	void cancelUploads()
	{
		SkyboxUploadBatch batch = uploads;
		if (batch != null)
		{
			batch.cancel();
		}
	}

	void useSkyboxContext(int generation)
	{
		if (skyboxContext != generation)
		{
			images.clear();
			preloaded.clear();
			preloadSignature = null;
			skyboxSignature = null;
			skyboxContext = generation;
		}
	}

	void checkActive()
	{
		if (cancelled || Thread.currentThread().isInterrupted())
		{
			throw new CancellationException("Skybox loading session ended");
		}
	}

	synchronized void publish(Runnable action)
	{
		if (!cancelled)
		{
			action.run();
		}
	}
}
