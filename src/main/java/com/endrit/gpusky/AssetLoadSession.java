package com.endrit.gpusky;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Loader-owned state is never reused by a later plugin startup. */
final class AssetLoadSession
{
	final AtomicBoolean refreshQueued = new AtomicBoolean();
	final SkyboxImageCache images = new SkyboxImageCache();
	final RandomWeatherSelector randomWeather = new RandomWeatherSelector();
	final Map<String, SkyboxAsset> preloaded = new LinkedHashMap<>();
	final Map<GpuSkyExtensionConfig.WeatherEffect, WeatherLoadState> weather =
		new EnumMap<>(GpuSkyExtensionConfig.WeatherEffect.class);
	/** Decoded weather sprite sets, shared by the layers and effects that use the same frames. */
	final WeatherRenderer.SpriteCache weatherSprites = new WeatherRenderer.SpriteCache();
	/** Bumped on every GPU context change; completed weather loads again for the new context. */
	final AtomicInteger weatherContext = new AtomicInteger();
	String skyboxSignature;
	String preloadSignature;
	String regionState;
	GpuSkyExtensionConfig.WeatherEffect publishedMainWeather;
	int skyboxContext = -1;
	volatile SkyboxUploadBatch uploads;
	/** Loading paused after an out-of-memory error, until a deliberate trigger clears it. */
	volatile boolean memoryBlocked;
	/** Loading stopped for this plugin start after an unexpected Error. */
	volatile boolean stopped;
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
