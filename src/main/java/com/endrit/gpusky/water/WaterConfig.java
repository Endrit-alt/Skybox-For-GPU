package com.endrit.gpusky.water;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

/**
 * Water settings. They live in the Skybox plugin's config group and panel:
 * GpuSkyExtensionConfig extends this interface and declares the water
 * @ConfigSection headers itself (RuneLite only reads section fields declared
 * directly on the plugin's config interface). Section keys are plain constants here.
 */
public interface WaterConfig extends Config
{
	/** Former standalone "Water for GPU" group; saved values are migrated once. */
	String LEGACY_GROUP = "modernanimatedwater";
	String generalSection = "waterGeneral";

	// ---------- General (no section) ----------

	@ConfigItem(
		keyName = "animatedWater",
		name = "Enable animated water",
		description = "Replace confirmed OSRS water material with modern procedural water.",
		section = generalSection,
		position = 0
	)
	default boolean animatedWater()
	{
		return true;
	}

	@Range(min = 0, max = 200)
	@ConfigItem(
		keyName = "animationSpeed",
		name = "Animation speed",
		description = "Speed of all water motion: waves, surface depth, foam and swash.",
		section = generalSection,
		position = 1
	)
	default int animationSpeed()
	{
		return 110;
	}

	// ---------- Color & lighting ----------

	String colorSection = "waterColorSection";

	@Range(min = -190, max = 160)
	@ConfigItem(
		keyName = "waterBrightnessAdjustment",
		name = "Brightness adjustment",
		description = "Adjusts water brightness around the 245% baseline; zero uses the default brightness.",
		section = colorSection,
		position = 0
	)
	default int waterBrightnessAdjustment()
	{
		return 0;
	}

	/** The default water color: the water's actual visible tone (before reflections). */
	Color DEFAULT_WATER_COLOR = new Color(0x5C, 0x8A, 0x9F);
	/** Default of the first version of this setting; treated as "unchanged". */
	Color OLD_DEFAULT_WATER_COLOR = new Color(0x17, 0x99, 0xD6);

	@ConfigItem(keyName = "waterColor", name = "Water color",
		description = "Overall color of the water; deep and shallow tones follow it. The default is the original water color.",
		section = colorSection, position = 1)
	default Color waterColor() { return DEFAULT_WATER_COLOR; }

	@ConfigItem(keyName = "skyReflections", name = "Reflect Weather skybox",
		description = "Reflect the active skybox on the water and add a sun/moon glint from its brightest point. Off uses the built-in sky colors.",
		section = colorSection, position = 2)
	default boolean skyReflections() { return true; }

	@Range(min = 0, max = 200)
	@ConfigItem(keyName = "skyReflectionStrength", name = "Skybox reflection (%)",
		description = "How strongly the skybox is mirrored on the water. 100 is the tuned default; 200 doubles it.",
		section = colorSection, position = 3)
	default int skyReflectionStrength() { return 100; }

	@Range(min = 0, max = 300)
	@ConfigItem(keyName = "sunGlintStrength", name = "Sun/moon glint (%)",
		description = "Sparkling path of light on the waves toward the brightest point of the skybox.",
		section = colorSection, position = 4)
	default int sunGlintStrength() { return 50; }

	@ConfigItem(keyName = "glintFollowsCamera", name = "Camera glint",
		description = "Adds a second glint on the water ahead of you, out past your character, coloured by the part of the sky you're looking toward. Uses the sun/moon glint strength.",
		section = colorSection, position = 5)
	default boolean glintFollowsCamera() { return false; }

	@ConfigItem(keyName = "reflectionNoise", name = "Reflection noise",
		description = "Drifting water noise that mottles the skybox reflection and the sun/moon glint.",
		section = colorSection, position = 6)
	default boolean reflectionNoise() { return true; }

	// ---------- Waves & surface ----------

