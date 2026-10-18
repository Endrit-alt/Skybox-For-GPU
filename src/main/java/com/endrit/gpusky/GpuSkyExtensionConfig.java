package com.endrit.gpusky;

import com.endrit.gpusky.water.WaterConfig;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(GpuSkyExtensionConfig.GROUP)
public interface GpuSkyExtensionConfig extends WaterConfig
{
	String GROUP = "skybox-for-gpu";
	String SKYBOX_SECTION = "skybox";
	String WEATHER_SECTION = "weather";
	String OVERRIDES_SECTION = "overrides";
	String LIGHTING_SECTION = "lighting";

	@ConfigSection(name = "Skybox Extra's", description = "Custom panorama, projection and texture settings", position = 2, closedByDefault = true)
	String skyboxSection = SKYBOX_SECTION;

	@ConfigSection(name = "Weather and Skybox", description = "Weather effects and skybox presets", position = 0, closedByDefault = true)
	String weatherSection = WEATHER_SECTION;

	@ConfigSection(name = "Region Overrides Side Panel", description = "Region-specific sky and weather settings", position = 1, closedByDefault = true)
	String overridesSection = OVERRIDES_SECTION;

	@ConfigSection(name = "Lighting", description = "Skybox lighting, tint and atmosphere", position = 3, closedByDefault = true)
	String lightingSection = LIGHTING_SECTION;

	// Water sections (the water settings themselves are in water/WaterConfig).
	// RuneLite only reads section headers declared directly on this interface.
	@ConfigSection(name = "Water", description = "Turn the water on or off and set its speed", position = 10, closedByDefault = true)
	String waterGeneralSectionHeader = WaterConfig.generalSection;

	@ConfigSection(name = "Water: Color & lighting", description = "Water brightness, skybox reflection and glints", position = 11, closedByDefault = true)
	String waterColorSectionHeader = WaterConfig.colorSection;

	@ConfigSection(name = "Water: Waves & surface", description = "Wave shape, reflective crests, surface depth and texture", position = 12, closedByDefault = true)
	String waterWavesSectionHeader = WaterConfig.wavesSection;

	@ConfigSection(name = "Water: Shore foam", description = "Foam along banks: the swash style, or the alternate foam", position = 13, closedByDefault = true)
	String waterFoamSectionHeader = WaterConfig.foamSection;

	@ConfigSection(name = "Water: Alternate foam", description = "The five-layer alternate foam, used when Swash foam style is off", position = 14, closedByDefault = true)
	String waterClassicFoamSectionHeader = WaterConfig.classicFoamSection;

	@ConfigSection(name = "Water: Surface bubbles", description = "Decorative bubble clusters and expanding rings", position = 15, closedByDefault = true)
	String waterBubbleSectionHeader = WaterConfig.bubbleSection;

	@ConfigItem(
		keyName = "skyboxFile",
		name = "Skybox custom override file",
		description = "PNG or JPEG path relative to .runelite/plugin-data/skybox-for-gpu; blank uses the selected preset",
		position = 3,
		section = SKYBOX_SECTION
	)
	default String skyboxFile()
	{
		return "";
	}

	@ConfigItem(
		keyName = "caveSkyboxFile",
		name = "Cave skybox custom override file",
		description = "Panorama path relative to .runelite/plugin-data/skybox-for-gpu used underground; blank keeps the overworld sky",
		position = 4,
		section = SKYBOX_SECTION
	)
	default String caveSkyboxFile()
	{
		return "";
	}

	@Range(min = 60, max = 120)
	@ConfigItem(
		keyName = "horizontalFov",
		name = "Horizontal FOV",
		description = "Horizontal field of view used for the panorama",
		position = 5,
		section = SKYBOX_SECTION
	)
	default int horizontalFov()
	{
		return 110;
	}

	@Range(min = -180, max = 180)
	@ConfigItem(
		keyName = "yawOffset",
		name = "Horizontal offset",
		description = "Rotates the panorama left or right",
		position = 6,
		section = SKYBOX_SECTION
	)
	default int yawOffset()
	{
		return 160;
	}

