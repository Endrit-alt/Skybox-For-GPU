package com.endrit.gpusky.water;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.plugins.gpu.api.GpuApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The water renderer (formerly the standalone "Water for GPU" plugin), owned by
 * the Skybox plugin. It registers its own GPU extension next to the skybox one;
 * the owning plugin calls start/stop and registers this object on the event bus.
 */
public final class WaterFeature
{
	private static final Logger log = LoggerFactory.getLogger(WaterFeature.class);
	private static final String MIGRATED_KEY = "waterSettingsMigrated";
	/**
	 * Every water setting: config key and its getter. Kept as an explicit list
	 * (Plugin Hub does not allow reflection). Add new WaterConfig items here.
	 */
	private static final Map<String, Function<WaterConfig, Object>> SETTINGS = new LinkedHashMap<>();
	static
	{
		setting("animatedWater", WaterConfig::animatedWater);
		setting("animationSpeed", WaterConfig::animationSpeed);
		setting("waterBrightnessAdjustment", WaterConfig::waterBrightnessAdjustment);
		setting("waterColor", WaterConfig::waterColor);
		setting("skyReflections", WaterConfig::skyReflections);
		setting("skyReflectionStrength", WaterConfig::skyReflectionStrength);
		setting("sunGlintStrength", WaterConfig::sunGlintStrength);
		setting("glintFollowsCamera", WaterConfig::glintFollowsCamera);
		setting("reflectionNoise", WaterConfig::reflectionNoise);
		setting("waveStrength", WaterConfig::waveStrength);
		setting("shoreFollowingWaves", WaterConfig::shoreFollowingWaves);
		setting("surfaceDepthStrength", WaterConfig::surfaceDepth);
		setting("softSurfaceShadows", WaterConfig::softSurfaceShadows);
		setting("openRippleStrength", WaterConfig::openRippleStrength);
		setting("surfaceGrainStrength", WaterConfig::surfaceGrainStrength);
		setting("shoreFoam", WaterConfig::shoreFoam);
		setting("swashFoam", WaterConfig::swashFoam);
		setting("swashFoamOpacity", WaterConfig::swashFoamOpacity);
		setting("swashHazeStyle", WaterConfig::swashHazeStyle);
		setting("contactFoamOpacity", WaterConfig::contactFoamOpacity);
		setting("classicFoamWidth", WaterConfig::classicFoamWidth);
		setting("classicFoamSoftness", WaterConfig::classicFoamSoftness);
		setting("classicSoftOuterLayers", WaterConfig::classicSoftOuterLayers);
		setting("bubbles", WaterConfig::bubbles);
		setting("bubbleFrequency", WaterConfig::bubbleFrequency);
		setting("bubbleOpacity", WaterConfig::bubbleOpacity);
	}
	/** Config keys of all water settings (to tell them apart from skybox settings). */
	public static final Set<String> KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(SETTINGS.keySet()));
	/** A plain WaterConfig: its methods return the interface defaults. */
	private static final WaterConfig DEFAULTS = new WaterConfig() {};

	private static void setting(String key, Function<WaterConfig, Object> getter)
	{
		SETTINGS.put(key, getter);
	}

	private final Client client;
	private final ClientThread clientThread;
	private final GpuApi gpuApi;
	private final PluginManager pluginManager;
	private final WaterConfig config;
	private final Plugin owner;
	private final AtomicInteger lifecycle = new AtomicInteger();
	private volatile ModernAnimatedWaterExtension extension;
	private volatile boolean extensionRegistered;
	private volatile java.util.function.Supplier<java.awt.Color> waterColorOverride = () -> null;
	private volatile java.util.function.BooleanSupplier instantTransitions = () -> false;

	/** When true, region water color changes switch instantly instead of fading with the sky. */
	public void setInstantTransitions(java.util.function.BooleanSupplier source)
	{
		instantTransitions = source == null ? () -> false : source;
		ModernAnimatedWaterExtension current = extension;
		if (current != null) current.setInstantTransitions(instantTransitions);
	}

	/** Source of a per-region water color (null result = use the Water color setting). */
	public void setWaterColorOverride(java.util.function.Supplier<java.awt.Color> source)
	{
		waterColorOverride = source == null ? () -> null : source;
		ModernAnimatedWaterExtension current = extension;
		if (current != null) current.setWaterColorOverride(waterColorOverride);
	}

	public WaterFeature(Client client, ClientThread clientThread, GpuApi gpuApi, PluginManager pluginManager,
		WaterConfig config, Plugin owner)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.gpuApi = gpuApi;
		this.pluginManager = pluginManager;
		this.config = config;
		this.owner = owner;
	}

	/** One-time copy of settings saved by the old standalone Water plugin into {@code group}. */
	public static void migrateLegacySettings(ConfigManager configManager, String group)
	{
		if (configManager.getConfiguration(group, MIGRATED_KEY) != null) return;
		int copied = 0;
		for (String key : KEYS)
		{
			String legacy = configManager.getConfiguration(WaterConfig.LEGACY_GROUP, key);
			if (legacy != null && configManager.getConfiguration(group, key) == null)
			{
				configManager.setConfiguration(group, key, legacy);
				copied++;
			}
		}
		configManager.setConfiguration(group, MIGRATED_KEY, true);
		if (copied > 0) log.info("Copied {} settings from the standalone Water plugin", copied);
	}

	/**
	 * Writes the default of every water setting that has no saved value. RuneLite's
	 * own default pass only covers settings declared directly on the plugin's config
	 * interface, so without this the panel shows inherited water settings as empty
	 * (black color, 0, unticked) even though the water itself uses the defaults.
	 */
	public static void saveMissingDefaults(ConfigManager configManager, String group, WaterConfig config)
	{
		// The first version of the water color used a different default; a value still
		// at that old default means "never changed", so move it to the new default.
		java.awt.Color savedColor = configManager.getConfiguration(group, "waterColor", java.awt.Color.class);
		if (WaterConfig.OLD_DEFAULT_WATER_COLOR.equals(savedColor))
		{
			configManager.setConfiguration(group, "waterColor", WaterConfig.DEFAULT_WATER_COLOR);
		}
		for (Map.Entry<String, Function<WaterConfig, Object>> setting : SETTINGS.entrySet())
		{
			if (configManager.getConfiguration(group, setting.getKey()) != null) continue;
			Object value = setting.getValue().apply(DEFAULTS);
			if (value != null) configManager.setConfiguration(group, setting.getKey(), value);
		}
	}

	/** Sets every water setting back to its default (used by the panel's Reset). */
	public static void resetToDefaults(ConfigManager configManager, String group, WaterConfig config)
	{
		for (String key : KEYS)
		{
			if (configManager.getConfiguration(group, key) != null) configManager.unsetConfiguration(group, key);
		}
		saveMissingDefaults(configManager, group, config);
	}

	public void start()
	{
		log.info("Starting water");
		ModernAnimatedWaterExtension nextExtension = new ModernAnimatedWaterExtension(client, config);
		nextExtension.setWaterColorOverride(waterColorOverride);
		nextExtension.setInstantTransitions(instantTransitions);
		extension = nextExtension;
		int generation = lifecycle.get();
		clientThread.invokeLater(() ->
		{
			if (generation != lifecycle.get() || extension != nextExtension)
			{
				return true;
			}
			DrawCallbacks callbacks = client.getDrawCallbacks();
			boolean gpuActive = pluginManager.getPlugins().stream()
				.anyMatch(plugin -> plugin instanceof GpuPlugin && pluginManager.isPluginActive(plugin));
			if (!(gpuActive && client.isGpu() && callbacks != null))
			{
				return false;
			}
			gpuApi.registerExtension(owner, nextExtension);
			extensionRegistered = true;
			// Late registration: the API recompiles shaders but does not issue a context callback.
			nextExtension.onContextCreate();
			nextExtension.attachDrawCallbacks();
			log.info("Registered water extension with draw callbacks {}", callbacks.getClass().getName());
			return true;
		});
	}

	public void stop()
	{
		lifecycle.incrementAndGet();
		ModernAnimatedWaterExtension current = extension;
		boolean registered = extensionRegistered;
		extension = null;
		extensionRegistered = false;
		clientThread.invokeLater(() ->
		{
			if (current != null) current.detachDrawCallbacks();
			if (registered)
			{
				gpuApi.unregisterExtension(owner, current);
			}
			if (current != null)
			{
				current.close();
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		ModernAnimatedWaterExtension current = extension;
		if (current == null) return;
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR)
		{
			current.clearShoreHistory();
		}
		if (state == GameState.LOADING || state == GameState.LOGGED_IN)
		{
			current.markShoreMaskDirty();
		}
	}

	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded event)
	{
		ModernAnimatedWaterExtension current = extension;
		if (current != null && event.getWorldView() == client.getTopLevelWorldView())
		{
			current.markShoreMaskDirty();
		}
	}
}