	String wavesSection = "waterWavesSection";

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "waveStrength",
		name = "Wave strength",
		description = "Controls the apparent slope and contrast of the waves.",
		section = wavesSection,
		position = 0
	)
	default int waveStrength()
	{
		return 15;
	}

	@ConfigItem(keyName = "shoreFollowingWaves", name = "Shore-following reflective waves",
		description = "Curve reflective crests parallel to nearby banks, blending back to the selected wave style offshore. Normal-map detail is unchanged.",
		section = wavesSection, position = 2)
	default boolean shoreFollowingWaves() { return true; }

	@Range(min = 0, max = 700)
	@ConfigItem(keyName = "surfaceDepthStrength", name = "Surface depth (%)",
		description = "Shaded wavelet relief so the surface reads as 3D, not flat. 0 restores the previous flat look.",
		section = wavesSection, position = 3)
	default int surfaceDepth() { return 50; }

	@ConfigItem(keyName = "softSurfaceShadows", name = "Softer surface-depth shadows",
		description = "Lighten the dark sides of the surface-depth wavelets while keeping highlights and glints the same.",
		section = wavesSection, position = 4)
	default boolean softSurfaceShadows() { return true; }

	@Range(min = 0, max = 300)
	@ConfigItem(keyName = "openRippleStrength", name = "Open-water ripple strength (%)",
		description = "100 preserves the current open-water texture strength.",
		section = wavesSection, position = 5)
	default int openRippleStrength() { return 100; }

	@Range(min = 0, max = 300)
	@ConfigItem(keyName = "surfaceGrainStrength", name = "Surface grain strength (%)",
		description = "100 preserves the current surface grain strength.",
		section = wavesSection, position = 6)
	default int surfaceGrainStrength() { return 100; }

	// ---------- Shore foam ----------

	String foamSection = "waterFoamSection";

	@ConfigItem(
		keyName = "shoreFoam",
		name = "Shore foam",
		description = "Adds foam along reliably detected water boundaries (either style).",
		section = foamSection,
		position = 0
	)
	default boolean shoreFoam()
	{
		return true;
	}

	@ConfigItem(keyName = "swashFoam", name = "Swash foam style",
		description = "A single beach-wash front that surges in and out, with haze trailing into the water. Off uses the five-layer alternate foam instead.",
		section = foamSection, position = 1)
	default boolean swashFoam() { return true; }

	@Range(min = 0, max = 200)
	@ConfigItem(keyName = "swashFoamOpacity", name = "Swash foam opacity (%)",
		description = "Strength of the swash foam. 100 is the tuned default.",
		section = foamSection, position = 2)
	default int swashFoamOpacity() { return 100; }

	@ConfigItem(keyName = "swashHazeStyle", name = "Swash haze style",
		description = "Look of the milky haze trailing out from the swash foam. Classic is the original.",
		section = foamSection, position = 3)
	default SwashHazeStyle swashHazeStyle() { return SwashHazeStyle.CLASSIC; }

	// ---------- Classic foam (only used when Swash foam style is off) ----------

	String classicFoamSection = "waterClassicFoamSection";

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "contactFoamOpacity",
		name = "Alternate foam strength",
		description = "Overall strength of the five-layer alternate foam (used when Swash foam style is off). Narrow water halves it.",
		section = classicFoamSection,
		position = 0
	)
	default int contactFoamOpacity()
	{
		return 8;
	}

	@Range(min = 25, max = 250)
	@ConfigItem(keyName = "classicFoamWidth", name = "Width (%)",
		description = "Width of all alternate foam layers together. 100 is the tuned default.",
		section = classicFoamSection, position = 1)
	default int classicFoamWidth() { return 100; }

	@Range(min = 0, max = 300)
	@ConfigItem(keyName = "classicFoamSoftness", name = "Edge softness (%)",
		description = "Edge softness of all alternate foam layers together. 100 is the tuned default.",
		section = classicFoamSection, position = 2)
	default int classicFoamSoftness() { return 100; }

	@ConfigItem(keyName = "classicSoftOuterLayers", name = "Soft outer layers",
		description = "Show the two soft blue-grey outer foam layers.",
		section = classicFoamSection, position = 3)
	default boolean classicSoftOuterLayers() { return true; }

	String bubbleSection = "waterBubbleSection";

	@ConfigItem(keyName = "bubbles", name = "Enable bubbles", description = "Sparse decorative events, not real fishing spots.",
		section = bubbleSection, position = 0)
	default boolean bubbles() { return true; }

	@Range(min = 0, max = 300)
	@ConfigItem(keyName = "bubbleFrequency", name = "Frequency (%)", description = "Default 50%. 100 is sparse; 0 disables events.",
		section = bubbleSection, position = 1)
	default int bubbleFrequency() { return 50; }

	@Range(min = 0, max = 100)
	@ConfigItem(keyName = "bubbleOpacity", name = "Opacity (%)", description = "White blended over the water, like Contact Foam. Default 25%.",
		section = bubbleSection, position = 2)
	default int bubbleOpacity() { return 25; }

}
