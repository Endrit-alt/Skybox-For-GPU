package com.endrit.gpusky;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class RegionOverride
{
	static final int COX_OLM_REGION_ID = 12889;

	String id;
	String name;
	boolean enabled;
	String regionIds;
	String skyboxFile;
	GpuSkyExtensionConfig.RegionWeatherOverride weather;
	GpuSkyExtensionConfig.SkyboxImage skyboxImage;
	int weatherIntensity;
	boolean inheritSkySettings;
	int horizontalFov;
	int yawOffset;
	int pitchOffset;
	int exposure;
	boolean inheritLightSettings;
	int worldLighting;
	int rimLighting;
	int backlighting;
	int skyboxTint;
	int atmosphere;
	/** "#RRGGBB", or empty to use the main Water color setting. */
	String waterColor;

	RegionOverride()
	{
	}

	static RegionOverride create(String name, int horizontalFov, int yawOffset, int pitchOffset, int exposure,
		int worldLighting, int rimLighting, int backlighting, int skyboxTint, int atmosphere)
	{
		RegionOverride override = new RegionOverride();
		override.id = UUID.randomUUID().toString();
		override.name = name;
		override.enabled = true;
		override.regionIds = "";
		override.skyboxFile = "";
		override.weather = GpuSkyExtensionConfig.RegionWeatherOverride.MAIN;
		override.skyboxImage = GpuSkyExtensionConfig.SkyboxImage.MATCH_WEATHER;
		override.weatherIntensity = 100;
		override.inheritSkySettings = true;
		override.horizontalFov = horizontalFov;
		override.yawOffset = yawOffset;
		override.pitchOffset = pitchOffset;
		override.exposure = exposure;
		override.inheritLightSettings = true;
		override.worldLighting = worldLighting;
		override.rimLighting = rimLighting;
		override.backlighting = backlighting;
		override.skyboxTint = skyboxTint;
		override.atmosphere = atmosphere;
		override.waterColor = "";
		return override;
	}

	RegionOverride copy()
	{
		RegionOverride copy = new RegionOverride();
		copy.id = id;
		copy.name = name;
		copy.enabled = enabled;
		copy.regionIds = regionIds;
		copy.skyboxFile = skyboxFile;
		copy.weather = weather;
		copy.skyboxImage = skyboxImage;
		copy.weatherIntensity = weatherIntensity;
		copy.inheritSkySettings = inheritSkySettings;
		copy.horizontalFov = horizontalFov;
		copy.yawOffset = yawOffset;
		copy.pitchOffset = pitchOffset;
		copy.exposure = exposure;
		copy.inheritLightSettings = inheritLightSettings;
		copy.worldLighting = worldLighting;
		copy.rimLighting = rimLighting;
		copy.backlighting = backlighting;
		copy.skyboxTint = skyboxTint;
		copy.atmosphere = atmosphere;
		copy.waterColor = waterColor;
		return copy;
	}

	RegionOverride normalized(int index)
	{
		RegionOverride copy = copy();
		if (copy.id == null || copy.id.trim().isEmpty())
		{
			copy.id = UUID.randomUUID().toString();
		}
		copy.name = trim(copy.name);
		if (copy.name.isEmpty())
		{
			copy.name = "Region override " + (index + 1);
		}
		copy.regionIds = trim(copy.regionIds);
		copy.skyboxFile = trim(copy.skyboxFile);
		if (copy.weather == null)
		{
			copy.weather = GpuSkyExtensionConfig.RegionWeatherOverride.MAIN;
		}
		if (copy.skyboxImage == null)
		{
			copy.skyboxImage = GpuSkyExtensionConfig.SkyboxImage.MATCH_WEATHER;
		}
		copy.weatherIntensity = Math.max(0, Math.min(200, copy.weatherIntensity));
		copy.horizontalFov = Math.max(60, Math.min(120, copy.horizontalFov));
		copy.yawOffset = Math.max(-180, Math.min(180, copy.yawOffset));
		copy.pitchOffset = Math.max(-10, Math.min(10, copy.pitchOffset));
		copy.exposure = Math.max(10, Math.min(300, copy.exposure));
		copy.worldLighting = strength(copy.worldLighting);
		copy.rimLighting = strength(copy.rimLighting);
		copy.backlighting = strength(copy.backlighting);
		copy.skyboxTint = strength(copy.skyboxTint);
		copy.atmosphere = strength(copy.atmosphere);
		copy.waterColor = parseWaterColor(copy.waterColor) == null ? "" : trim(copy.waterColor).toUpperCase(java.util.Locale.ROOT);
		return copy;
	}

	private static int strength(int value)
	{
		return Math.max(0, Math.min(200, value));
	}

	boolean matches(int regionId)
	{
		return enabled && parsedRegionIds().contains(regionId);
	}

	boolean matchesChambersOfXeric()
	{
		return enabled && parsedRegionIds().contains(COX_OLM_REGION_ID);
	}

	/** Whether a typed token means this region, compared by number ("012889", "+12889"). */
	static boolean isRegion(String token, int regionId)
	{
		try
		{
			return Integer.parseInt(token.trim()) == regionId;
		}
		catch (NumberFormatException ex)
		{
			return false;
		}
	}

	Set<Integer> parsedRegionIds()
	{
		if (regionIds == null || regionIds.trim().isEmpty())
		{
			return Collections.emptySet();
		}
		Set<Integer> ids = new HashSet<>();
		for (String value : regionIds.split("[,\\s]+"))
		{
			try
			{
				int id = Integer.parseInt(value);
				if (id >= 0 && id <= 65535)
				{
					ids.add(id);
				}
			}
			catch (NumberFormatException ignored)
			{
				// Invalid values remain visible in the editor but never match a region.
			}
		}
		return Collections.unmodifiableSet(ids);
	}

	/** The custom water color, or null to use the main Water color setting. */
	java.awt.Color customWaterColor()
	{
		return parseWaterColor(waterColor);
	}

	static java.awt.Color parseWaterColor(String value)
	{
		String text = trim(value);
		if (!text.matches("#[0-9a-fA-F]{6}")) return null;
		return new java.awt.Color(Integer.parseInt(text.substring(1), 16));
	}

	static String formatWaterColor(java.awt.Color color)
	{
		return String.format(java.util.Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
	}

	private static String trim(String value)
	{
		return value == null ? "" : value.trim();
	}
}