	@Range(min = -10, max = 10)
	@ConfigItem(
		keyName = "pitchOffset",
		name = "Vertical offset",
		description = "Moves the panorama up or down",
		position = 7,
		section = SKYBOX_SECTION
	)
	default int pitchOffset()
	{
		return 0;
	}

	@Range(min = 10, max = 300)
	@ConfigItem(
		keyName = "exposure",
		name = "Exposure",
		description = "Brightness multiplier for the panorama",
		position = 8,
		section = SKYBOX_SECTION
	)
	default int exposure()
	{
		return 110;
	}

	@ConfigItem(keyName = "instantTransitions", name = "Instant transitions", description = "Switch panoramas without crossfading", position = 2, section = SKYBOX_SECTION)
	default boolean instantTransitions()
	{
		return false;
	}

	@Range(min = 0, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "worldLighting", name = "Skybox world lighting", description = "Blends distant surfaces toward the sky", position = 0, section = LIGHTING_SECTION)
	default int worldLighting()
	{
		return 50;
	}

	@Range(min = 0, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "rimLighting", name = "Skybox rim lighting", description = "Adds sky-colored edge lighting", position = 1, section = LIGHTING_SECTION)
	default int rimLighting()
	{
		return 70;
	}

	@Range(min = 0, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "backlighting", name = "Skybox backlighting", description = "Adds light to opposite-facing model edges", position = 2, section = LIGHTING_SECTION)
	default int backlighting()
	{
		return 70;
	}

	@Range(min = 0, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "skyboxTint", name = "Skybox tint", description = "Adds a subtle sky-colored world tint", position = 3, section = LIGHTING_SECTION)
	default int skyboxTint()
	{
		return 50;
	}

	@Range(min = 0, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "atmosphere", name = "Skybox atmosphere", description = "Adds lightweight sky lighting and weather grading", position = 4, section = LIGHTING_SECTION)
	default int atmosphere()
	{
		return 70;
	}

	@ConfigItem(keyName = "softFacets", name = "Soft facets", description = "Softens per-triangle steps in rim, backlight and atmosphere lighting", position = 5, section = LIGHTING_SECTION)
	default boolean softFacets()
	{
		return true;
	}

	/** Former checkbox; a saved "true" is migrated to {@link TextureResolution#RES_8K}. */
	String LEGACY_EIGHT_K_KEY = "eightKTextureResolution";

	@ConfigItem(
		keyName = "textureResolution",
		name = "Texture resolution",
		description = "Resolution skybox presets are loaded at: 2K uses the least memory, 8K is the original resolution",
		position = 1,
		section = SKYBOX_SECTION
	)
	default TextureResolution textureResolution()
	{
		return TextureResolution.RES_4K;
	}

	@ConfigItem(
		keyName = "preloadSkyboxPresets",
		name = "Pre-load skybox presets",
		description = "Keeps every skybox preset loaded for instant switching; uses substantial RAM and VRAM",
		position = 0,
		section = SKYBOX_SECTION
	)
	default boolean preloadSkyboxPresets()
	{
		return true;
	}

	@ConfigItem(keyName = "weatherEffect", name = "Weather effect", description = "Weather particle set", position = 0, section = WEATHER_SECTION)
	default WeatherEffect weatherEffect()
	{
		return WeatherEffect.DAY;
	}

	@ConfigItem(keyName = "skyboxImage", name = "Skybox preset", description = "Skybox preset; Match weather follows the selected weather effect", position = 1, section = WEATHER_SECTION)
	default SkyboxImage skyboxImage()
	{
		return SkyboxImage.MATCH_WEATHER;
	}

	@Range(min = 0, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "weatherIntensity", name = "Weather intensity", description = "Density of weather particles", position = 2, section = WEATHER_SECTION)
	default int weatherIntensity()
	{
		return 100;
	}

