package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkyboxTexturesTest
{
	private final TestBackend backend = new TestBackend();
	private final SkyboxTextures textures = new SkyboxTextures(backend);

	@Test
	public void customToPresetKeepsOutgoingTextureUntilFadeEnds()
	{
		SkyboxAsset custom = upload();
		SkyboxAsset preset = upload();
		textures.require(Arrays.asList(custom, preset));
		textures.select(custom);
		int oldTexture = textures.currentTexture();
		textures.require(Collections.singleton(preset));
		assertTrue(textures.select(preset));
		textures.prune();
		assertEquals(oldTexture, textures.previousTexture());
		assertTrue(backend.live.contains(oldTexture));
		textures.finishTransition();
		assertFalse(backend.live.contains(oldTexture));
		assertEquals(textures.currentTexture(), textures.previousTexture());
	}

	@Test
	public void replacementWithoutPreloadingGetsANewHandle()
	{
		SkyboxAsset first = upload();
		SkyboxAsset replacement = new SkyboxAsset(image());
		textures.require(Collections.singleton(first));
		textures.select(first);
		int oldTexture = textures.currentTexture();
		textures.require(Collections.singleton(replacement));
		assertFalse(textures.select(replacement));
		assertEquals(oldTexture, textures.currentTexture());
		textures.upload(replacement, image());
		assertTrue(textures.select(replacement));
		assertNotEquals(oldTexture, textures.currentTexture());
		assertEquals(oldTexture, textures.previousTexture());
	}

	@Test
	public void switchesBetweenResidentPresetsWithoutPixelsOrMoreUploads()
	{
		SkyboxAsset first = upload();
		SkyboxAsset second = upload();
		textures.require(Arrays.asList(first, second));
		assertTrue(textures.select(first));
		assertTrue(textures.select(second));
		textures.finishTransition();
		assertTrue(textures.select(first));
		textures.upload(first, null);
		assertEquals(2, backend.uploads);
		assertFalse(textures.pending(Arrays.asList(first, second)));
	}

	@Test
	public void disabledSkyReleasesPrivateTextures()
	{
		SkyboxAsset first = upload();
		textures.require(Collections.singleton(first));
		textures.select(first);
		textures.require(Collections.emptyList());
		textures.select(null);
		textures.prune();
		assertEquals(0, textures.currentTexture());
		assertEquals(0, textures.previousTexture());
		assertTrue(backend.live.isEmpty());
	}

	@Test
	public void failedReplacementLeavesOldTextureAvailable()
	{
		SkyboxAsset first = upload();
		textures.require(Collections.singleton(first));
		textures.select(first);
		int original = textures.currentTexture();
		SkyboxAsset bad = new SkyboxAsset(image());
		assertThrows(IllegalArgumentException.class, () -> textures.upload(bad, null));
		assertFalse(textures.select(bad));
		assertEquals(original, textures.currentTexture());
		SkyboxAsset good = upload();
		assertTrue(textures.select(good));
	}

	private SkyboxAsset upload()
	{
		BufferedImage pixels = image();
		SkyboxAsset asset = new SkyboxAsset(pixels);
		textures.upload(asset, pixels);
		return asset;
	}

	private static BufferedImage image()
	{
		return new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
	}

	private static final class TestBackend implements SkyboxTextures.Backend
	{
		private final Set<Integer> live = new HashSet<>();
		private int uploads;

		@Override
		public int upload(BufferedImage image)
		{
			int handle = ++uploads;
			if (image == null) throw new IllegalArgumentException("Unsupported texture");
			live.add(handle);
			return handle;
		}

		@Override
		public void destroy(int texture)
		{
			assertTrue("Texture must be deleted exactly once", live.remove(texture));
		}
	}
}
