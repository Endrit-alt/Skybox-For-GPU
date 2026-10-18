package com.endrit.gpusky;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class RegionOverrideTest
{
	@Test
	public void dayOverrideMatchesDaylightAndPreservesExplicitPreset()
	{
		RegionOverride override = RegionOverride.create("Day", 110, 160, 0, 110,
			100, 100, 100, 100, 100);
		override.weather = GpuSkyExtensionConfig.RegionWeatherOverride.DAY;
		RegionOverride normalized = override.normalized(0);
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.DAY, normalized.weather);
		assertEquals("Daylight.jpg", normalized.skyboxImage.resolveFilename(
			GpuSkyExtensionConfig.WeatherEffect.valueOf(normalized.weather.name())));
		override.skyboxImage = GpuSkyExtensionConfig.SkyboxImage.RAIN;
		assertEquals("Rain.jpg", override.normalized(0).skyboxImage.resolveFilename(
			GpuSkyExtensionConfig.WeatherEffect.DAY));
	}

	@Test
	public void fallOverrideUsesDaylightAndPreservesExplicitPreset()
	{
		RegionOverride override = RegionOverride.create("Fall", 110, 160, 0, 110,
			100, 100, 100, 100, 100);
		override.weather = GpuSkyExtensionConfig.RegionWeatherOverride.FALL;
		RegionOverride normalized = override.normalized(0);
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.FALL, normalized.weather);
		assertEquals("Daylight.jpg", normalized.skyboxImage.resolveFilename(
			GpuSkyExtensionConfig.WeatherEffect.valueOf(normalized.weather.name())));
		override.skyboxImage = GpuSkyExtensionConfig.SkyboxImage.FALL;
		assertEquals("Daylight.jpg", override.normalized(0).skyboxImage.resolveFilename(
			GpuSkyExtensionConfig.WeatherEffect.RAIN));
	}

	@Test
	public void regionOverridesAreEnabledByDefault()
	{
		assertFalse(new GpuSkyExtensionConfig()
		{
		}.disableRegionOverrides());
	}

	@Test
	public void skyboxPresetsPreloadAtFourKByDefault()
	{
		GpuSkyExtensionConfig config = new GpuSkyExtensionConfig()
		{
		};
		assertTrue(config.preloadSkyboxPresets());
		assertEquals(GpuSkyExtensionConfig.TextureResolution.RES_4K, config.textureResolution());
	}

	@Test
	public void lightingDefaultsUseFiftyPercentWorldAndTintAndSeventyPercentOtherLighting()
	{
		GpuSkyExtensionConfig config = new GpuSkyExtensionConfig()
		{
		};
		assertEquals(50, config.worldLighting());
		assertEquals(70, config.rimLighting());
		assertEquals(70, config.backlighting());
		assertEquals(50, config.skyboxTint());
		assertEquals(70, config.atmosphere());
		assertEquals(110, config.exposure());
	}

	@Test
	public void createUsesCurrentDefaults()
	{
		RegionOverride override = RegionOverride.create("Test", 110, 160, -3, 110,
			100, 100, 100, 100, 100);

		assertTrue(override.enabled);
		assertTrue(override.inheritSkySettings);
		assertTrue(override.inheritLightSettings);
		assertEquals(110, override.horizontalFov);
		assertEquals(-3, override.pitchOffset);
		assertEquals(100, override.weatherIntensity);
		assertEquals(100, override.worldLighting);
		assertEquals(100, override.rimLighting);
		assertEquals(100, override.backlighting);
		assertEquals(100, override.skyboxTint);
		assertEquals(100, override.atmosphere);
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.MAIN, override.weather);
		assertEquals(GpuSkyExtensionConfig.SkyboxImage.MATCH_WEATHER, override.skyboxImage);
	}

	@Test
	public void normalizedClampsEditableValues()
	{
		RegionOverride override = RegionOverride.create("Test", 110, 160, -3, 110,
			100, 100, 100, 100, 100);
		override.weatherIntensity = 250;
		override.horizontalFov = 10;
		override.yawOffset = 500;
		override.pitchOffset = -20;
		override.exposure = 1;
		override.worldLighting = -1;
		override.rimLighting = 250;

		RegionOverride normalized = override.normalized(0);

		assertEquals(200, normalized.weatherIntensity);
		assertEquals(60, normalized.horizontalFov);
		assertEquals(180, normalized.yawOffset);
		assertEquals(-10, normalized.pitchOffset);
		assertEquals(10, normalized.exposure);
		assertEquals(0, normalized.worldLighting);
		assertEquals(200, normalized.rimLighting);

		override.pitchOffset = 20;
		assertEquals(10, override.normalized(0).pitchOffset);
	}

	@Test
	public void parsedRegionIdsAcceptsCommaAndWhitespace()
	{
		RegionOverride override = RegionOverride.create("Test", 110, 160, -3, 110,
			100, 100, 100, 100, 100);
		override.regionIds = "8302, 12894\n8302 invalid 70000";

		assertEquals(new HashSet<>(Arrays.asList(8302, 12894)), override.parsedRegionIds());
	}

	@Test
	public void firstRunDefaultsAreEditableOverridesInDisplayOrder()
	{
		List<RegionOverride> defaults = DefaultRegionOverrides.create(new GpuSkyExtensionConfig()
		{
		});

		assertEquals(Arrays.asList("Desert", "Morytania", "Snow", "Wilderness", "Lava", "Blood", "Cox"),
			Arrays.asList(defaults.get(0).name, defaults.get(1).name, defaults.get(2).name,
				defaults.get(3).name, defaults.get(4).name, defaults.get(5).name, defaults.get(6).name));
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.SIROCCO, defaults.get(0).weather);
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.MOONLIGHT, defaults.get(1).weather);
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.SNOW, defaults.get(2).weather);
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.ASH, defaults.get(3).weather);
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.ASH, defaults.get(4).weather);
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.PESTILENCE, defaults.get(5).weather);
		assertEquals(GpuSkyExtensionConfig.RegionWeatherOverride.DISABLED, defaults.get(6).weather);

		Set<Integer> assigned = new HashSet<>();
		for (RegionOverride override : defaults)
		{
			assertEquals(70, override.worldLighting);
			assertEquals(70, override.rimLighting);
			assertEquals(70, override.backlighting);
			assertEquals(70, override.skyboxTint);
			assertEquals(70, override.atmosphere);
			assertTrue(override.enabled);
			assertTrue(override.inheritSkySettings);
			assertTrue(override.inheritLightSettings);
			assertEquals(100, override.weatherIntensity);
			assertEquals(override == defaults.get(6)
				? GpuSkyExtensionConfig.SkyboxImage.COX
				: GpuSkyExtensionConfig.SkyboxImage.MATCH_WEATHER, override.skyboxImage);
			for (int regionId : override.parsedRegionIds())
			{
				assertFalse("Duplicate default region " + regionId, assigned.contains(regionId));
				assigned.add(regionId);
			}
		}
		assertTrue(defaults.get(2).parsedRegionIds().contains(20597));
		assertTrue(defaults.get(5).parsedRegionIds().contains(14644));
		assertTrue(defaults.get(6).parsedRegionIds().contains(RegionOverride.COX_OLM_REGION_ID));
	}
}
