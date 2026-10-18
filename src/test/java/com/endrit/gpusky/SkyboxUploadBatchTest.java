package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkyboxUploadBatchTest
{
	@Test(timeout = 10000)
	public void oneClientBatchAcknowledgesEachImageBeforeNextDecode() throws Exception
	{
		SkyboxUploadBatch batch = new SkyboxUploadBatch();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		AtomicInteger uploads = new AtomicInteger();
		AtomicInteger published = new AtomicInteger();
		try
		{
			Future<?> producer = worker.submit(() ->
			{
				try
				{
					for (int i = 0; i < 10; i++)
					{
						assertEquals("Previous upload must finish before another decode", i, uploads.get());
						BufferedImage image = image();
						batch.upload(new SkyboxAsset(image), image);
						batch.execute(published::incrementAndGet);
					}
				}
				catch (IOException ex)
				{
					throw new AssertionError(ex);
				}
				finally
				{
					batch.finish();
				}
			});
			batch.drain(() -> true, (asset, image) ->
			{
				assertNotNull(image);
				assertEquals(GpuSkyExtension.findUpperSkyBrightness(image), asset.upperBrightness, 0f);
				uploads.incrementAndGet();
			});
			producer.get(5, TimeUnit.SECONDS);
			assertEquals(10, uploads.get());
			assertEquals("Publish identities before resuming rendering", 10, published.get());
		}
		finally
		{
			batch.cancel();
			worker.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void failedUploadDoesNotBlockTheRestOfTheBatch() throws Exception
	{
		SkyboxUploadBatch batch = new SkyboxUploadBatch();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		AtomicInteger uploads = new AtomicInteger();
		try
		{
			Future<?> producer = worker.submit(() ->
			{
				try
				{
					BufferedImage image = image();
					assertThrows(IOException.class, () -> batch.upload(new SkyboxAsset(image), image));
					batch.upload(new SkyboxAsset(image), image);
				}
				catch (IOException ex)
				{
					throw new AssertionError(ex);
				}
				finally
				{
					batch.finish();
				}
			});
			batch.drain(() -> true, (asset, image) ->
			{
				if (uploads.incrementAndGet() == 1) throw new IllegalArgumentException("unsupported image");
			});
			producer.get(5, TimeUnit.SECONDS);
			assertEquals(2, uploads.get());
		}
		finally
		{
			batch.cancel();
			worker.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void shutdownWakesWaitingProducerWithoutGpu() throws Exception
	{
		AssetLoadSession session = new AssetLoadSession();
		SkyboxUploadBatch batch = new SkyboxUploadBatch();
		session.uploads = batch;
		ExecutorService worker = Executors.newSingleThreadExecutor();
		CompletableFuture<Void> entered = new CompletableFuture<>();
		try
		{
			Future<?> producer = worker.submit(() ->
			{
				BufferedImage image = image();
				SkyboxAsset asset = new SkyboxAsset(image);
				entered.complete(null);
				assertThrows(CancellationException.class, () -> batch.upload(asset, image));
			});
			entered.get(5, TimeUnit.SECONDS);
			session.cancel();
			producer.get(5, TimeUnit.SECONDS);
			batch.drain(() -> true, (asset, image) -> fail("Cancelled image must not upload"));
		}
		finally
		{
			batch.cancel();
			worker.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void oldContextCannotReceiveQueuedImage() throws Exception
	{
		SkyboxUploadBatch batch = new SkyboxUploadBatch();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try
		{
			Future<?> producer = worker.submit(() ->
			{
				BufferedImage image = image();
				assertThrows(CancellationException.class, () -> batch.upload(new SkyboxAsset(image), image));
			});
			batch.drain(() -> false, (asset, image) -> fail("Old context must not upload"));
			producer.get(5, TimeUnit.SECONDS);
		}
		finally
		{
			batch.cancel();
			worker.shutdownNow();
		}
	}

	private static BufferedImage image()
	{
		return new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
	}
}
