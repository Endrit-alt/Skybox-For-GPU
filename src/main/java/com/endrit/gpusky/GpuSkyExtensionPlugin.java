package com.endrit.gpusky;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import com.endrit.gpusky.water.WaterFeature;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.plugins.gpu.api.GpuApi;
import net.runelite.client.plugins.gpu.api.GpuExtension;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Skybox for GPU",
	internalName = "skybox-for-gpu",
	description = "Custom panoramic skies, sky lighting, and world-space weather",
	tags = {"gpu", "skybox", "weather", "lighting"}
)
@PluginDependency(GpuPlugin.class)
public class GpuSkyExtensionPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(GpuSkyExtensionPlugin.class);
	private static final String FALLBACK_SKYBOX = "weather/presets/Daylight.jpg";
	private static final String REGION_OVERRIDES_KEY = "regionOverrides";
	private static final String COX_OVERRIDE_SEEDED_KEY = "coxOverrideSeeded";
	/** Panorama exposure default (was 110 until 2026-11-15). */
	static final int DEFAULT_EXPOSURE = 140;
	private static final int OLD_DEFAULT_EXPOSURE = 110;
	/** Set per profile once the 110 -> 140 exposure default change has been applied to it. */
	private static final String EXPOSURE_140_KEY = "exposureDefault140Applied";
	private static final long MEMORY_NOTICE_NANOS = 20_000_000_000L;
	private static final int MAX_INSTALL_ATTEMPTS = 3;
	private volatile long memoryNoticeUntil;
	/** Loader thread: incomplete bundled-asset installs this start. */
	private int installFailures;
	/** Raw copy of overrides that could not be parsed, kept so nothing is lost. */
	static final String UNREADABLE_OVERRIDES_KEY = "regionOverridesUnreadable";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GpuApi gpuApi;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbacks;

	@Inject
	private GpuSkyExtensionConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private EventBus eventBus;

	@Inject
	private net.runelite.client.ui.components.colorpicker.ColorPickerManager colorPickerManager;

	private final AtomicInteger lifecycle = new AtomicInteger();
	private final Object assetDirectoryLock = new Object();
	private final AtomicInteger eightKPreloadsInProgress = new AtomicInteger();
	private final AtomicInteger fourKPreloadsInProgress = new AtomicInteger();
	private ScheduledExecutorService loader;
	private ScheduledFuture<?> watcher;
	private volatile GpuSkyExtension extension;
	private volatile AssetLoadSession loadSession;
	private volatile Filepath skyboxDirectory;
	// Client thread only, so registration and unregistration are decided in one place.
	private GpuSkyExtension registeredExtension;
	// Closed (inert) extensions, skybox and water, left registered because GPU had already
	// stopped (see shutDown). Client thread only; unregistered by the next registration.
	private final List<GpuExtension> staleExtensions = new ArrayList<>();
	private volatile boolean waitingForSkyboxContext;
	private String loadedOverridesJson;
	/** Profile the in-memory overrides were read from; edits are refused while it is not the active one. */
	private long overridesProfile = Long.MIN_VALUE;
	/** Saved overrides could not be parsed: saving is refused until the user starts over. */
	private volatile boolean overridesUnreadable;
	/** A panorama was loaded below the selected resolution because of RuneLite's memory limit. */
	private volatile boolean resolutionReducedForMemory;
	private final Set<String> reducedResolutionWarnings = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private volatile RegionContext regionContext = new RegionContext();
	private volatile List<RegionOverride> regionOverrides = Collections.emptyList();
	private volatile RegionOverridePanel regionOverridePanel;
	private NavigationButton navigationButton;
	private SkyboxLoadingOverlay loadingOverlay;
	private volatile TextureQualitySetup textureQualitySetup;
	private WaterFeature water;
	/** Region water color published with the skybox (null = main setting). */
	private volatile java.awt.Color publishedRegionWaterColor;

	@Provides
	GpuSkyExtensionConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpuSkyExtensionConfig.class);
	}

	@Override
	protected void startUp()
	{
		migrateTextureResolution();
		resetTextureQualitySetup();
		resolutionReducedForMemory = false;
		refreshRegionOverrides();
		waitingForSkyboxContext = config.preloadSkyboxPresets();
		loadingOverlay = new SkyboxLoadingOverlay(client, this);
		loadingOverlay.install();
		int registrationGeneration = lifecycle.get();
		extension = new GpuSkyExtension(client, config, renderCallbacks, () ->
		{
			if (registrationGeneration == lifecycle.get()) onGpuContextChanged();
		});
		GpuSkyExtension currentExtension = extension;
		clientThread.invokeLater(() ->
		{
			if (registrationGeneration != lifecycle.get() || currentExtension != extension)
			{
				return true;
			}
			DrawCallbacks callbacks = client.getDrawCallbacks();
			refreshRegionContext();
			if (!canRegisterWithGpu(gpuPluginActive(), client.isGpu(), callbacks))
			{
				return false;
			}
			// GPU is running now, so leftovers from a shutdown while it was off can go.
			for (GpuExtension stale : staleExtensions)
			{
				gpuApi.unregisterExtension(this, stale);
			}
			staleExtensions.clear();
			gpuApi.registerExtension(this, currentExtension);
			registeredExtension = currentExtension;
			// Late API registration does not issue a context callback on this fork.
			currentExtension.onContextCreate();
			log.debug("Skybox GPU extension initialized with draw callbacks {}", callbacks.getClass().getName());
			return true;
		});
		int navigationGeneration = lifecycle.get();
		SwingUtilities.invokeLater(() ->
		{
			if (navigationGeneration == lifecycle.get())
			{
				syncNavigationVisibility();
			}
		});
		loader = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "skybox-for-gpu-loader");
			thread.setDaemon(true);
			return thread;
		});
		AssetLoadSession session = new AssetLoadSession();
		loadSession = session;
		installFailures = 0;
		watcher = loader.scheduleWithFixedDelay(() -> poll(session), 0, 1, TimeUnit.SECONDS);
		// Water registers after the skybox extension (queued after it on the client thread).
		WaterFeature.migrateLegacySettings(configManager, GpuSkyExtensionConfig.GROUP);
		WaterFeature.saveMissingDefaults(configManager, GpuSkyExtensionConfig.GROUP, config);
		water = new WaterFeature(client, clientThread, gpuApi, pluginManager, config, this, staleExtensions);
		water.setWaterColorOverride(() -> publishedRegionWaterColor);
		water.setInstantTransitions(config::instantTransitions);
		eventBus.register(water);
		water.start();
	}

	@Override
	public void resetConfiguration()
	{
		// The panel's Reset only resets settings declared directly on
		// GpuSkyExtensionConfig; reset the inherited water settings too.
		WaterFeature.resetToDefaults(configManager, GpuSkyExtensionConfig.GROUP, config);
	}

	/** The former "8k Texture Resolution" checkbox becomes the Texture resolution dropdown. */
	private void migrateTextureResolution()
	{
		String legacy = configManager.getConfiguration(GpuSkyExtensionConfig.GROUP, GpuSkyExtensionConfig.LEGACY_EIGHT_K_KEY);
		if (legacy == null) return;
		if (Boolean.parseBoolean(legacy) && configManager.getConfiguration(GpuSkyExtensionConfig.GROUP, "textureResolution") == null)
		{
			configManager.setConfiguration(GpuSkyExtensionConfig.GROUP, "textureResolution",
				GpuSkyExtensionConfig.TextureResolution.RES_8K);
		}
		configManager.unsetConfiguration(GpuSkyExtensionConfig.GROUP, GpuSkyExtensionConfig.LEGACY_EIGHT_K_KEY);
	}

	private void resetTextureQualitySetup()
	{
		TextureQualitySetup previous = textureQualitySetup;
		if (previous != null) previous.close();
		boolean completed = Boolean.parseBoolean(configManager.getConfiguration(
			GpuSkyExtensionConfig.GROUP, TextureQualitySetup.COMPLETED_KEY));
		long profileId = configManager.getProfile().getId();
		textureQualitySetup = new TextureQualitySetup(client.getCanvas(), completed, config.textureResolution(),
			selection ->
			{
				if (configManager.getProfile().getId() != profileId) return;
				configManager.setConfiguration(GpuSkyExtensionConfig.GROUP, "textureResolution", selection);
				configManager.setConfiguration(GpuSkyExtensionConfig.GROUP, TextureQualitySetup.COMPLETED_KEY, true);
			}, this::requestAssetRefresh);
	}

	/** Short name of the selected texture resolution, for the loading overlay. */
	String textureResolutionName()
	{
		return config.textureResolution().shortName();
	}

	static boolean canRegisterWithGpu(boolean gpuPluginActive, boolean gpuRendering, DrawCallbacks callbacks)
	{
		// Other plugins can wrap the callbacks while the built-in GPU still owns rendering.
		return gpuPluginActive && gpuRendering && callbacks != null;
	}

	/** The built-in GPU plugin itself is on. Another renderer (117 HD) also sets the GPU flag and callbacks. */
	private boolean gpuPluginActive()
	{
		return pluginManager.getPlugins().stream()
			.anyMatch(plugin -> plugin instanceof GpuPlugin && pluginManager.isPluginActive(plugin));
	}

	private synchronized void refreshRegionOverrides()
	{
		// Read the profile first: if it switches before the JSON is read, the list is labelled
		// with the old profile and edits stay refused until the switch handler reloads it.
		long profile = profileId();
		String json = configManager.getConfiguration(GpuSkyExtensionConfig.GROUP, REGION_OVERRIDES_KEY);
		if (json == null)
		{
			overridesUnreadable = false;
			overridesProfile = profile;
			applyExposureDefault(Collections.emptyList());
			saveRegionOverrides(DefaultRegionOverrides.create(config));
			configManager.setConfiguration(GpuSkyExtensionConfig.GROUP, COX_OVERRIDE_SEEDED_KEY, true);
			reloadPanel();
			return;
		}
		if (json.equals(loadedOverridesJson))
		{
			return;
		}

		List<RegionOverride> parsed = new ArrayList<>();
		try
		{
			RegionOverride[] values = gson.fromJson(json, RegionOverride[].class);
			if (values != null)
			{
				for (int i = 0; i < values.length; i++)
				{
					if (values[i] != null)
					{
						parsed.add(normalizeRegionOverride(values[i], i));
					}
				}
			}
		}
		catch (RuntimeException ex)
		{
			loadedOverridesJson = json;
			// Keep a copy and pause saving, so the next edit cannot replace data that may be recoverable.
			if (!json.equals(configManager.getConfiguration(GpuSkyExtensionConfig.GROUP, UNREADABLE_OVERRIDES_KEY)))
			{
				configManager.setConfiguration(GpuSkyExtensionConfig.GROUP, UNREADABLE_OVERRIDES_KEY, json);
			}
			overridesUnreadable = true;
			overridesProfile = profile;
			regionOverrides = Collections.emptyList();
			log.warn("Unable to read region overrides; kept a copy in '{}' and paused saving until the side panel's "
				+ "Start over button is used", UNREADABLE_OVERRIDES_KEY, ex);
			reloadPanel();
			requestAssetRefresh();
			return;
		}
		overridesUnreadable = false;
		overridesProfile = profile;

		boolean changed = applyExposureDefault(parsed);
		if (!Boolean.parseBoolean(configManager.getConfiguration(
			GpuSkyExtensionConfig.GROUP, COX_OVERRIDE_SEEDED_KEY)))
		{
			boolean hasCoxOverride = parsed.stream().anyMatch(RegionOverride::matchesChambersOfXeric);
			if (!hasCoxOverride)
			{
				parsed.add(DefaultRegionOverrides.createCox(config));
			}
			configManager.setConfiguration(GpuSkyExtensionConfig.GROUP, COX_OVERRIDE_SEEDED_KEY, true);
			changed = true;
		}
		if (changed)
		{
			saveRegionOverrides(parsed);
			reloadPanel();
			return;
		}

		loadedOverridesJson = json;
		regionOverrides = Collections.unmodifiableList(parsed);
		reloadPanel();
	}

	/**
	 * Once per profile: moves the old exposure default (110) to the new one (140), in the main
	 * setting if it was saved as 110 and in every override still at 110. Other values are kept.
	 * Returns whether an override changed (the caller saves the list).
	 */
	private boolean applyExposureDefault(List<RegionOverride> overrides)
	{
		if (Boolean.parseBoolean(configManager.getConfiguration(GpuSkyExtensionConfig.GROUP, EXPOSURE_140_KEY)))
		{
			return false;
		}
		configManager.setConfiguration(GpuSkyExtensionConfig.GROUP, EXPOSURE_140_KEY, true);
		if (Integer.toString(OLD_DEFAULT_EXPOSURE).equals(
			configManager.getConfiguration(GpuSkyExtensionConfig.GROUP, "exposure")))
		{
			// Unset rather than write 140, so it follows the default from now on.
			configManager.unsetConfiguration(GpuSkyExtensionConfig.GROUP, "exposure");
		}
		return updateOldExposure(overrides);
	}

	/** Sets every override still at the old default exposure to the new one. */
	static boolean updateOldExposure(List<RegionOverride> overrides)
	{
		boolean changed = false;
		for (RegionOverride override : overrides)
		{
			if (override.exposure == OLD_DEFAULT_EXPOSURE)
			{
				override.exposure = DEFAULT_EXPOSURE;
				changed = true;
			}
		}
		return changed;
	}

	private void reloadPanel()
	{
		RegionOverridePanel panel = regionOverridePanel;
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::reload);
		}
	}

	/** The active profile; edits, imports and recordings remember it and are dropped after a switch. */
	long profileId()
	{
		ConfigProfile profile = configManager.getProfile();
		return profile == null ? -1 : profile.getId();
	}

	boolean overridesUnreadable()
	{
		return overridesUnreadable;
	}

	/** The edit was made for the active profile, and the in-memory list was read from it. */
	private boolean editable(long expectedProfile)
	{
		return expectedProfile == profileId() && expectedProfile == overridesProfile;
	}

	/** Replaces unreadable saved overrides with the default list; the raw copy stays in its backup key. */
	synchronized void discardUnreadableOverrides(long expectedProfile)
	{
		if (!overridesUnreadable || !editable(expectedProfile))
		{
			return;
		}
		overridesUnreadable = false;
		saveRegionOverrides(DefaultRegionOverrides.create(config));
		configManager.setConfiguration(GpuSkyExtensionConfig.GROUP, COX_OVERRIDE_SEEDED_KEY, true);
		reloadPanel();
	}

	synchronized List<RegionOverride> regionOverrides()
	{
		List<RegionOverride> snapshot = new ArrayList<>();
		for (RegionOverride override : regionOverrides)
		{
			snapshot.add(override.copy());
		}
		return snapshot;
	}

	/** Adds an override, or returns null when the profile changed or saving is paused. */
	synchronized RegionOverride addRegionOverride(long expectedProfile)
	{
		if (!editable(expectedProfile))
		{
			return null;
		}
		List<RegionOverride> updated = regionOverrides();
		RegionOverride added = createRegionOverride("Region override " + (updated.size() + 1));
		updated.add(added);
		return saveRegionOverrides(updated) ? added.copy() : null;
	}

	/**
	 * Applies one field change to the latest saved copy of an override, found by ID, so an
	 * editor never writes back an older copy of the other fields. Ignored after a profile
	 * switch: the same ID (for example builtin-tfa) can exist in several profiles.
	 */
	synchronized boolean updateRegionOverride(String id, long expectedProfile, Consumer<RegionOverride> change)
	{
		if (!editable(expectedProfile))
		{
			return false;
		}
		List<RegionOverride> updated = regionOverrides();
		for (int i = 0; i < updated.size(); i++)
		{
			RegionOverride target = updated.get(i);
			if (target.id.equals(id))
			{
				change.accept(target);
				updated.set(i, normalizeRegionOverride(target, i));
				return saveRegionOverrides(updated);
			}
		}
		return false;
	}

	synchronized void removeRegionOverride(String id, long expectedProfile)
	{
		if (!editable(expectedProfile))
		{
			return;
		}
		List<RegionOverride> updated = regionOverrides();
		updated.removeIf(override -> override.id.equals(id));
		saveRegionOverrides(updated);
	}

	synchronized String regionOverrideUsing(int regionId, String excludedId)
	{
		for (RegionOverride override : regionOverrides)
		{
			if (!override.id.equals(excludedId) && override.parsedRegionIds().contains(regionId))
			{
				return override.name;
			}
		}
		return null;
	}

	synchronized void reassignRegion(int regionId, String targetId, long expectedProfile)
	{
		if (!editable(expectedProfile))
		{
			return;
		}
		List<RegionOverride> updated = regionOverrides();
		for (RegionOverride override : updated)
		{
			override.regionIds = updateRegionAssignment(
				override.regionIds, regionId, override.id.equals(targetId));
		}
		saveRegionOverrides(updated);
	}

	private static String updateRegionAssignment(String value, int regionId, boolean include)
	{
		List<String> ids = new ArrayList<>();
		String current = Integer.toString(regionId);
		if (value != null)
		{
			for (String token : value.trim().split("[,\\s]+"))
			{
				// By number, like conflict detection: "012889" and "+12889" are region 12889 too.
				if (!token.isEmpty() && !RegionOverride.isRegion(token, regionId))
				{
					ids.add(token);
				}
			}
		}
		if (include)
		{
			ids.add(current);
		}
		StringBuilder formatted = new StringBuilder();
		for (int i = 0; i < ids.size(); i++)
		{
			if (i > 0)
			{
				formatted.append(i % 2 == 0 ? '\n' : ' ');
			}
			formatted.append(ids.get(i)).append(',');
		}
		return formatted.toString();
	}

	private boolean saveRegionOverrides(List<RegionOverride> overrides)
	{
		if (overridesUnreadable)
		{
			log.warn("Not saving region overrides: the saved list could not be read (a copy is in '{}'). "
				+ "Use Start over in the side panel to replace it.", UNREADABLE_OVERRIDES_KEY);
			return false;
		}
		List<RegionOverride> normalized = new ArrayList<>();
		for (int i = 0; i < overrides.size(); i++)
		{
			normalized.add(normalizeRegionOverride(overrides.get(i), i));
		}
		String json = gson.toJson(normalized.toArray(new RegionOverride[0]));
		configManager.setConfiguration(GpuSkyExtensionConfig.GROUP, REGION_OVERRIDES_KEY, json);
		loadedOverridesJson = json;
		regionOverrides = Collections.unmodifiableList(normalized);
		requestAssetRefresh();
		return true;
	}

	private RegionOverride createRegionOverride(String name)
	{
		return RegionOverride.create(name, config.horizontalFov(), config.yawOffset(),
			config.pitchOffset(), config.exposure(), config.worldLighting(), config.rimLighting(),
			config.backlighting(), config.skyboxTint(), config.atmosphere());
	}

	private RegionOverride normalizeRegionOverride(RegionOverride override, int index)
	{
		return override.normalized(index);
	}

	/**
	 * Imports a panorama and assigns it to the override by ID. The assignment is made here,
	 * not by the editor that started it, so a panel rebuilt in the meantime cannot lose it;
	 * it is skipped (with an error) if the profile changed during the import.
	 */
	CompletableFuture<String> importSkybox(Filepath selected, String overrideId, long expectedProfile)
	{
		AssetLoadSession session = loadSession;
		ScheduledExecutorService currentLoader = loader;
		int maxWidth = config.textureResolution().maxWidth();
		if (session == null || currentLoader == null)
		{
			return CompletableFuture.failedFuture(new CancellationException("Skybox plugin is stopped"));
		}
		try
		{
			return CompletableFuture.supplyAsync(() ->
			{
				session.checkActive();
				String path;
				try
				{
					path = importSkybox(selected, overrideId, maxWidth, session);
				}
				catch (IOException ex)
				{
					throw new CompletionException(ex);
				}
				session.checkActive();
				if (!updateRegionOverride(overrideId, expectedProfile, override -> override.skyboxFile = path))
				{
					throw new CompletionException(new IOException("The image was saved as " + path
						+ ", but not assigned: the profile or the override changed while it was importing."));
				}
				return path;
			}, currentLoader);
		}
		catch (RejectedExecutionException ex)
		{
			return CompletableFuture.failedFuture(ex);
		}
	}

	private String importSkybox(Filepath selected, String overrideId, int maxWidth, AssetLoadSession session)
		throws IOException
	{
		ImageLoader.validatePanorama(selected, maxWidth);
		Filepath overrideDirectory = ensureSkyboxDirectory().joinSegment("overrides");
		overrideDirectory.createDirectories();
		String fileName = selected.getFileName().replaceAll("[^A-Za-z0-9._-]", "_");
		String prefix = overrideId.replace("-", "");
		prefix = prefix.substring(0, Math.min(8, prefix.length()));
		Filepath target = overrideDirectory.joinSegment(prefix + "-" + fileName);
		Filepath temporary = overrideDirectory.createTempFile("skybox-import-", ".tmp");
		try
		{
			selected.copyTo(temporary, StandardCopyOption.REPLACE_EXISTING);
			ImageLoader.readPanorama(temporary, maxWidth, message -> { });
			session.checkActive();
			try
			{
				temporary.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException ex)
			{
				temporary.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally
		{
			temporary.deleteIfExists();
		}
		return "overrides/" + target.getFileName();
	}

	int currentRegionId()
	{
		return regionContext.regionId;
	}

	Filepath skyboxDirectory()
	{
		return skyboxDirectory;
	}

	private Filepath ensureSkyboxDirectory() throws IOException
	{
		synchronized (assetDirectoryLock)
		{
			if (skyboxDirectory == null)
			{
				// Initialize storage on the asset worker, never the client thread.
				Filepath directory = getPluginDirectory();
				directory.createDirectories();
				// Presets and weather textures ship with the plugin; add any that are missing.
				boolean complete = BundledAssets.install(directory);
				if (!complete && Thread.currentThread().isInterrupted())
				{
					// The plugin is stopping mid-copy: the next start installs the rest.
					throw new CancellationException("Bundled asset installation interrupted");
				}
				if (!complete && ++installFailures < MAX_INSTALL_ATTEMPTS)
				{
					throw new IOException("Some bundled assets could not be installed; trying again");
				}
				skyboxDirectory = directory;
			}
			return skyboxDirectory;
		}
	}

	private void syncNavigationVisibility()
	{
		if (config.showOverridesPanel())
		{
			addNavigation();
		}
		else
		{
			removeNavigation();
		}
	}

	private void addNavigation()
	{
		if (navigationButton != null)
		{
			return;
		}
		regionOverridePanel = new RegionOverridePanel(this);
		navigationButton = NavigationButton.builder()
			.tooltip("Skybox for GPU overrides")
			.icon(createNavigationIcon())
			.priority(6)
			.panel(regionOverridePanel)
			.build();
		clientToolbar.addNavigation(navigationButton);
	}

	private void removeNavigation()
	{
		NavigationButton button = navigationButton;
		RegionOverridePanel panel = regionOverridePanel;
		navigationButton = null;
		regionOverridePanel = null;
		if (panel != null)
		{
			panel.dispose();
		}
		if (button != null)
		{
			clientToolbar.removeNavigation(button);
		}
	}

	private static BufferedImage createNavigationIcon()
	{
		return ImageUtil.loadImageResource(GpuSkyExtensionPlugin.class, "sidebar_icon.png");
	}

	@Override
	protected void shutDown()
	{
		lifecycle.incrementAndGet();
		TextureQualitySetup currentSetup = textureQualitySetup;
		textureQualitySetup = null;
		if (currentSetup != null) currentSetup.close();
		WaterFeature currentWater = water;
		water = null;
		if (currentWater != null)
		{
			eventBus.unregister(currentWater);
			currentWater.stop();
		}
		AssetLoadSession session = loadSession;
		loadSession = null;
		if (session != null)
		{
			session.cancel();
		}
		eightKPreloadsInProgress.set(0);
		fourKPreloadsInProgress.set(0);
		waitingForSkyboxContext = false;
		SkyboxLoadingOverlay currentLoadingOverlay = loadingOverlay;
		loadingOverlay = null;
		if (currentLoadingOverlay != null)
		{
			currentLoadingOverlay.uninstall();
		}
		ScheduledFuture<?> currentWatcher = watcher;
		if (currentWatcher != null)
		{
			currentWatcher.cancel(true);
			watcher = null;
		}
		ScheduledExecutorService currentLoader = loader;
		if (currentLoader != null)
		{
			currentLoader.shutdownNow();
			loader = null;
		}
		GpuSkyExtension currentExtension = extension;
		extension = null;
		if (currentExtension != null)
		{
			// Decided on the client thread, where registration happens, so a registration
			// that is still pending is cancelled by its lifecycle check and one that already
			// ran is always seen here.
			clientThread.invokeLater(() ->
			{
				currentExtension.close();
				if (registeredExtension != currentExtension)
				{
					return;
				}
				registeredExtension = null;
				// Same readiness test as registration: with the GPU plugin off (even if another
				// renderer is drawing), unregistering would queue a recompile in the wrong context.
				if (canRegisterWithGpu(gpuPluginActive(), client.isGpu(), client.getDrawCallbacks()))
				{
					gpuApi.unregisterExtension(this, currentExtension);
				}
				else
				{
					// unregisterExtension queues a shader recompile, and the API does not check
					// that its GL context still exists when that task runs. With GPU already
					// stopped it would call GL without a context, so leave this closed (inert)
					// extension registered and unregister it on the next startup that finds
					// GPU running. PARTIAL MITIGATION: if GPU stops after this check but before
					// the queued recompile runs (within about one client tick), the API can
					// still recompile without a context. The complete fix is a context check
					// inside the API's recompile task (to raise with Adam; API left unchanged).
					staleExtensions.add(currentExtension);
				}
			});
		}
		loadedOverridesJson = null;
		publishedRegionWaterColor = null;
		regionContext = new RegionContext();
		regionOverrides = Collections.emptyList();
		NavigationButton button = navigationButton;
		RegionOverridePanel panel = regionOverridePanel;
		navigationButton = null;
		regionOverridePanel = null;
		if (button != null || panel != null)
		{
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.dispose();
				}
				if (button != null)
				{
					clientToolbar.removeNavigation(button);
				}
			});
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		refreshRegionContext();
	}

	private void refreshRegionContext()
	{
		GameState state = client.getGameState();
		// Main-world position: on a boat this is the sea region, not the boat's own.
		LocalPoint local = state == GameState.LOGGED_IN ? MainWorldLocation.ofPlayer(client) : null;
		int nextRegionId = local == null ? -1 : WorldPoint.fromLocalInstance(client, local).getRegionID();
		boolean nextInChambersOfXeric = nextRegionId >= 0
			&& client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
		RegionContext previous = regionContext;
		RegionContext next = previous.update(state, nextRegionId, nextInChambersOfXeric);
		if (next != previous)
		{
			regionContext = next;
			log.debug("Region context changed: region={}, inCox={}, ready={}",
				next.regionId, next.inChambersOfXeric, next.ready);
			GpuSkyExtension current = extension;
			if (current != null)
			{
				current.invalidateHeightMap();
			}
			requestAssetRefresh();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		// Resolve a newly loaded scene on its first game tick, once player/raid data is available.
		if (state != GameState.LOGGED_IN) refreshRegionContext();
		if (state == GameState.LOADING || state == GameState.LOGGED_IN)
		{
			GpuSkyExtension current = extension;
			if (current != null)
			{
				current.invalidateHeightMap();
			}
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (extension != null) extension.invalidateFloorPresence();
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		AssetLoadSession session = loadSession;
		if (session == null) return;
		resumeLoading();
		// Load the new profile's overrides now, before any panel edit can save the old list into it.
		reloadOverridesForProfile();
		// Before the chooser reads the resolution: a profile from the old 8K checkbox keeps its 8K choice.
		migrateTextureResolution();
		resetTextureQualitySetup();
		session.cancelUploads();
		refreshLoadingOverlay();
		requestAssetRefresh();
	}

	private synchronized void reloadOverridesForProfile()
	{
		loadedOverridesJson = null;
		refreshRegionOverrides();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (extension != null) extension.invalidateFloorPresence();
		if (!GpuSkyExtensionConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		resumeLoading();
		if (WaterFeature.KEYS.contains(event.getKey()))
		{
			// Water settings are read live each frame; no skybox reload. If one was
			// cleared, store its default again so the panel shows the real value.
			if (event.getNewValue() == null)
			{
				WaterFeature.saveMissingDefaults(configManager, GpuSkyExtensionConfig.GROUP, config);
			}
			return;
		}

		requestAssetRefresh();
		if ("textureResolution".equals(event.getKey()))
		{
			resolutionReducedForMemory = false;
		}
		if ("textureResolution".equals(event.getKey()) || "preloadSkyboxPresets".equals(event.getKey()))
		{
			AssetLoadSession session = loadSession;
			if (session != null) session.cancelUploads();
			refreshLoadingOverlay();
		}
		if ("showOverridesPanel".equals(event.getKey()))
		{
			SwingUtilities.invokeLater(this::syncNavigationVisibility);
		}
	}

	/**
	 * One loader pass that can never end the once-a-second schedule. After an out-of-memory
	 * error nothing heavy is retried: loading stays paused until a deliberate trigger (a
	 * setting of this plugin changes, a profile switch, a GPU restart, or the plugin restarts).
	 * Any other Error stops loading for this plugin start.
	 */
	private void poll(AssetLoadSession session)
	{
		if (session.stopped)
		{
			return;
		}
		if (session.memoryBlocked)
		{
			refreshLoadingOverlay(); // Lets the notice time out.
			return;
		}
		try
		{
			pollFilesAndConfig(session);
		}
		catch (OutOfMemoryError error)
		{
			session.memoryBlocked = true;
			abandonLoads(session);
			memoryNoticeUntil = System.nanoTime() + MEMORY_NOTICE_NANOS;
			log.warn("Skybox for GPU ran out of memory while loading and has paused loading. Lower the texture "
				+ "resolution or raise RuneLite's memory limit; loading resumes when a Skybox setting changes "
				+ "or the plugin is restarted.", error);
			refreshLoadingOverlay();
		}
		catch (RuntimeException ex)
		{
			log.debug("Skybox asset refresh failed", ex);
		}
		catch (Error error)
		{
			if (error instanceof ThreadDeath)
			{
				throw error;
			}
			session.stopped = true;
			abandonLoads(session);
			log.error("Skybox for GPU stopped loading after an unexpected error; restart the plugin to retry", error);
		}
	}

	/** Releases anything waiting on the failed pass, so neither the client nor a weather effect stays stuck. */
	private static void abandonLoads(AssetLoadSession session)
	{
		session.cancelUploads();
		for (WeatherLoadState state : session.weather.values())
		{
			state.retryNow();
		}
	}

	/** A deliberate trigger after an out-of-memory pause: try loading again, once. */
	private void resumeLoading()
	{
		AssetLoadSession session = loadSession;
		if (session != null && session.memoryBlocked)
		{
			session.memoryBlocked = false;
			memoryNoticeUntil = 0;
		}
	}

	/** Loading is paused after running out of memory, and the notice about it is still due. */
	boolean memoryNoticeDue()
	{
		AssetLoadSession session = loadSession;
		return session != null && session.memoryBlocked && System.nanoTime() - memoryNoticeUntil < 0;
	}

	private void pollFilesAndConfig(AssetLoadSession session)
	{
		if (session != loadSession) return;
		refreshStage(session, "asset directory", this::ensureSkyboxDirectory);
		if (session != loadSession) return;
		// Before the texture-chooser gate: a new profile shows the chooser, and its
		// overrides must still be loaded while it waits.
		refreshStage(session, "region overrides", () -> session.publish(this::refreshRegionOverrides));
		if (session != loadSession) return;
		TextureQualitySetup setup = textureQualitySetup;
		if (setup != null && setup.isPending())
		{
			setup.prepare(skyboxDirectory);
			return;
		}
		if (setup != textureQualitySetup) return;
		if (skyboxDirectory == null)
		{
			return;
		}
		int maxWidth = config.textureResolution().maxWidth();
		GpuSkyExtensionConfig.WeatherEffect mainWeather = session.randomWeather.select(
			config.weatherEffect(), config.randomWeatherMinutes(), System.nanoTime());
		refreshStage(session, "region overrides", () -> session.publish(this::refreshRegionOverrides));
		if (session != loadSession) return;
		GpuSkyExtension current = extension;
		int generation = current == null ? -1 : current.skyboxContext();
		session.useSkyboxContext(generation);
		waitingForSkyboxContext = generation < 0 && config.preloadSkyboxPresets();
		refreshLoadingOverlay();
		if (generation >= 0)
		{
			try
			{
				refreshStage(session, "preloaded skies", () -> loadPreloadedSkyboxesIfChanged(session, maxWidth));
				refreshStage(session, "active skies", () -> loadSkyboxesIfChanged(session, maxWidth, mainWeather));
			}
			finally
			{
				SkyboxUploadBatch batch = session.uploads;
				if (batch != null)
				{
					try
					{
						batch.checkActive();
					}
					catch (CancellationException ex)
					{
						// A partial batch may contain identities whose textures were never published.
						session.useSkyboxContext(-1);
					}
					batch.finish();
					session.uploads = null;
				}
				clientThread.invokeLater(() -> session.publish(() ->
				{
					if (session == loadSession && current == extension && current.skyboxContext() == generation)
					{
						current.pruneSkyboxes();
					}
				}));
			}
		}
		refreshStage(session, "weather", () -> loadWeatherIfNeeded(session, mainWeather));
	}

	private void refreshStage(AssetLoadSession session, String label, AssetRefresh refresh)
	{
		try
		{
			session.checkActive();
			refresh.run();
		}
		catch (CancellationException ignored)
		{
			// A stopped session cannot publish into a later startup.
		}
		catch (IOException | RuntimeException ex)
		{
			log.debug("Unable to refresh {}", label, ex);
		}
	}

	private interface AssetRefresh
	{
		void run() throws IOException;
	}

	private void loadPreloadedSkyboxesIfChanged(AssetLoadSession session, int maxWidth)
	{
		int generation = session.skyboxContext;
		boolean enabled = config.preloadSkyboxPresets();
		String mode = enabled + "|res=" + maxWidth;
		Map<String, String> signatures = new LinkedHashMap<>();
		if (enabled)
		{
			for (GpuSkyExtensionConfig.SkyboxImage skybox : GpuSkyExtensionConfig.SkyboxImage.values())
			{
				String filename = skybox.resolveFilename(GpuSkyExtensionConfig.WeatherEffect.DISABLED);
				if (filename != null)
				{
					String path = skyboxPresetPath(filename);
					signatures.put(path, safeFileSignature(path) + "|res=" + maxWidth);
				}
			}
		}
		String nextSignature = mode + signatures;
		if (nextSignature.equals(session.preloadSignature) && !session.images.retryDue())
		{
			return;
		}
		// A single edited preset is not a bulk preload notification.
		boolean bulk = enabled && (session.preloadSignature == null
			|| !session.preloadSignature.startsWith(mode + "{"));
		Runnable finish = () ->
		{
			if (bulk)
			{
				session.publish(maxWidth == 0 ? this::finishEightKPreload : this::finishFourKPreload);
			}
		};
		if (bulk)
		{
			session.publish(maxWidth == 0 ? this::beginEightKPreload : this::beginFourKPreload);
		}
		boolean handedOff = false;
		try
		{
			Map<String, SkyboxAsset> loaded = new LinkedHashMap<>();
			for (String path : signatures.keySet())
			{
				SkyboxAsset image = readSkyboxImage(session, path, "preloaded sky", false, maxWidth);
				if (image != null)
				{
					loaded.put(path, image);
					publishSkyboxes(session, () ->
					{
						GpuSkyExtension current = extension;
						if (current != null && session == loadSession && current.skyboxContext() == generation)
						{
							current.retainPreloadedSkybox(path, image);
						}
					});
				}
			}
			session.checkActive();
			session.preloaded.clear();
			session.preloaded.putAll(loaded);
			session.preloadSignature = nextSignature;
			session.skyboxSignature = null;
			Map<String, SkyboxAsset> snapshot = Collections.unmodifiableMap(loaded);
			publishSkyboxes(session, () ->
			{
				GpuSkyExtension current = extension;
				if (current != null && session == loadSession && current.skyboxContext() == generation)
				{
					current.setPreloadedSkyboxes(snapshot, finish);
				}
				else
				{
					finish.run();
				}
			});
			handedOff = true;
		}
		finally
		{
			if (!handedOff)
			{
				finish.run();
			}
		}
	}

	private void requestAssetRefresh()
	{
		ScheduledExecutorService currentLoader = loader;
		AssetLoadSession session = loadSession;
		if (currentLoader == null || session == null || currentLoader.isShutdown()
			|| !session.refreshQueued.compareAndSet(false, true))
		{
			return;
		}

		try
		{
			currentLoader.execute(() ->
			{
				session.refreshQueued.set(false);
				poll(session);
			});
		}
		catch (RejectedExecutionException ex)
		{
			session.refreshQueued.set(false);
			log.debug("GPU Sky asset refresh skipped during shutdown", ex);
		}
	}

	private void loadSkyboxesIfChanged(AssetLoadSession session, int maxWidth,
		GpuSkyExtensionConfig.WeatherEffect mainWeather)
	{
		int generation = session.skyboxContext;
		RegionContext context = regionContext;
		if (!context.ready) return;
		List<RegionOverride> overrides = regionOverrides;
		boolean overridesDisabled = config.disableRegionOverrides();
		RegionOverride activeOverride = activeRegionOverride(context, overrides, overridesDisabled);
		TfaRoomSettings roomSettings = TfaRoomSettings.resolve(activeOverride, context.regionId);
		boolean mainSkyboxDisabled = config.skyboxImage() == GpuSkyExtensionConfig.SkyboxImage.DISABLED;
		String customOverworld = mainSkyboxDisabled ? "" : trim(config.skyboxFile());
		String overworld = customOverworld;
		if (!mainSkyboxDisabled && overworld.isEmpty())
		{
			overworld = skyboxPresetPath(config.skyboxImage().resolveFilename(mainWeather));
		}
		String cave = mainSkyboxDisabled ? "" : trim(config.caveSkyboxFile());
		boolean regionSkyboxDisabled = activeOverride != null
			&& activeOverride.skyboxImage == GpuSkyExtensionConfig.SkyboxImage.DISABLED;
		String region = activeOverride == null || regionSkyboxDisabled
			? ""
			: trim(activeOverride.skyboxFile);
		if (activeOverride != null && !regionSkyboxDisabled && region.isEmpty())
		{
			String regionSkybox = null;
			if (activeOverride.skyboxImage != GpuSkyExtensionConfig.SkyboxImage.MATCH_WEATHER)
			{
				regionSkybox = activeOverride.skyboxImage.resolveFilename(mainWeather);
			}
			else if (activeOverride.weather != GpuSkyExtensionConfig.RegionWeatherOverride.MAIN
				&& activeOverride.weather != GpuSkyExtensionConfig.RegionWeatherOverride.DISABLED)
			{
				regionSkybox = GpuSkyExtensionConfig.WeatherEffect.valueOf(
					activeOverride.weather.name()).skyboxFilename();
			}
			if (regionSkybox != null)
			{
				region = skyboxPresetPath(regionSkybox);
			}
		}
		String signature = safeFileSignature(overworld) + '|' + safeFileSignature(cave) + '|' + safeFileSignature(region)
			+ "|res=" + maxWidth;
		// Also when a sky that failed to load or upload is due for one of its few retries.
		boolean imagesChanged = !signature.equals(session.skyboxSignature) || session.images.retryDue();
		SkyboxAsset overworldImage = null;
		SkyboxAsset caveImage = null;
		SkyboxAsset regionImage = null;
		if (imagesChanged)
		{
			overworldImage = readSkyboxImage(session, overworld, "overworld", !overworld.isEmpty(), maxWidth);
			caveImage = readSkyboxImage(session, cave, "cave", false, maxWidth);
			regionImage = readSkyboxImage(session, region, "region", false, maxWidth);
		}
		Set<String> retainedImages = new HashSet<>(session.preloaded.keySet());
		Collections.addAll(retainedImages, overworld, cave, region);
		session.images.retain(retainedImages);
		String regionState = activeOverride == null
			? "none"
			: activeOverride.id + '|' + activeOverride.enabled + '|' + activeOverride.regionIds
				+ '|' + activeOverride.weather
				+ '|' + activeOverride.skyboxImage
				+ '|' + trim(activeOverride.skyboxFile)
				+ '|' + activeOverride.weatherIntensity + '|' + activeOverride.inheritSkySettings
				+ '|' + activeOverride.horizontalFov + '|' + activeOverride.yawOffset
				+ '|' + activeOverride.pitchOffset + '|' + activeOverride.exposure
				+ '|' + activeOverride.inheritLightSettings + '|' + activeOverride.worldLighting
				+ '|' + activeOverride.rimLighting + '|' + activeOverride.backlighting
				+ '|' + activeOverride.skyboxTint + '|' + activeOverride.atmosphere
				+ '|' + trim(activeOverride.waterColor) + '|' + roomSettings;
		boolean regionStateChanged = !regionState.equals(session.regionState);
		boolean weatherChanged = mainWeather != session.publishedMainWeather;
		if (!imagesChanged && !regionStateChanged && !weatherChanged)
		{
			return;
		}

		GpuSkyExtensionConfig.RegionWeatherOverride weather = activeOverride == null
			? GpuSkyExtensionConfig.RegionWeatherOverride.MAIN
			: activeOverride.weather;
		int intensity = activeOverride == null ? 100 : activeOverride.weatherIntensity;
		boolean inheritSkySettings = activeOverride == null || activeOverride.inheritSkySettings;
		int horizontalFov = activeOverride == null ? config.horizontalFov() : activeOverride.horizontalFov;
		int yawOffset = activeOverride == null ? config.yawOffset() : activeOverride.yawOffset;
		int pitchOffset = activeOverride == null ? config.pitchOffset() : activeOverride.pitchOffset;
		int exposure = activeOverride == null ? config.exposure() : activeOverride.exposure;
		boolean inheritLightSettings = activeOverride == null || activeOverride.inheritLightSettings;
		int worldLighting = activeOverride == null ? config.worldLighting() : activeOverride.worldLighting;
		int rimLighting = activeOverride == null ? config.rimLighting() : activeOverride.rimLighting;
		int backlighting = activeOverride == null ? config.backlighting() : activeOverride.backlighting;
		int skyboxTint = activeOverride == null ? config.skyboxTint() : activeOverride.skyboxTint;
		int atmosphere = activeOverride == null ? config.atmosphere() : activeOverride.atmosphere;
		java.awt.Color regionWaterColor = activeOverride == null ? null : activeOverride.customWaterColor();
		boolean disableRegionSkybox = activeOverride != null
			&& activeOverride.skyboxImage == GpuSkyExtensionConfig.SkyboxImage.DISABLED;
		SkyboxAsset newOverworldImage = overworldImage;
		SkyboxAsset newCaveImage = caveImage;
		SkyboxAsset newRegionImage = regionImage;
		session.checkActive();
		CompletableFuture<Boolean> publication = new CompletableFuture<>();
		boolean published = false;
		try
		{
			publishSkyboxes(session, () ->
			{
				if (publication.isDone()) return;
				try
				{
					GpuSkyExtension current = extension;
					if (current == null || session != loadSession || current.skyboxContext() != generation
						|| context != regionContext || overrides != regionOverrides
						|| overridesDisabled != config.disableRegionOverrides())
					{
						publication.complete(false);
						return;
					}
					current.setMainWeather(mainWeather);
					if (imagesChanged)
					{
						current.setImages(newOverworldImage, newCaveImage, newRegionImage);
					}
					current.setRegionOverride(activeOverride != null, weather, intensity, disableRegionSkybox,
						inheritSkySettings, horizontalFov, yawOffset, pitchOffset, exposure, inheritLightSettings,
						worldLighting, rimLighting, backlighting, skyboxTint, atmosphere, roomSettings);
					// Same moment as the skybox: the water color then fades with the sky.
					publishedRegionWaterColor = regionWaterColor;
					publication.complete(true);
				}
				catch (RuntimeException ex)
				{
					publication.completeExceptionally(ex);
				}
			});
			// Wait only on the loader; commit signatures after the client accepts this destination.
			published = publication.get(30, TimeUnit.SECONDS);
			if (published)
			{
				session.skyboxSignature = signature;
				session.regionState = regionState;
				session.publishedMainWeather = mainWeather;
			}
			else
			{
				requestAssetRefresh();
			}
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			throw new CancellationException("Skybox selection interrupted");
		}
		catch (TimeoutException ex)
		{
			throw new CancellationException("Skybox selection timed out");
		}
		catch (ExecutionException ex)
		{
			throw new IllegalStateException("Unable to publish skybox selection", ex.getCause());
		}
		finally
		{
			publication.cancel(false);
			if (!published)
			{
				// Rejected uploads can be pruned; do not reuse their identities as resident textures.
				session.images.retain(session.preloaded.keySet());
				session.skyboxSignature = null;
				session.regionState = null;
			}
		}
	}

	boolean isLoadingEightKPresets()
	{
		return config.preloadSkyboxPresets() && config.textureResolution() == GpuSkyExtensionConfig.TextureResolution.RES_8K
			&& (waitingForSkyboxContext || eightKPreloadsInProgress.get() > 0);
	}

	boolean isLoadingFourKPresets()
	{
		return config.preloadSkyboxPresets() && config.textureResolution() != GpuSkyExtensionConfig.TextureResolution.RES_8K
			&& (waitingForSkyboxContext || fourKPreloadsInProgress.get() > 0);
	}

	boolean isLoadingSkyboxTextures()
	{
		TextureQualitySetup setup = textureQualitySetup;
		if (setup != null && setup.isPending()) return false;
		return isLoadingEightKPresets() || isLoadingFourKPresets();
	}

	PreloadStatus preloadStatus()
	{
		if (memoryNoticeDue())
		{
			return PreloadStatus.OUT_OF_MEMORY;
		}
		GpuSkyExtension current = extension;
		return preloadStatus(isLoadingSkyboxTextures(), current != null && current.hasContext());
	}

	static PreloadStatus preloadStatus(boolean pending, boolean contextReady)
	{
		return !pending ? PreloadStatus.IDLE : contextReady ? PreloadStatus.LOADING : PreloadStatus.WAITING_FOR_GPU;
	}

	enum PreloadStatus
	{
		IDLE, WAITING_FOR_GPU, LOADING, OUT_OF_MEMORY
	}

	private void beginEightKPreload()
	{
		eightKPreloadsInProgress.incrementAndGet();
		refreshLoadingOverlay();
	}

	private void finishEightKPreload()
	{
		eightKPreloadsInProgress.updateAndGet(value -> Math.max(0, value - 1));
		refreshLoadingOverlay();
	}

	private void beginFourKPreload()
	{
		fourKPreloadsInProgress.incrementAndGet();
		refreshLoadingOverlay();
	}

	private void finishFourKPreload()
	{
		fourKPreloadsInProgress.updateAndGet(value -> Math.max(0, value - 1));
		refreshLoadingOverlay();
	}

	private void refreshLoadingOverlay()
	{
		SkyboxLoadingOverlay currentLoadingOverlay = loadingOverlay;
		if (currentLoadingOverlay != null)
		{
			currentLoadingOverlay.refreshVisibility();
		}
	}

	private void onGpuContextChanged()
	{
		resumeLoading();
		AssetLoadSession session = loadSession;
		if (session != null)
		{
			// Uploaded weather pixels were released; the new context needs them decoded again.
			session.weatherSprites.contextLost();
			session.weatherContext.incrementAndGet();
			session.cancelUploads();
			requestAssetRefresh();
		}
		GpuSkyExtension current = extension;
		waitingForSkyboxContext = current != null && !current.hasContext() && config.preloadSkyboxPresets();
		refreshLoadingOverlay();
	}

	private SkyboxAsset uploadSkybox(AssetLoadSession session, BufferedImage image, String file) throws IOException
	{
		session.checkActive();
		SkyboxAsset asset = new SkyboxAsset(image, file);
		SkyboxUploadBatch batch = session.uploads;
		if (batch == null)
		{
			GpuSkyExtension current = extension;
			int generation = session.skyboxContext;
			if (current == null || current.skyboxContext() != generation || generation < 0)
			{
				throw new CancellationException("GPU context changed while decoding skybox");
			}
			batch = new SkyboxUploadBatch();
			session.uploads = batch;
			SkyboxUploadBatch queued = batch;
			// One deliberate preload pause. Only the worker performs IO and decoding;
			// it waits for each upload acknowledgement before decoding another panorama.
			clientThread.invokeLater(() -> queued.drain(
				() -> session == loadSession && current == extension && current.skyboxContext() == generation,
				current::uploadSkybox));
		}
		batch.upload(asset, image);
		session.checkActive();
		return asset;
	}

	private void publishSkyboxes(AssetLoadSession session, Runnable action)
	{
		Runnable publication = () -> session.publish(action);
		SkyboxUploadBatch batch = session.uploads;
		if (batch == null)
		{
			clientThread.invokeLater(publication);
			return;
		}
		try
		{
			// Publish texture identities before the paused frame resumes and prunes old textures.
			batch.execute(publication);
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("Unable to publish skybox state", ex);
		}
	}

	private SkyboxAsset readSkyboxImage(AssetLoadSession session, String configured, String label,
		boolean bundledFallback, int maxWidth)
	{
		session.checkActive();
		if (configured.isEmpty())
		{
			return null;
		}
		String signature = safeFileSignature(configured) + "|res=" + maxWidth;
		SkyboxAsset image = session.images.read(configured, signature, () ->
		{
			try
			{
				BufferedImage loaded = readImage(configured, label, bundledFallback, maxWidth);
				session.checkActive();
				return loaded == null ? null : uploadSkybox(session, loaded, configured);
			}
			catch (RuntimeException ex)
			{
				if (ex instanceof CancellationException)
				{
					throw ex;
				}
				throw new IOException("Unable to decode skybox: " + configured, ex);
			}
		}, ex -> log.warn("Unable to load {}; keeping its last working image: {}", configured, ex.getMessage()));
		session.checkActive();
		return image;
	}

	private String safeFileSignature(String configured)
	{
		try
		{
			return fileSignature(configured);
		}
		catch (IOException | IllegalArgumentException ex)
		{
			return configured + ":unavailable";
		}
	}

	/** RuneLite's own color picker, so the sidebar matches the config panel. */
	net.runelite.client.ui.components.colorpicker.ColorPickerManager colorPickerManager()
	{
		return colorPickerManager;
	}

	/** The main Water color setting (shown as the starting color for a region override). */
	java.awt.Color mainWaterColor()
	{
		java.awt.Color color = config.waterColor();
		return color == null ? com.endrit.gpusky.water.WaterConfig.DEFAULT_WATER_COLOR : color;
	}

	private RegionOverride activeRegionOverride()
	{
		return activeRegionOverride(regionContext, regionOverrides, config.disableRegionOverrides());
	}

	static RegionOverride activeRegionOverride(RegionContext context, List<RegionOverride> overrides, boolean disabled)
	{
		if (disabled || !context.ready)
		{
			return null;
		}

		if (context.inChambersOfXeric)
		{
			for (RegionOverride override : overrides)
			{
				if (override.matchesChambersOfXeric())
				{
					return override;
				}
			}
		}
		for (RegionOverride override : overrides)
		{
			if (override.matches(context.regionId))
			{
				return override;
			}
		}
		return null;
	}

	private static String skyboxPresetPath(String filename)
	{
		return filename == null ? "" : "weather/presets/" + filename;
	}

	private void loadWeatherIfNeeded(AssetLoadSession session, GpuSkyExtensionConfig.WeatherEffect mainWeather)
	{
		Set<GpuSkyExtensionConfig.WeatherEffect> requested = EnumSet.noneOf(GpuSkyExtensionConfig.WeatherEffect.class);
		if (mainWeather != GpuSkyExtensionConfig.WeatherEffect.DISABLED)
		{
			requested.add(mainWeather);
		}
		RegionOverride override = activeRegionOverride();
		if (override != null
			&& override.weather != GpuSkyExtensionConfig.RegionWeatherOverride.MAIN
			&& override.weather != GpuSkyExtensionConfig.RegionWeatherOverride.DISABLED)
		{
			requested.add(GpuSkyExtensionConfig.WeatherEffect.valueOf(override.weather.name()));
		}
		if (requested.remove(GpuSkyExtensionConfig.WeatherEffect.SUNLIGHT))
		{
			requested.add(GpuSkyExtensionConfig.WeatherEffect.MOONLIGHT);
		}
		// Galaxy is drawn in the sky shader and needs no particle asset loading.
		requested.remove(GpuSkyExtensionConfig.WeatherEffect.GALAXY);
		for (GpuSkyExtensionConfig.WeatherEffect effect : requested)
		{
			session.checkActive();
			WeatherLoadState state = session.weather.computeIfAbsent(effect, key -> new WeatherLoadState());
			if (!state.begin(System.nanoTime(), session.weatherContext.get()))
			{
				continue;
			}
			try
			{
				Filepath weatherDirectory = skyboxDirectory.joinSegment("weather");
				String assetDirectory = effect == GpuSkyExtensionConfig.WeatherEffect.FALL
					|| effect == GpuSkyExtensionConfig.WeatherEffect.DAY
					? "spring" : effect.name().toLowerCase(Locale.ROOT);
				if (effect == GpuSkyExtensionConfig.WeatherEffect.SIROCCO)
				{
					assetDirectory = "sandstorm";
				}
				else if (effect == GpuSkyExtensionConfig.WeatherEffect.PESTILENCE)
				{
					assetDirectory = "shared";
				}
				Filepath directory = weatherDirectory.joinSegment(assetDirectory);
				Filepath sharedDirectory = weatherDirectory.joinSegment("shared");
				WeatherRenderer.LoadedWeatherAssets assets =
					WeatherRenderer.loadAssets(effect, directory, sharedDirectory, state.assets, session.weatherSprites);
				state.assets = assets;
				session.checkActive();
				clientThread.invokeLater(() -> session.publish(() ->
				{
					GpuSkyExtension current = extension;
					if (current != null && session == loadSession)
					{
						current.uploadWeather(assets, success ->
						{
							if (!success && assets.foundStale())
							{
								// Loaded just before a GPU context change: decode again now, without the backoff.
								state.retryNow();
								requestAssetRefresh();
							}
							else
							{
								state.finish(success, System.nanoTime());
							}
						});
					}
				}));
			}
			catch (Error error)
			{
				// Never leave the effect marked as loading; poll() decides what happens next.
				state.retryNow();
				throw error;
			}
			catch (RuntimeException ex)
			{
				state.finish(false, System.nanoTime());
				if (ex instanceof CancellationException)
				{
					throw ex;
				}
				log.debug("Unable to prepare {}; will retry", effect, ex);
			}
		}
	}

	private BufferedImage readImage(String configured, String label, boolean bundledFallback,
		int maxWidth) throws IOException
	{
		Filepath path = resolveSkyboxPath(configured);
		if (path == null)
		{
			if (!configured.isEmpty())
			{
				log.warn("{} skybox file does not exist: {}", label, configured);
			}
			return bundledFallback ? readFallbackSkybox(maxWidth) : null;
		}
		BufferedImage image = ImageLoader.readPanorama(path, maxWidth, this::noteReducedResolution);
		if (image == null)
		{
			log.warn("Unable to decode {} skybox {}", label, path);
			return null;
		}
		log.debug("Loaded {} skybox {} {}x{}", label, path, image.getWidth(), image.getHeight());
		return image;
	}

	private BufferedImage readFallbackSkybox(int maxWidth) throws IOException
	{
		Filepath path = resolveSkyboxPath(FALLBACK_SKYBOX);
		if (path == null)
		{
			log.warn("Fallback skybox is missing: {}", FALLBACK_SKYBOX);
			return null;
		}
		BufferedImage image = ImageLoader.readPanorama(path, maxWidth, this::noteReducedResolution);
		if (image == null)
		{
			throw new IOException("Unable to decode fallback skybox " + path);
		}
		log.debug("Loaded fallback skybox {} {}x{}", path, image.getWidth(), image.getHeight());
		return image;
	}

	/** Logs once per file and resolution, and tells the loading overlay to explain the reduced quality. */
	private void noteReducedResolution(String message)
	{
		if (reducedResolutionWarnings.add(message))
		{
			log.warn("{}. Raise RuneLite's memory limit to load it at full size.", message);
		}
		if (!resolutionReducedForMemory)
		{
			resolutionReducedForMemory = true;
			refreshLoadingOverlay();
		}
	}

	/** A panorama is shown below the selected resolution because of the memory limit. */
	boolean resolutionReducedForMemory()
	{
		return resolutionReducedForMemory;
	}

	private String fileSignature(String configured) throws IOException
	{
		if (configured.isEmpty())
		{
			return "none";
		}
		Filepath path = resolveSkyboxPath(configured);
		return path == null
			? configured + ":missing"
			: path + ":" + path.size() + ":" + path.getLastModifiedTime().toMillis();
	}

	private Filepath resolveSkyboxPath(String configured) throws IOException
	{
		if (configured.isEmpty())
		{
			return null;
		}
		Filepath candidate = configuredSkyboxPath(ensureSkyboxDirectory(), configured);
		return candidate.isFile() ? candidate : null;
	}

	static Filepath configuredSkyboxPath(Filepath directory, String configured)
	{
		String root = directory.toString().replace('\\', '/');
		String path = configured.replace('\\', '/');
		String managedSuffix = "/plugin-data/skybox-for-gpu";
		String legacyRoot = root.endsWith(managedSuffix)
			? root.substring(0, root.length() - managedSuffix.length()) + "/gpu-sky/" : root + "/";
		boolean windows = root.length() > 1 && root.charAt(1) == ':';
		// Saved absolute paths inside the old owned directory follow RuneLite's migration.
		for (String prefix : new String[]{root + "/", legacyRoot})
		{
			if (path.regionMatches(windows, 0, prefix, 0, prefix.length()))
			{
				path = path.substring(prefix.length());
				break;
			}
		}
		return directory.join(path);
	}

	private static String trim(String value)
	{
		return value == null ? "" : value.trim();
	}
}
