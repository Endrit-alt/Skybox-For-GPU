package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkyboxImageCacheTest
{
	@Test
	public void badImageKeepsLastGoodVersionAndDoesNotBlockAnotherImage()
	{
		SkyboxImageCache cache = new SkyboxImageCache();
		SkyboxAsset good = new SkyboxAsset(new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB));
		AtomicInteger failures = new AtomicInteger();
		assertSame(good, cache.read("rain", "v1", () -> good, ex -> fail()));
		assertSame(good, cache.read("rain", "v2", () -> { throw new IOException("truncated JPEG"); },
			ex -> failures.incrementAndGet()));
		assertSame(good, cache.read("rain", "v2", () -> { throw new AssertionError("unnecessary retry"); }, ex -> fail()));
		assertSame(good, cache.read("snow", "v1", () -> good, ex -> fail()));
		assertEquals(1, failures.get());
		SkyboxAsset repaired = new SkyboxAsset(new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB));
		assertSame(repaired, cache.read("rain", "v3", () -> repaired, ex -> fail()));
	}

	@Test
	public void unchangedImagesAreReusedAndEvictedImagesCanBeReleased()
	{
		SkyboxImageCache cache = new SkyboxImageCache();
		AtomicInteger decodes = new AtomicInteger();
		SkyboxImageCache.Loader loader = () ->
		{
			decodes.incrementAndGet();
			return new SkyboxAsset(new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB));
		};
		SkyboxAsset first = cache.read("sky", "4k", loader, ex -> fail());
		assertSame(first, cache.read("sky", "4k", loader, ex -> fail()));
		assertNotSame(first, cache.read("sky", "8k", loader, ex -> fail()));
		cache.retain(Collections.emptySet());
		cache.read("sky", "8k", loader, ex -> fail());
		assertEquals(3, decodes.get());
	}
}
