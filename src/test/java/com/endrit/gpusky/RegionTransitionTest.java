package com.endrit.gpusky;

import java.util.Arrays;
import java.util.List;
import net.runelite.api.GameState;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegionTransitionTest
{
	@Test
	public void teleportKeepsPublishedOverrideUntilDestinationIsReady()
	{
		GpuSkyExtension extension = extension(new MutableConfig());
		RegionOverride origin = override("Snow", 12586, GpuSkyExtensionConfig.RegionWeatherOverride.SNOW);
		RegionOverride destination = override("Blood", 12664, GpuSkyExtensionConfig.RegionWeatherOverride.PESTILENCE);
		List<RegionOverride> overrides = Arrays.asList(origin, destination);
		RegionContext context = new RegionContext().update(GameState.LOGGED_IN, 12586, false);
		publish(extension, GpuSkyExtensionPlugin.activeRegionOverride(context, overrides, false));
		assertEquals(GpuSkyExtensionConfig.WeatherEffect.SNOW, extension.activeWeather());

		context = context.update(GameState.LOADING, -1, false);
		assertFalse(context.ready);
		assertEquals(12586, context.regionId);
		assertTrue(extension.regionActive());
		assertEquals(GpuSkyExtensionConfig.WeatherEffect.SNOW, extension.activeWeather());
		context = context.update(GameState.LOGGED_IN, -1, false);
		assertFalse(context.ready);
		assertEquals(GpuSkyExtensionConfig.WeatherEffect.SNOW, extension.activeWeather());

		context = context.update(GameState.LOGGED_IN, 12664, false);
		assertTrue(context.ready);
		// Even with the new region known, the renderer holds its last complete publication.
		assertEquals(GpuSkyExtensionConfig.WeatherEffect.SNOW, extension.activeWeather());
		publish(extension, GpuSkyExtensionPlugin.activeRegionOverride(context, overrides, false));
		assertTrue(extension.regionActive());
		assertEquals(GpuSkyExtensionConfig.WeatherEffect.PESTILENCE, extension.activeWeather());
	}

	@Test
	public void destinationWithoutOverrideReturnsToMainWeather()
	{
		GpuSkyExtension extension = extension(new MutableConfig());
		RegionOverride origin = override("Snow", 12586, GpuSkyExtensionConfig.RegionWeatherOverride.SNOW);
		publish(extension, origin);
		RegionContext destination = new RegionContext().update(GameState.LOGGED_IN, 12664, false);
		RegionOverride selected = GpuSkyExtensionPlugin.activeRegionOverride(destination, Arrays.asList(origin), false);
		assertNull(selected);
		publish(extension, selected);
		assertFalse(extension.regionActive());
		assertEquals(GpuSkyExtensionConfig.WeatherEffect.DAY, extension.activeWeather());
	}

	@Test
	public void disablingOverridesImmediatelyUsesMainWeather()
	{
		MutableConfig config = new MutableConfig();
		GpuSkyExtension extension = extension(config);
		RegionOverride origin = override("Snow", 12586, GpuSkyExtensionConfig.RegionWeatherOverride.SNOW);
		publish(extension, origin);
		config.disabled = true;
		assertFalse(extension.regionActive());
		assertEquals(GpuSkyExtensionConfig.WeatherEffect.DAY, extension.activeWeather());
		RegionContext context = new RegionContext().update(GameState.LOGGED_IN, 12586, false);
		assertNull(GpuSkyExtensionPlugin.activeRegionOverride(context, Arrays.asList(origin), true));
	}

	@Test
	public void regionSnapshotIdentityRejectsOlderDestinationsEvenAfterReturning()
	{
		RegionContext first = new RegionContext().update(GameState.LOGGED_IN, 12586, false);
		assertSame(first, first.update(GameState.LOGGED_IN, 12586, false));
		RegionContext loading = first.update(GameState.LOADING, -1, false);
		RegionContext second = loading.update(GameState.LOGGED_IN, 12664, false);
		assertNotSame(first, loading);
		assertNotSame(first, second);
		assertNotSame(first, second.update(GameState.LOGGED_IN, 12586, false));
		assertNotSame(first, first.update(GameState.LOGGED_IN, 12586, true));
	}

	@Test
	public void logoutResetsRegionAndRaidButTemporaryDisconnectDoesNot()
	{
		RegionContext raid = new RegionContext().update(GameState.LOGGED_IN, 12889, true);
		for (GameState state : new GameState[] {GameState.LOADING, GameState.HOPPING, GameState.CONNECTION_LOST})
		{
			RegionContext pending = raid.update(state, -1, false);
			assertFalse(pending.ready);
			assertEquals(12889, pending.regionId);
			assertTrue(pending.inChambersOfXeric);
		}
		RegionContext loggedOut = raid.update(GameState.LOGIN_SCREEN, -1, false);
		assertTrue(loggedOut.ready);
		assertEquals(-1, loggedOut.regionId);
		assertFalse(loggedOut.inChambersOfXeric);
		assertFalse(new RegionContext().ready);
	}

	@Test
	public void chambersOverrideStillRequiresEnabledOlmAssignment()
	{
		RegionOverride cox = override("Cox", RegionOverride.COX_OLM_REGION_ID,
			GpuSkyExtensionConfig.RegionWeatherOverride.DISABLED);
		RegionContext room = new RegionContext().update(GameState.LOGGED_IN, 12664, true);
		assertSame(cox, GpuSkyExtensionPlugin.activeRegionOverride(room, Arrays.asList(cox), false));
		cox.enabled = false;
		assertNull(GpuSkyExtensionPlugin.activeRegionOverride(room, Arrays.asList(cox), false));
		cox.enabled = true;
		cox.regionIds = "";
		assertNull(GpuSkyExtensionPlugin.activeRegionOverride(room, Arrays.asList(cox), false));
	}

	private static GpuSkyExtension extension(GpuSkyExtensionConfig config)
	{
		// Selection must not query the player's live coordinates or require an OpenGL context.
		GpuSkyExtension extension = new GpuSkyExtension(null, config, null, () -> {});
		extension.setMainWeather(GpuSkyExtensionConfig.WeatherEffect.DAY);
		return extension;
	}

	private static RegionOverride override(String name, int regionId, GpuSkyExtensionConfig.RegionWeatherOverride weather)
	{
		RegionOverride override = RegionOverride.create(name, 110, 160, 0, 110, 50, 70, 70, 50, 70);
		override.regionIds = Integer.toString(regionId);
		override.weather = weather;
		return override;
	}

	private static void publish(GpuSkyExtension extension, RegionOverride override)
	{
		extension.setRegionOverride(override != null,
			override == null ? GpuSkyExtensionConfig.RegionWeatherOverride.MAIN : override.weather,
			100, false, true, 110, 160, 0, 110, true, 50, 70, 70, 50, 70);
	}

	private static final class MutableConfig implements GpuSkyExtensionConfig
	{
		private boolean disabled;

		@Override
		public boolean disableRegionOverrides()
		{
			return disabled;
		}
	}
}
