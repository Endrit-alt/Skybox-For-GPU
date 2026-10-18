package com.endrit.gpusky;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.Filepath;

final class RegionOverridePanel extends PluginPanel
{
	private static final Color CARD_COLOR = new Color(38, 38, 38);
	private static final Color FIELD_COLOR = new Color(30, 30, 30);
	private static final Dimension ICON_SIZE = new Dimension(24, 24);

	private final GpuSkyExtensionPlugin plugin;
	private final IconTextField search = new IconTextField();
	private final JComboBox<SortOrder> sortOrder = new JComboBox<>(SortOrder.values());
	private final JPanel list = new JPanel();
	private final Set<String> expanded = new HashSet<>();
	private final Set<String> expandedRegionLists = new HashSet<>();
	private final Set<String> newlyAddedOverrides = new HashSet<>();
	private final Timer regionRecorder = new Timer(250, event -> recordCurrentRegion());
	private final AWTEventListener outsideRegionClickListener = this::onGlobalMouseEvent;
	private Component temporarilyExpandedRegionField;
	private Runnable temporaryRegionCollapse;
	private String recordingOverrideId;
	private int lastRecordedRegion = -1;
	private boolean disposed;

	RegionOverridePanel(GpuSkyExtensionPlugin plugin)
	{
		this.plugin = plugin;
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		Toolkit.getDefaultToolkit().addAWTEventListener(outsideRegionClickListener, AWTEvent.MOUSE_EVENT_MASK);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(new EmptyBorder(0, 0, 4, 0));
		JLabel title = new JLabel("Region Overrides");
		title.setForeground(Color.WHITE);
		header.add(title, BorderLayout.WEST);
		JButton add = iconButton("+", "Add region override");
		add.addActionListener(event ->
		{
			RegionOverride created = plugin.addRegionOverride();
			newlyAddedOverrides.add(created.id);
			expanded.add(created.id);
			reload();
		});
		header.add(add, BorderLayout.EAST);
		add(header);

		search.setIcon(IconTextField.Icon.SEARCH);
		search.setBackground(FIELD_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		search.getDocument().addDocumentListener(documentListener(this::reload));
		search.setPreferredSize(new Dimension(110, 30));
		search.setMinimumSize(new Dimension(70, 30));

		sortOrder.setSelectedItem(SortOrder.ADDED);
		sortOrder.setBackground(FIELD_COLOR);
		sortOrder.setFocusable(false);
		sortOrder.setPreferredSize(new Dimension(105, 30));
		sortOrder.addActionListener(event -> reload());

		JPanel controls = new JPanel(new BorderLayout(5, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.setBorder(new EmptyBorder(0, 0, 4, 0));
		controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		controls.add(search, BorderLayout.CENTER);
		controls.add(sortOrder, BorderLayout.EAST);
		add(controls);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(list);
		reload();
	}

	void reload()
	{
		collapseTemporaryRegionList();
		list.removeAll();
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		List<RegionOverride> overrides = plugin.regionOverrides();
		if (sortOrder.getSelectedItem() == SortOrder.ALPHABETICAL)
		{
			overrides.sort((left, right) ->
			{
				boolean leftDefault = newlyAddedOverrides.contains(left.id) || isDefaultOverrideName(left.name);
				boolean rightDefault = newlyAddedOverrides.contains(right.id) || isDefaultOverrideName(right.name);
				if (leftDefault != rightDefault)
				{
					return leftDefault ? 1 : -1;
				}
				return leftDefault ? 0 : String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name);
			});
		}
		int matches = 0;
		for (RegionOverride override : overrides)
		{
			String searchable = (override.name + ' ' + override.regionIds + ' ' + override.weather
				+ ' ' + override.skyboxImage)
				.toLowerCase(Locale.ROOT);
			if (!query.isEmpty() && !searchable.contains(query))
			{
				continue;
			}
			list.add(new OverrideEditor(override));
			list.add(Box.createVerticalStrut(4));
			matches++;
		}

		if (matches == 0)
		{
			JLabel empty = new JLabel(query.isEmpty() ? "No region overrides" : "No matching overrides");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setHorizontalAlignment(SwingConstants.CENTER);
			empty.setBorder(new EmptyBorder(18, 0, 8, 0));
			empty.setAlignmentX(Component.CENTER_ALIGNMENT);
			list.add(empty);
		}
		list.revalidate();
		list.repaint();
	}

	private static boolean isDefaultOverrideName(String name)
	{
		if (name == null)
		{
			return false;
		}
		String normalized = name.trim().toLowerCase(Locale.ROOT);
		String prefix = "region override ";
		if (!normalized.startsWith(prefix) || normalized.length() == prefix.length())
		{
			return false;
		}
		for (int i = prefix.length(); i < normalized.length(); i++)
		{
			if (!Character.isDigit(normalized.charAt(i)))
			{
				return false;
			}
		}
		return true;
	}

	void dispose()
	{
		disposed = true;
		collapseTemporaryRegionList();
		Toolkit.getDefaultToolkit().removeAWTEventListener(outsideRegionClickListener);
		stopRecording();
	}

	void stopRecording()
	{
		regionRecorder.stop();
		recordingOverrideId = null;
		lastRecordedRegion = -1;
	}

	private void onGlobalMouseEvent(AWTEvent event)
	{
		if (!(event instanceof MouseEvent) || event.getID() != MouseEvent.MOUSE_PRESSED
			|| temporarilyExpandedRegionField == null)
		{
			return;
		}
		Object source = event.getSource();
		if (!(source instanceof Component)
			|| (source != temporarilyExpandedRegionField
				&& !SwingUtilities.isDescendingFrom((Component) source, temporarilyExpandedRegionField)))
		{
			collapseTemporaryRegionList();
		}
	}

	private void temporarilyExpandRegionList(Component field, Runnable collapse)
	{
		if (temporarilyExpandedRegionField != field)
		{
			collapseTemporaryRegionList();
		}
		temporarilyExpandedRegionField = field;
		temporaryRegionCollapse = collapse;
	}

	private void collapseTemporaryRegionList()
	{
		Runnable collapse = temporaryRegionCollapse;
		temporarilyExpandedRegionField = null;
		temporaryRegionCollapse = null;
		if (collapse != null)
		{
			collapse.run();
		}
	}

	private void scrollOuterPanel(MouseWheelEvent event)
	{
		JScrollBar scrollBar = getScrollPane().getVerticalScrollBar();
		int direction = event.getWheelRotation() < 0 ? -1 : 1;
		int amount = event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
			? event.getWheelRotation() * scrollBar.getBlockIncrement(direction)
			: event.getUnitsToScroll() * scrollBar.getUnitIncrement(direction);
		scrollBar.setValue(scrollBar.getValue() + amount);
		event.consume();
	}

	private void toggleRecording(String overrideId)
	{
		if (overrideId.equals(recordingOverrideId))
		{
			stopRecording();
		}
		else
		{
			recordingOverrideId = overrideId;
			lastRecordedRegion = -1;
			regionRecorder.start();
			recordCurrentRegion();
		}
		reload();
	}

	private void recordCurrentRegion()
	{
		if (recordingOverrideId == null)
		{
			return;
		}
		int regionId = plugin.currentRegionId();
		if (regionId < 0 || regionId == lastRecordedRegion)
		{
			return;
		}
		lastRecordedRegion = regionId;

		RegionOverride target = null;
		for (RegionOverride candidate : plugin.regionOverrides())
		{
			if (candidate.id.equals(recordingOverrideId))
			{
				target = candidate;
				break;
			}
		}
		if (target == null)
		{
			stopRecording();
			reload();
			return;
		}
		if (target.parsedRegionIds().contains(regionId))
		{
			return;
		}

		String existingOverride = plugin.regionOverrideUsing(regionId, recordingOverrideId);
		if (existingOverride != null)
		{
			Object[] options = {"Skip", "Override"};
			int choice = JOptionPane.showOptionDialog(this,
				"Region " + regionId + " is already used by \"" + existingOverride + "\".",
				"Region already assigned", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
				null, options, options[0]);
			if (choice != 1)
			{
				return;
			}
		}
		plugin.reassignRegion(regionId, recordingOverrideId);
		reload();
	}

	private enum SortOrder
	{
		ALPHABETICAL("Alphabetical"),
		ADDED("Added order");

		private final String label;

		SortOrder(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	private final class OverrideEditor extends JPanel
	{
		private final RegionOverride override;
		private final JPanel body = new JPanel(new GridBagLayout());
		private final JButton expandButton = iconButton("\u25b8", "Expand override");
		private final JButton enabledButton = iconButton("", "Enable override");
		private final JLabel nameLabel = new JLabel();
		private boolean regionListTemporarilyExpanded;
		private int importRequest;

		private OverrideEditor(RegionOverride override)
		{
			this.override = override;
			setLayout(new BorderLayout());
			setBackground(CARD_COLOR);
			setBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

			JPanel row = new JPanel(new BorderLayout(2, 0));
			row.setBackground(CARD_COLOR);
			row.setPreferredSize(new Dimension(0, 30));
			row.setMinimumSize(new Dimension(0, 30));
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
			expandButton.addActionListener(event -> toggleExpanded());
			row.add(expandButton, BorderLayout.WEST);

			nameLabel.setText(displayName());
			nameLabel.setForeground(override.enabled ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
			nameLabel.setBorder(new EmptyBorder(0, 3, 0, 2));
			row.add(nameLabel, BorderLayout.CENTER);

			JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
			actions.setBackground(CARD_COLOR);
			actions.setBorder(new EmptyBorder(3, 0, 3, 0));
			updateEnabledButton();
			enabledButton.addActionListener(event ->
			{
				override.enabled = !override.enabled;
				persist();
				nameLabel.setForeground(override.enabled ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
				updateEnabledButton();
			});
			actions.add(enabledButton);
			JButton remove = rotatedPlusButton("Delete override");
			remove.addActionListener(event ->
			{
				if (override.regionIds != null && !override.regionIds.trim().isEmpty())
				{
					int choice = JOptionPane.showConfirmDialog(this,
						"Delete \"" + displayName() + "\" and its region assignments?",
						"Delete region override", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
					if (choice != JOptionPane.YES_OPTION)
					{
						return;
					}
				}
				if (override.id.equals(recordingOverrideId))
				{
					stopRecording();
				}
				expanded.remove(override.id);
				expandedRegionLists.remove(override.id);
				newlyAddedOverrides.remove(override.id);
				plugin.removeRegionOverride(override.id);
				reload();
			});
			actions.add(remove);
			row.add(actions, BorderLayout.EAST);
			add(row, BorderLayout.NORTH);

			body.setBackground(CARD_COLOR);
			body.setBorder(new EmptyBorder(7, 7, 7, 7));
			buildBody();
			add(body, BorderLayout.CENTER);
			setExpanded(expanded.contains(override.id));
		}

		private void buildBody()
		{
			JTextField name = textField(override.name);
			boolean initiallyDefaultName = isDefaultOverrideName(override.name);
			name.setPreferredSize(new Dimension(name.getPreferredSize().width, 30));
			name.setMinimumSize(new Dimension(0, 30));
			name.getDocument().addDocumentListener(documentListener(() ->
			{
				override.name = name.getText();
				if (!isDefaultOverrideName(override.name))
				{
					newlyAddedOverrides.remove(override.id);
				}
				nameLabel.setText(displayName());
				persist();
			}));
			name.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent event)
				{
					if (initiallyDefaultName && !isDefaultOverrideName(override.name)
						&& sortOrder.getSelectedItem() == SortOrder.ALPHABETICAL)
					{
						reload();
					}
				}
			});
			addField(0, "Name", name);

			JTextArea regions = regionArea(override.regionIds);
			regions.setFont(name.getFont());
			JScrollPane regionScroll = new JScrollPane(regions,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			regionScroll.setBorder(null);
			regionScroll.setViewportBorder(null);
			regionScroll.setWheelScrollingEnabled(false);
			regionScroll.getViewport().setBackground(FIELD_COLOR);
			regions.addMouseWheelListener(RegionOverridePanel.this::scrollOuterPanel);
			regionScroll.addMouseWheelListener(RegionOverridePanel.this::scrollOuterPanel);
			regionScroll.getViewport().addMouseWheelListener(RegionOverridePanel.this::scrollOuterPanel);
			JPanel regionRow = compoundField(regionScroll);
			JPanel regionActions = new JPanel();
			regionActions.setLayout(new BoxLayout(regionActions, BoxLayout.Y_AXIS));
			regionActions.setOpaque(false);
			boolean recording = override.id.equals(recordingOverrideId);
			JButton record = regionButton(recording ? "\u25a0" : "\u25b6",
				recording ? "Stop recording regions" : "Record regions while exploring",
				recording ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR);
			if (!recording)
			{
				record.setFont(record.getFont().deriveFont(
					Math.max(7f, record.getFont().getSize2D() - 4f)));
			}
			record.addActionListener(event -> toggleRecording(override.id));
			regionActions.add(record);
			JButton append = regionButton("+", "Add the current region to this override",
				ColorScheme.PROGRESS_COMPLETE_COLOR);
			append.addActionListener(event -> appendCurrentRegion(regions));
			regionActions.add(append);
			JButton removeRegion = regionButton("-", "Remove the current region from this override",
				ColorScheme.PROGRESS_ERROR_COLOR);
			removeRegion.addActionListener(event -> removeCurrentRegion(regions));
			regionActions.add(removeRegion);
			regionRow.add(regionActions, BorderLayout.EAST);
			JButton toggleRegions = iconButton("+", "Keep region IDs expanded");
			toggleRegions.setPreferredSize(new Dimension(18, 18));
			toggleRegions.setMinimumSize(new Dimension(18, 18));
			toggleRegions.setMaximumSize(new Dimension(18, 18));
			JPanel regionLabel = fieldLabel("Region IDs", toggleRegions);
			Runnable updateRegionMode = () ->
			{
				boolean expanded = expandedRegionLists.contains(override.id);
				toggleRegions.setText(expanded ? "-" : "+");
				toggleRegions.setToolTipText(expanded
					? "Collapse region IDs to three rows" : "Keep region IDs expanded");
				updateRegionRows(regions, regionScroll);
				body.revalidate();
				body.repaint();
			};
			regions.getDocument().addDocumentListener(documentListener(() ->
			{
				override.regionIds = regions.getText();
				updateRegionRows(regions, regionScroll);
				persist();
			}));
			regions.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent event)
				{
					validateManualRegionAssignments(regions);
				}
			});
			regions.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent event)
				{
					regionListTemporarilyExpanded = true;
					updateRegionRows(regions, regionScroll);
					temporarilyExpandRegionList(regions, () ->
					{
						regionListTemporarilyExpanded = false;
						updateRegionRows(regions, regionScroll);
					});
				}
			});
			toggleRegions.addActionListener(event ->
			{
				if (!expandedRegionLists.add(override.id))
				{
					expandedRegionLists.remove(override.id);
				}
				updateRegionMode.run();
			});
			updateRegionMode.run();
			addField(1, regionLabel, regionRow, true);

			JTextField skybox = textField(override.skyboxFile);
			JPanel skyboxRow = compoundField(skybox);
			JButton browse = iconButton("\u2026", "Choose panorama file");
			browse.addActionListener(event -> chooseSkybox(skybox));
			skyboxRow.add(browse, BorderLayout.EAST);
			skybox.getDocument().addDocumentListener(documentListener(() ->
			{
				override.skyboxFile = skybox.getText();
				persist();
			}));
			addField(5, "Custom sky", skyboxRow);

			JComboBox<GpuSkyExtensionConfig.RegionWeatherOverride> weather =
				new JComboBox<>(GpuSkyExtensionConfig.RegionWeatherOverride.values());
			weather.setSelectedItem(override.weather);
			weather.setBackground(FIELD_COLOR);
			weather.setFocusable(false);
			weather.addActionListener(event ->
			{
				override.weather = (GpuSkyExtensionConfig.RegionWeatherOverride) weather.getSelectedItem();
				persist();
			});
			addField(2, "Weather", weather);

			JSpinner intensity = percentageSpinner(override.weatherIntensity, JTextField.LEFT);
			intensity.addChangeListener(event ->
			{
				override.weatherIntensity = (Integer) intensity.getValue();
				persist();
			});
			addField(3, "Intensity", intensity);

			JComboBox<GpuSkyExtensionConfig.SkyboxImage> skyboxImage =
				new JComboBox<>(GpuSkyExtensionConfig.SkyboxImage.values());
			skyboxImage.setSelectedItem(override.skyboxImage);
			skyboxImage.setBackground(FIELD_COLOR);
			skyboxImage.setFocusable(false);
			skyboxImage.addActionListener(event ->
			{
				override.skyboxImage = (GpuSkyExtensionConfig.SkyboxImage) skyboxImage.getSelectedItem();
				persist();
			});
			addField(4, "Skybox preset", skyboxImage);

			JCheckBox inherit = new JCheckBox("Inherit main sky settings", override.inheritSkySettings);
			inherit.setOpaque(false);
			inherit.setForeground(Color.WHITE);
			setControlHeight(inherit, 30);
			GridBagConstraints inheritConstraints = new GridBagConstraints();
			inheritConstraints.gridx = 0;
			inheritConstraints.gridy = 6;
			inheritConstraints.gridwidth = 2;
			inheritConstraints.anchor = GridBagConstraints.WEST;
			inheritConstraints.insets = new Insets(5, 0, 2, 0);
			body.add(inherit, inheritConstraints);

			JPanel customSkySettings = new JPanel(new GridBagLayout());
			customSkySettings.setOpaque(false);
			JSpinner fov = new JSpinner(new SpinnerNumberModel(override.horizontalFov, 60, 120, 1));
			fov.addChangeListener(event ->
			{
				override.horizontalFov = (Integer) fov.getValue();
				persist();
			});
			addSettingField(customSkySettings, 0, "FOV", fov);
			JSpinner yaw = new JSpinner(new SpinnerNumberModel(override.yawOffset, -180, 180, 1));
			yaw.addChangeListener(event ->
			{
				override.yawOffset = (Integer) yaw.getValue();
				persist();
			});
			addSettingField(customSkySettings, 1, "Horizontal", yaw);
			JSpinner pitch = new JSpinner(new SpinnerNumberModel(override.pitchOffset, -10, 10, 1));
			pitch.addChangeListener(event ->
			{
				override.pitchOffset = (Integer) pitch.getValue();
				persist();
			});
			addSettingField(customSkySettings, 2, "Vertical", pitch);
			JSpinner exposure = new JSpinner(new SpinnerNumberModel(override.exposure, 10, 300, 5));
			exposure.addChangeListener(event ->
			{
				override.exposure = (Integer) exposure.getValue();
				persist();
			});
			addSettingField(customSkySettings, 3, "Exposure", exposure);
			GridBagConstraints customConstraints = new GridBagConstraints();
			customConstraints.gridx = 0;
			customConstraints.gridy = 7;
			customConstraints.gridwidth = 2;
			customConstraints.weightx = 1;
			customConstraints.fill = GridBagConstraints.HORIZONTAL;
			body.add(customSkySettings, customConstraints);
			customSkySettings.setVisible(!override.inheritSkySettings);
			inherit.addActionListener(event ->
			{
				override.inheritSkySettings = inherit.isSelected();
				customSkySettings.setVisible(!override.inheritSkySettings);
				persist();
				body.revalidate();
			});

			JCheckBox inheritLights = new JCheckBox("Inherit main light settings", override.inheritLightSettings);
			inheritLights.setOpaque(false);
			inheritLights.setForeground(Color.WHITE);
			setControlHeight(inheritLights, 30);
			GridBagConstraints inheritLightsConstraints = new GridBagConstraints();
			inheritLightsConstraints.gridx = 0;
			inheritLightsConstraints.gridy = 8;
			inheritLightsConstraints.gridwidth = 2;
			inheritLightsConstraints.anchor = GridBagConstraints.WEST;
			inheritLightsConstraints.insets = new Insets(5, 0, 2, 0);
			body.add(inheritLights, inheritLightsConstraints);

			JPanel customLightSettings = new JPanel(new GridBagLayout());
			customLightSettings.setOpaque(false);
			JSpinner worldLighting = percentageSpinner(override.worldLighting);
			worldLighting.addChangeListener(event ->
			{
				override.worldLighting = (Integer) worldLighting.getValue();
				persist();
			});
			addSettingField(customLightSettings, 0, "World", worldLighting);
			JSpinner rimLighting = percentageSpinner(override.rimLighting);
			rimLighting.addChangeListener(event ->
			{
				override.rimLighting = (Integer) rimLighting.getValue();
				persist();
			});
			addSettingField(customLightSettings, 1, "Rim", rimLighting);
			JSpinner backlighting = percentageSpinner(override.backlighting);
			backlighting.addChangeListener(event ->
			{
				override.backlighting = (Integer) backlighting.getValue();
				persist();
			});
			addSettingField(customLightSettings, 2, "Backlight", backlighting);
			JSpinner skyboxTint = percentageSpinner(override.skyboxTint);
			skyboxTint.addChangeListener(event ->
			{
				override.skyboxTint = (Integer) skyboxTint.getValue();
				persist();
			});
			addSettingField(customLightSettings, 3, "Tint", skyboxTint);
			JSpinner atmosphere = percentageSpinner(override.atmosphere);
			atmosphere.addChangeListener(event ->
			{
				override.atmosphere = (Integer) atmosphere.getValue();
				persist();
			});
			addSettingField(customLightSettings, 4, "Atmosphere", atmosphere);
			GridBagConstraints customLightConstraints = new GridBagConstraints();
			customLightConstraints.gridx = 0;
			customLightConstraints.gridy = 9;
			customLightConstraints.gridwidth = 2;
			customLightConstraints.weightx = 1;
			customLightConstraints.fill = GridBagConstraints.HORIZONTAL;
			body.add(customLightSettings, customLightConstraints);
			customLightSettings.setVisible(!override.inheritLightSettings);
			inheritLights.addActionListener(event ->
			{
				override.inheritLightSettings = inheritLights.isSelected();
				customLightSettings.setVisible(!override.inheritLightSettings);
				persist();
				body.revalidate();
			});

			// Water color: the main Water color setting unless this override sets its own.
			JCheckBox inheritWater = new JCheckBox("Use main water color", override.customWaterColor() == null);
			inheritWater.setOpaque(false);
			inheritWater.setForeground(Color.WHITE);
			setControlHeight(inheritWater, 30);
			GridBagConstraints inheritWaterConstraints = new GridBagConstraints();
			inheritWaterConstraints.gridx = 0;
			inheritWaterConstraints.gridy = 10;
			inheritWaterConstraints.gridwidth = 2;
			inheritWaterConstraints.anchor = GridBagConstraints.WEST;
			inheritWaterConstraints.insets = new Insets(5, 0, 2, 0);
			body.add(inheritWater, inheritWaterConstraints);

			JPanel customWaterSettings = new JPanel(new GridBagLayout());
			customWaterSettings.setOpaque(false);
			// RuneLite's own color button and picker, as in the plugin config panel.
			net.runelite.client.ui.components.ColorJButton waterColor =
				new net.runelite.client.ui.components.ColorJButton("", plugin.mainWaterColor());
			waterColor.setFocusable(false);
			waterColor.setToolTipText("Choose this region's water color");
			Runnable showWaterColor = () ->
			{
				Color shown = override.customWaterColor() != null ? override.customWaterColor() : plugin.mainWaterColor();
				waterColor.setColor(shown);
				waterColor.setText(RegionOverride.formatWaterColor(shown));
			};
			showWaterColor.run();
			waterColor.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent event)
				{
					if (!SwingUtilities.isLeftMouseButton(event)) return; // right-click shows Reset
					Color start = override.customWaterColor() != null ? override.customWaterColor() : plugin.mainWaterColor();
					net.runelite.client.ui.components.colorpicker.RuneliteColorPicker picker =
						plugin.colorPickerManager().create(RegionOverridePanel.this, start, "Water color", true);
					picker.setLocationRelativeTo(waterColor);
					picker.setOnColorChange(color ->
					{
						waterColor.setColor(color);
						waterColor.setText(RegionOverride.formatWaterColor(color));
					});
					picker.setOnClose(color ->
					{
						override.waterColor = RegionOverride.formatWaterColor(color);
						showWaterColor.run();
						persist();
					});
					picker.setVisible(true);
				}
			});
			// Reset to the default water color (button, or right-click like the config panel).
			Runnable resetWaterColor = () ->
			{
				override.waterColor = RegionOverride.formatWaterColor(com.endrit.gpusky.water.WaterConfig.DEFAULT_WATER_COLOR);
				showWaterColor.run();
				persist();
			};
			javax.swing.JPopupMenu waterColorMenu = new javax.swing.JPopupMenu();
			javax.swing.JMenuItem resetItem = new javax.swing.JMenuItem("Reset");
			resetItem.addActionListener(event -> resetWaterColor.run());
			waterColorMenu.add(resetItem);
			waterColor.setComponentPopupMenu(waterColorMenu);
			JButton resetWater = iconButton("\u21ba", "Reset to the default water color");
			resetWater.addActionListener(event -> resetWaterColor.run());
			JPanel waterColorRow = new JPanel(new BorderLayout(4, 0));
			waterColorRow.setOpaque(false);
			waterColorRow.add(waterColor, BorderLayout.CENTER);
			waterColorRow.add(resetWater, BorderLayout.EAST);
			addSettingField(customWaterSettings, 0, "Water", waterColorRow);
			GridBagConstraints customWaterConstraints = new GridBagConstraints();
			customWaterConstraints.gridx = 0;
			customWaterConstraints.gridy = 11;
			customWaterConstraints.gridwidth = 2;
			customWaterConstraints.weightx = 1;
			customWaterConstraints.fill = GridBagConstraints.HORIZONTAL;
			body.add(customWaterSettings, customWaterConstraints);
			customWaterSettings.setVisible(override.customWaterColor() != null);
			inheritWater.addActionListener(event ->
			{
				// Unticking starts from the current main color; ticking clears the custom color.
				override.waterColor = inheritWater.isSelected() ? "" : RegionOverride.formatWaterColor(plugin.mainWaterColor());
				customWaterSettings.setVisible(!inheritWater.isSelected());
				showWaterColor.run();
				persist();
				body.revalidate();
			});
		}

		private void appendCurrentRegion(JTextArea regions)
		{
			int regionId = plugin.currentRegionId();
			if (regionId < 0)
			{
				return;
			}
			String value = regions.getText().trim();
			for (String token : value.split("[,\\s]+"))
			{
				if (Integer.toString(regionId).equals(token))
				{
					return;
				}
			}
			String existingOverride = plugin.regionOverrideUsing(regionId, override.id);
			if (existingOverride != null)
			{
				Object[] options = {"Cancel", "Override"};
				int choice = JOptionPane.showOptionDialog(this,
					"Region " + regionId + " is already used by \"" + existingOverride + "\".",
					"Region already assigned", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
					null, options, options[0]);
				if (choice == 1)
				{
					plugin.reassignRegion(regionId, override.id);
					reload();
				}
				return;
			}
			regions.setText(formatRegionIds(value + " " + regionId));
		}

		private void removeCurrentRegion(JTextArea regions)
		{
			int regionId = plugin.currentRegionId();
			if (regionId < 0)
			{
				return;
			}
			String current = Integer.toString(regionId);
			StringBuilder remaining = new StringBuilder();
			for (String token : regions.getText().trim().split("[,\\s]+"))
			{
				if (!token.isEmpty() && !current.equals(token))
				{
					if (remaining.length() > 0)
					{
						remaining.append('\n');
					}
					remaining.append(token);
				}
			}
			regions.setText(formatRegionIds(remaining.toString()));
		}

		private void validateManualRegionAssignments(JTextArea regions)
		{
			List<Integer> conflicts = new ArrayList<>();
			Set<Integer> seen = new HashSet<>();
			for (String token : regions.getText().trim().split("[,\\s]+"))
			{
				try
				{
					int regionId = Integer.parseInt(token);
					if (regionId >= 0 && regionId <= 65535 && seen.add(regionId)
						&& plugin.regionOverrideUsing(regionId, override.id) != null)
					{
						conflicts.add(regionId);
					}
				}
				catch (NumberFormatException ignored)
				{
					// Invalid text remains visible but cannot conflict with a region assignment.
				}
			}

			if (conflicts.isEmpty())
			{
				return;
			}

			Set<Integer> rejected = new HashSet<>();
			boolean reassigned = false;
			for (int regionId : conflicts)
			{
				String existingOverride = plugin.regionOverrideUsing(regionId, override.id);
				if (existingOverride == null)
				{
					continue;
				}
				Object[] options = {"Keep existing", "Move here"};
				int choice = JOptionPane.showOptionDialog(this,
					"Region " + regionId + " is already used by \"" + existingOverride + "\".",
					"Region already assigned", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
					null, options, options[0]);
				if (choice == 1)
				{
					plugin.reassignRegion(regionId, override.id);
					reassigned = true;
				}
				else
				{
					rejected.add(regionId);
				}
			}

			if (!rejected.isEmpty())
			{
				StringBuilder remaining = new StringBuilder();
				for (String token : regions.getText().trim().split("[,\\s]+"))
				{
					boolean remove = false;
					try
					{
						remove = rejected.contains(Integer.parseInt(token));
					}
					catch (NumberFormatException ignored)
					{
						// Preserve invalid text so the user can correct it.
					}
					if (!remove)
					{
						if (remaining.length() > 0)
						{
							remaining.append(' ');
						}
						remaining.append(token);
					}
				}
				regions.setText(formatRegionIds(remaining.toString()));
			}
			if (reassigned)
			{
				reload();
			}
		}

		private void updateRegionRows(JTextArea regions, JScrollPane regionScroll)
		{
			String value = regions.getText().trim();
			int count = value.isEmpty() ? 1 : value.split("[,\\s]+").length;
			int rows = Math.max(1, (count + 1) / 2);
			if (!expandedRegionLists.contains(override.id) && !regionListTemporarilyExpanded)
			{
				rows = Math.min(3, rows);
			}
			if (regions.getRows() != rows)
			{
				regions.setRows(rows);
				Dimension viewportSize = regions.getPreferredScrollableViewportSize();
				regionScroll.setPreferredSize(new Dimension(viewportSize.width, viewportSize.height));
				regionScroll.setMinimumSize(new Dimension(0, viewportSize.height));
				regions.revalidate();
				regionScroll.revalidate();
				regionScroll.getParent().revalidate();
				body.revalidate();
				OverrideEditor.this.revalidate();
				list.revalidate();
			}
		}

		private String formatRegionIds(String value)
		{
			StringBuilder formatted = new StringBuilder();
			int index = 0;
			for (String token : value.trim().split("[,\\s]+"))
			{
				if (token.isEmpty())
				{
					continue;
				}
				if (index > 0)
				{
					formatted.append(index % 2 == 0 ? '\n' : ' ');
				}
				formatted.append(token).append(',');
				index++;
			}
			return formatted.toString();
		}

		private void addSettingField(JPanel panel, int row, String labelText, Component field)
		{
			setControlHeight(field, 30);
			GridBagConstraints labelConstraints = new GridBagConstraints();
			labelConstraints.gridx = 0;
			labelConstraints.gridy = row;
			labelConstraints.anchor = GridBagConstraints.WEST;
			labelConstraints.insets = new Insets(2, 0, 2, 6);
			JLabel label = new JLabel(labelText);
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			panel.add(label, labelConstraints);

			GridBagConstraints fieldConstraints = new GridBagConstraints();
			fieldConstraints.gridx = 1;
			fieldConstraints.gridy = row;
			fieldConstraints.weightx = 1;
			fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
			fieldConstraints.insets = new Insets(2, 0, 2, 0);
			panel.add(field, fieldConstraints);
		}

		private void addField(int row, String labelText, Component field)
		{
			addField(row, labelText, field, false);
		}

		private void addField(int row, String labelText, Component field, boolean alignTop)
		{
			JLabel label = new JLabel(labelText);
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			addField(row, label, field, alignTop);
		}

		private void addField(int row, Component label, Component field, boolean alignTop)
		{
			if (!alignTop)
			{
				setControlHeight(field, 30);
			}
			GridBagConstraints labelConstraints = new GridBagConstraints();
			labelConstraints.gridx = 0;
			labelConstraints.gridy = row;
			labelConstraints.anchor = alignTop ? GridBagConstraints.NORTHWEST : GridBagConstraints.WEST;
			labelConstraints.insets = alignTop ? new Insets(7, 0, 3, 6) : new Insets(3, 0, 3, 6);
			body.add(label, labelConstraints);

			GridBagConstraints fieldConstraints = new GridBagConstraints();
			fieldConstraints.gridx = 1;
			fieldConstraints.gridy = row;
			fieldConstraints.weightx = 1;
			fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
			fieldConstraints.insets = new Insets(3, 0, 3, 0);
			body.add(field, fieldConstraints);
		}

		private void setControlHeight(Component component, int height)
		{
			Dimension preferred = component.getPreferredSize();
			component.setPreferredSize(new Dimension(preferred.width, height));
			component.setMinimumSize(new Dimension(0, height));
			if (component instanceof JComboBox)
			{
				component.setBackground(FIELD_COLOR);
			}
			else if (component instanceof JSpinner)
			{
				JSpinner spinner = (JSpinner) component;
				spinner.setBackground(FIELD_COLOR);
				if (spinner.getEditor() instanceof JSpinner.DefaultEditor)
				{
					((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setBackground(FIELD_COLOR);
				}
			}
		}

		private void chooseSkybox(JTextField skybox)
		{
			Filepath.Chooser chooser = new Filepath.Chooser().setIsOpen().setAcceptsFiles()
				.setDialogTitle("Choose region panorama")
				.addExtensionFilter("Panorama images", "png", "jpg", "jpeg");
			Filepath directory = plugin.skyboxDirectory();
			if (directory != null)
			{
				chooser.setCurrentDirectory(directory);
			}
			List<Filepath> selected = chooser.showDialog(RegionOverridePanel.this);
			if (selected == null || selected.isEmpty())
			{
				return;
			}
			int request = ++importRequest;
			skybox.setEnabled(false);
			plugin.importSkybox(selected.get(0), override.id).whenComplete((path, failure) ->
				SwingUtilities.invokeLater(() ->
			{
				if (disposed || request != importRequest
					|| !SwingUtilities.isDescendingFrom(skybox, RegionOverridePanel.this))
				{
					return;
				}
				skybox.setEnabled(true);
				if (failure == null)
				{
					skybox.setText(path);
				}
				else
				{
					Throwable cause = failure.getCause() == null ? failure : failure.getCause();
					JOptionPane.showMessageDialog(RegionOverridePanel.this, cause.getMessage(),
						"Unable to import panorama", JOptionPane.ERROR_MESSAGE);
				}
			}));
		}

		private void toggleExpanded()
		{
			setExpanded(!body.isVisible());
		}

		private void setExpanded(boolean value)
		{
			body.setVisible(value);
			expandButton.setText(value ? "\u25be" : "\u25b8");
			expandButton.setToolTipText(value ? "Collapse override" : "Expand override");
			if (value)
			{
				expanded.add(override.id);
			}
			else
			{
				expanded.remove(override.id);
			}
			revalidate();
		}

		private void updateEnabledButton()
		{
			enabledButton.setText(override.enabled ? "\u25cf" : "\u25cb");
			enabledButton.setForeground(override.enabled
				? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
			enabledButton.setToolTipText(override.enabled ? "Disable override" : "Enable override");
		}

		private String displayName()
		{
			String name = override.name == null ? "" : override.name.trim();
			return name.isEmpty() ? "Unnamed override" : name;
		}

		private void persist()
		{
			plugin.updateRegionOverride(override);
		}
	}

	private static JTextField textField(String value)
	{
		JTextField field = new JTextField(value == null ? "" : value);
		field.setBackground(FIELD_COLOR);
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		field.setBorder(new EmptyBorder(4, 5, 4, 5));
		return field;
	}

	private static JSpinner percentageSpinner(int value)
	{
		return percentageSpinner(value, JTextField.RIGHT);
	}

	private static JSpinner percentageSpinner(int value, int horizontalAlignment)
	{
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, 0, 200, 5));
		JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "0");
		NumberFormatter formatter = new NumberFormatter(new DecimalFormat("0"))
		{
			@Override
			public Object stringToValue(String text) throws ParseException
			{
				return super.stringToValue(text.replace("%", "").trim());
			}

			@Override
			public String valueToString(Object value) throws ParseException
			{
				return value == null ? "" : super.valueToString(value) + "%";
			}
		};
		formatter.setValueClass(Integer.class);
		formatter.setMinimum(0);
		formatter.setMaximum(200);
		formatter.setAllowsInvalid(true);
		JFormattedTextField field = editor.getTextField();
		field.setFormatterFactory(new DefaultFormatterFactory(formatter));
		field.setHorizontalAlignment(horizontalAlignment);
		spinner.setEditor(editor);
		return spinner;
	}

	private static JTextArea regionArea(String value)
	{
		JTextArea field = new JTextArea(value == null ? "" : value, 1, 1);
		field.setLineWrap(true);
		field.setWrapStyleWord(true);
		field.setBackground(FIELD_COLOR);
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		field.setBorder(new EmptyBorder(4, 5, 4, 5));
		return field;
	}

	private static JPanel compoundField(Component center)
	{
		JPanel panel = new JPanel(new BorderLayout(2, 0));
		panel.setOpaque(false);
		panel.add(center, BorderLayout.CENTER);
		return panel;
	}

	private static JPanel fieldLabel(String text, Component action)
	{
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
		panel.setOpaque(false);
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(label);
		panel.add(action);
		return panel;
	}

	private static JButton iconButton(String text, String tooltip)
	{
		JButton button = new JButton(text);
		button.setToolTipText(tooltip);
		button.setPreferredSize(ICON_SIZE);
		button.setMinimumSize(ICON_SIZE);
		button.setMaximumSize(ICON_SIZE);
		button.setFocusable(false);
		button.setMargin(new Insets(0, 0, 0, 0));
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setForeground(Color.WHITE);
		return button;
	}

	private static JButton rotatedPlusButton(String tooltip)
	{
		JButton button = new JButton("+")
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D rotated = (Graphics2D) graphics.create();
				rotated.rotate(Math.PI / 4d, getWidth() / 2d, getHeight() / 2d);
				super.paintComponent(rotated);
				rotated.dispose();
			}
		};
		button.setToolTipText(tooltip);
		button.setPreferredSize(ICON_SIZE);
		button.setMinimumSize(ICON_SIZE);
		button.setMaximumSize(ICON_SIZE);
		button.setFocusable(false);
		button.setMargin(new Insets(0, 0, 0, 0));
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setForeground(Color.WHITE);
		return button;
	}

	private static JButton regionButton(String text, String tooltip, Color foreground)
	{
		JButton button = iconButton(text, tooltip);
		button.setForeground(foreground);
		button.setBackground(CARD_COLOR);
		button.setOpaque(true);
		button.setContentAreaFilled(true);
		button.setBorderPainted(true);
		button.setBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR));
		return button;
	}

	private static DocumentListener documentListener(Runnable action)
	{
		return new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				action.run();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				action.run();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				action.run();
			}
		};
	}
}