	@Range(min = 1, max = 1440)
	@Units(Units.MINUTES)
	@ConfigItem(keyName = "randomWeatherMinutes", name = "Random timer",
		description = "Minutes between weather changes when Weather effect is Random; region overrides still take priority",
		position = 3, section = WEATHER_SECTION)
	default int randomWeatherMinutes()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "showOverridesPanel",
		name = "Region overrides side panel",
		description = "Show the region overrides icon in the RuneLite sidebar",
		position = 0,
		section = OVERRIDES_SECTION
	)
	default boolean showOverridesPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "disableRegionOverrides",
		name = "Disable region overrides",
		description = "Use the main skybox, weather, and lighting settings in every region",
		position = 1,
		section = OVERRIDES_SECTION
	)
	default boolean disableRegionOverrides()
	{
		return false;
	}

	enum TextureResolution
	{
		RES_2K("2K", 2048),
		RES_4K("4K", 4096),
		RES_8K("8K (original)", 0);

		private final String displayName;
		/** Largest panorama width to load; 0 = original size. */
		private final int maxWidth;

		TextureResolution(String displayName, int maxWidth)
		{
			this.displayName = displayName;
			this.maxWidth = maxWidth;
		}

		int maxWidth()
		{
			return maxWidth;
		}

		String shortName()
		{
			return maxWidth == 0 ? "8K" : displayName;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

		enum WeatherEffect
	{
		DISABLED("Disabled", null, 0),
		RANDOM("Random", null, 0),
		DAY("Day", "Daylight.jpg", 5),
		SUNLIGHT("Sunlight", "Sunlight.jpg", 8),
		MOONLIGHT("Moonlight", "Moonlight.jpg", 7),
		RAIN("Rain", "Rain.jpg", 4),
		SPRING("Blossom", "Blossom.jpg", 5),
		FALL("Fall", "Daylight.jpg", 5),
		SNOW("Snow", "Snow.jpg", 6),
		ASH("Ashfall", "Ashfall.jpg", 3),
		PESTILENCE("Bloodfall", "Bloodfall.jpg", 2),
		SIROCCO("Sandstorm", "Sandstorm.jpg", 1);

		private final String displayName;
		private final String skyboxFilename;
		private final int shaderGrade;

		WeatherEffect(String displayName, String skyboxFilename, int shaderGrade)
		{
			this.displayName = displayName;
			this.skyboxFilename = skyboxFilename;
			this.shaderGrade = shaderGrade;
		}

		String skyboxFilename()
		{
			return skyboxFilename;
		}

		int shaderGrade()
		{
			return shaderGrade;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

	enum SkyboxImage
	{
		DISABLED("Disabled", null),
		MATCH_WEATHER("Match weather", null),
		DAYLIGHT("Daylight", "Daylight.jpg"),
		SUNLIGHT("Sunlight", "Sunlight.jpg"),
		MOONLIGHT("Moonlight", "Moonlight.jpg"),
		RAIN("Rain", "Rain.jpg"),
		SPRING("Blossom", "Blossom.jpg"),
		FALL("Fall", "Daylight.jpg"),
		SNOW("Snow", "Snow.jpg"),
		ASH("Ashfall", "Ashfall.jpg"),
		PESTILENCE("Bloodfall", "Bloodfall.jpg"),
		SIROCCO("Sandstorm", "Sandstorm.jpg"),
		COX("Cox", "Cox.jpg");

		private final String displayName;
		private final String presetFilename;

		SkyboxImage(String displayName, String presetFilename)
		{
			this.displayName = displayName;
			this.presetFilename = presetFilename;
		}

		String resolveFilename(WeatherEffect selectedWeather)
		{
			if (this == DISABLED)
			{
				return null;
			}
			return this == MATCH_WEATHER ? selectedWeather.skyboxFilename() : presetFilename;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

	enum RegionWeatherOverride
	{
		MAIN("Main"),
		DISABLED("Disabled"),
		DAY("Day"),
		SUNLIGHT("Sunlight"),
		MOONLIGHT("Moonlight"),
		RAIN("Rain"),
		SPRING("Blossom"),
		FALL("Fall"),
		SNOW("Snow"),
		ASH("Ashfall"),
		PESTILENCE("Bloodfall"),
		SIROCCO("Sandstorm");

		private final String displayName;

		RegionWeatherOverride(String displayName)
		{
			this.displayName = displayName;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}
}
