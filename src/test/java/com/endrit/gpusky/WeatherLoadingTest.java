package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.runelite.api.Constants;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class WeatherLoadingTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void roofFlagsUseLocalCoordinatesWithoutExtendedOffset()
	{
		byte[][][] flags = new byte[4][104][104];
		flags[0][4][7] = Constants.TILE_FLAG_UNDER_ROOF;
		assertTrue(WeatherRenderer.isUnderRoof(flags, 0, 4 * 128 + 64, 7 * 128 + 64));
		assertFalse(WeatherRenderer.isUnderRoof(flags, 0, 5 * 128 + 64, 7 * 128 + 64));
		assertFalse(WeatherRenderer.isUnderRoof(flags, 1, 4 * 128 + 64, 7 * 128 + 64));
		assertFalse(WeatherRenderer.isUnderRoof(flags, 0, -1, 7 * 128));
		assertFalse(WeatherRenderer.isUnderRoof(flags, 0, 104 * 128, 7 * 128));
		assertFalse(WeatherRenderer.isUnderRoof(null, 0, 0, 0));
	}

	@Test
	public void corruptLayerDoesNotStopOtherLayersAndCanRecover() throws Exception
	{
		Path directory = temporary.newFolder("sandstorm").toPath();
		Path shared = temporary.newFolder("shared").toPath();
		Filepath weatherDirectory = Filepath.Unchecked.getRooted(directory);
		Filepath sharedDirectory = Filepath.Unchecked.getRooted(shared);
		for (String prefix : new String[]{"alternate_smoke_", "dust_v2_", "alternate_dust_"})
		{
			ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png",
				directory.resolve(prefix + "0.png").toFile());
		}
		Path broken = directory.resolve("dust_0.png");
		Files.write(broken, new byte[]{1, 2, 3});
		Path good = shared.resolve("Exfoliate_0.png");
		ImageIO.write(new BufferedImage(16, 24, BufferedImage.TYPE_INT_ARGB), "png", good.toFile());
		WeatherRenderer.LoadedWeatherAssets partial = WeatherRenderer.loadAssets(
			GpuSkyExtensionConfig.WeatherEffect.SIROCCO, weatherDirectory, sharedDirectory, null);
		assertFalse(partial.isEmpty());
		assertFalse(partial.isComplete());
		ImageIO.write(new BufferedImage(12, 18, BufferedImage.TYPE_INT_ARGB), "png", broken.toFile());
		// The later layer must have loaded despite the earlier failure, and is reused on retry.
		Files.write(good, new byte[]{4, 5, 6});
		WeatherRenderer.LoadedWeatherAssets recovered = WeatherRenderer.loadAssets(
			GpuSkyExtensionConfig.WeatherEffect.SIROCCO, weatherDirectory, sharedDirectory, partial);
		assertTrue(recovered.isComplete());
	}

	@Test
	public void rainLoadsWithoutImpactPngsAndIgnoresOldSprites() throws Exception
	{
		Path directory = temporary.newFolder("rain").toPath();
		Path shared = temporary.newFolder("shared").toPath();
		Filepath weatherDirectory = Filepath.Unchecked.getRooted(directory);
		Filepath sharedDirectory = Filepath.Unchecked.getRooted(shared);
		WeatherRenderer.LoadedWeatherAssets generated = WeatherRenderer.loadAssets(
			GpuSkyExtensionConfig.WeatherEffect.RAIN, weatherDirectory, sharedDirectory, null);
		assertFalse(generated.isEmpty());
		assertTrue(generated.isComplete());
		for (String name : new String[]{"particle_ring_wavy6.png", "spray1_seq0_0.png", "spray1_seq1_0.png"})
		{
			Files.write(directory.resolve(name), new byte[]{1, 2, 3});
			Files.write(shared.resolve(name), new byte[]{1, 2, 3});
		}
		assertTrue(WeatherRenderer.loadAssets(GpuSkyExtensionConfig.WeatherEffect.RAIN,
			weatherDirectory, sharedDirectory, null).isComplete());
	}

	@Test
	public void dayAndFallUseSharedLeavesWithoutLoadingBlossomsFlowers() throws Exception
	{
		Path spring = temporary.newFolder("spring").toPath();
		Path shared = temporary.newFolder("shared").toPath();
		for (int i = 0; i <= 9; i++)
		{
			ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png",
				shared.resolve("leaf_" + i + ".png").toFile());
		}
		Path leaf = shared.resolve("leaf_9.png");
		Path petal = spring.resolve("petal_0.png");
		Files.write(petal, new byte[]{1, 2, 3});
		Filepath springDirectory = Filepath.Unchecked.getRooted(spring);
		Filepath sharedDirectory = Filepath.Unchecked.getRooted(shared);
		for (GpuSkyExtensionConfig.WeatherEffect effect : new GpuSkyExtensionConfig.WeatherEffect[]{
			GpuSkyExtensionConfig.WeatherEffect.DAY, GpuSkyExtensionConfig.WeatherEffect.FALL})
		{
			WeatherRenderer.LoadedWeatherAssets leaves = WeatherRenderer.loadAssets(
				effect, springDirectory, sharedDirectory, null);
			assertFalse(leaves.isEmpty());
			assertTrue(leaves.isComplete());
		}
		assertFalse(WeatherRenderer.loadAssets(GpuSkyExtensionConfig.WeatherEffect.SPRING,
			springDirectory, sharedDirectory, null).isComplete());
		ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", petal.toFile());
		assertTrue(WeatherRenderer.loadAssets(GpuSkyExtensionConfig.WeatherEffect.SPRING,
			springDirectory, sharedDirectory, null).isComplete());
		Files.write(leaf, new byte[]{4, 5, 6});
		assertFalse(WeatherRenderer.loadAssets(GpuSkyExtensionConfig.WeatherEffect.SPRING,
			springDirectory, sharedDirectory, null).isComplete());
		assertFalse(WeatherRenderer.loadAssets(GpuSkyExtensionConfig.WeatherEffect.FALL,
			springDirectory, sharedDirectory, null).isComplete());
		assertFalse(WeatherRenderer.loadAssets(GpuSkyExtensionConfig.WeatherEffect.DAY,
			springDirectory, sharedDirectory, null).isComplete());
	}

	@Test
	public void renamedExfoliateFramesSupplySandstormAndSeasonalMotes() throws Exception
	{
		Path directory = temporary.newFolder("weather").toPath();
		Path shared = temporary.newFolder("shared").toPath();
		BufferedImage frame = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (String prefix : new String[]{"alternate_smoke_", "dust_", "dust_v2_", "alternate_dust_"})
		{
			ImageIO.write(frame, "png", directory.resolve(prefix + "0.png").toFile());
		}
		Filepath weatherDirectory = Filepath.Unchecked.getRooted(directory);
		Filepath sharedDirectory = Filepath.Unchecked.getRooted(shared);
		Path exfoliate = shared.resolve("Exfoliate_0.png");
		for (GpuSkyExtensionConfig.WeatherEffect effect : new GpuSkyExtensionConfig.WeatherEffect[]{
			GpuSkyExtensionConfig.WeatherEffect.SIROCCO, GpuSkyExtensionConfig.WeatherEffect.SPRING,
			GpuSkyExtensionConfig.WeatherEffect.DAY, GpuSkyExtensionConfig.WeatherEffect.FALL})
		{
			// A corrupt renamed frame must be detected, not silently replaced by procedural motes.
			Files.write(exfoliate, new byte[]{1, 2, 3});
			assertFalse(effect.toString(), WeatherRenderer.loadAssets(effect,
				weatherDirectory, sharedDirectory, null).isComplete());
			ImageIO.write(frame, "png", exfoliate.toFile());
			assertTrue(effect.toString(), WeatherRenderer.loadAssets(effect,
				weatherDirectory, sharedDirectory, null).isComplete());
		}
	}

	@Test
	public void scorchedFramesOneThroughSixtyFourSupplyAshAndBloodRain() throws Exception
	{
		Path shared = temporary.newFolder("shared").toPath();
		Filepath sharedDirectory = Filepath.Unchecked.getRooted(shared);
		BufferedImage frame = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int i = 1; i <= 64; i++)
		{
			ImageIO.write(frame, "png", shared.resolve("scorched_" + i + ".png").toFile());
		}
		for (GpuSkyExtensionConfig.WeatherEffect effect : new GpuSkyExtensionConfig.WeatherEffect[]{
			GpuSkyExtensionConfig.WeatherEffect.ASH, GpuSkyExtensionConfig.WeatherEffect.PESTILENCE})
		{
			assertTrue(WeatherRenderer.loadAssets(effect, sharedDirectory, sharedDirectory, null).isComplete());
			for (int index : new int[]{1, 64})
			{
				Path endpoint = shared.resolve("scorched_" + index + ".png");
				Files.write(endpoint, new byte[]{1, 2, 3});
				assertFalse(WeatherRenderer.loadAssets(effect, sharedDirectory, sharedDirectory, null).isComplete());
				ImageIO.write(frame, "png", endpoint.toFile());
			}
		}
	}

	@Test
	public void snowDriftLoadsRenamedSharedDustSequence() throws Exception
	{
		Path directory = temporary.newFolder("snow").toPath();
		Path shared = temporary.newFolder("shared").toPath();
		Filepath weatherDirectory = Filepath.Unchecked.getRooted(directory);
		Filepath sharedDirectory = Filepath.Unchecked.getRooted(shared);
		Path dust = shared.resolve("dust_v2_0.png");
		WeatherRenderer.LoadedWeatherAssets partial = WeatherRenderer.loadAssets(
			GpuSkyExtensionConfig.WeatherEffect.SNOW, weatherDirectory, sharedDirectory, null);
		assertFalse(partial.isEmpty());
		assertFalse(partial.isComplete());
		Files.write(dust, new byte[]{1, 2, 3});
		assertFalse(WeatherRenderer.loadAssets(GpuSkyExtensionConfig.WeatherEffect.SNOW,
			weatherDirectory, sharedDirectory, partial).isComplete());
		ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", dust.toFile());
		assertTrue(WeatherRenderer.loadAssets(GpuSkyExtensionConfig.WeatherEffect.SNOW,
			weatherDirectory, sharedDirectory, partial).isComplete());
	}

	@Test
	public void renamedLightFramesKeepSmokeSeparateFromNumberedFill() throws Exception
	{
		Path directory = temporary.newFolder("moonlight").toPath();
		Filepath weatherDirectory = Filepath.Unchecked.getRooted(directory);
		Filepath sharedDirectory = weatherDirectory.join("missing-shared");
		BufferedImage frame = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int i = 4; i <= 8; i++)
		{
			ImageIO.write(frame, "png", directory.resolve("light_0" + i + ".png").toFile());
		}
		Path smoke = directory.resolve("light_03.png");
		Path beam = directory.resolve("light_03_v2.png");
		Files.write(smoke, new byte[]{1, 2, 3});
		Files.write(beam, new byte[]{1, 2, 3});
		WeatherRenderer.LoadedWeatherAssets partial = WeatherRenderer.loadAssets(
			GpuSkyExtensionConfig.WeatherEffect.MOONLIGHT, weatherDirectory, sharedDirectory, null);
		assertFalse(partial.isEmpty());
		assertFalse(partial.isComplete());
		ImageIO.write(frame, "png", smoke.toFile());
		ImageIO.write(frame, "png", beam.toFile());
		// Fill must already be cached despite the corrupt smoke and beam images.
		Files.write(directory.resolve("light_04.png"), new byte[]{1, 2, 3});
		assertTrue(WeatherRenderer.loadAssets(GpuSkyExtensionConfig.WeatherEffect.MOONLIGHT,
			weatherDirectory, sharedDirectory, partial).isComplete());
	}

	@Test
	public void missingDustLeavesSnowfallAvailableWithoutGeneratedDrift()
	{
		Filepath root = Filepath.Unchecked.getRooted(temporary.getRoot().toPath());
		WeatherRenderer.LoadedWeatherAssets assets = WeatherRenderer.loadAssets(
			GpuSkyExtensionConfig.WeatherEffect.SNOW,
			root.join("missing"), root.join("shared"), null);
		assertFalse(assets.isEmpty());
		assertFalse(assets.isComplete());
	}
}
