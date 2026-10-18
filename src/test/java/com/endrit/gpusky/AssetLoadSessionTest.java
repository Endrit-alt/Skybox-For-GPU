package com.endrit.gpusky;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class AssetLoadSessionTest
{
	@Test
	public void queuedOldPublicationCannotReachRestartedPlugin()
	{
		AssetLoadSession old = new AssetLoadSession();
		AtomicInteger publications = new AtomicInteger();
		Runnable queued = () -> old.publish(publications::incrementAndGet);
		old.cancel();
		AssetLoadSession restarted = new AssetLoadSession();
		queued.run();
		restarted.publish(publications::incrementAndGet);
		assertEquals(1, publications.get());
		assertThrows(CancellationException.class, old::checkActive);
		restarted.checkActive();
	}

	@Test
	public void restartHasIndependentCacheAndQueueState()
	{
		AssetLoadSession old = new AssetLoadSession();
		old.refreshQueued.set(true);
		old.skyboxSignature = "old";
		old.cancel();
		AssetLoadSession restarted = new AssetLoadSession();
		assertNull(restarted.skyboxSignature);
		assertNotSame(old.images, restarted.images);
		assertFalse(restarted.refreshQueued.get());
	}

	@Test
	public void contextRecreationInvalidatesResidentMetadata()
	{
		AssetLoadSession session = new AssetLoadSession();
		session.useSkyboxContext(1);
		session.preloadSignature = "uploaded";
		session.skyboxSignature = "active";
		AtomicInteger uploads = new AtomicInteger();
		SkyboxImageCache.Loader load = () ->
		{
			uploads.incrementAndGet();
			return new SkyboxAsset(new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB));
		};
		SkyboxAsset first = session.images.read("rain", "v1", load, ex -> fail());
		session.preloaded.put("rain", first);
		session.useSkyboxContext(1);
		assertSame(first, session.images.read("rain", "v1", load, ex -> fail()));
		session.useSkyboxContext(2);
		assertNull(session.preloadSignature);
		assertNull(session.skyboxSignature);
		assertTrue(session.preloaded.isEmpty());
		assertNotSame(first, session.images.read("rain", "v1", load, ex -> fail()));
		assertEquals(2, uploads.get());
	}

	@Test
	public void cancellationDoesNotWaitForAnInProgressPublication() throws Exception
	{
		AssetLoadSession session = new AssetLoadSession();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CompletableFuture<Void> entered = new CompletableFuture<>();
		CompletableFuture<Void> release = new CompletableFuture<>();
		try
		{
			CompletableFuture<Void> upload = CompletableFuture.runAsync(() -> session.publish(() ->
			{
				entered.complete(null);
				release.join();
			}), executor);
			entered.get(5, TimeUnit.SECONDS);
			CompletableFuture.runAsync(session::cancel, executor).get(5, TimeUnit.SECONDS);
			assertThrows(CancellationException.class, session::checkActive);
			release.complete(null);
			upload.get(5, TimeUnit.SECONDS);
		}
		finally
		{
			release.complete(null);
			executor.shutdownNow();
		}
	}
}
