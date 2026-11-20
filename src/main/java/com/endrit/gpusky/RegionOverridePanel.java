package com.endrit.gpusky;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
import javax.swing.text.JTextComponent;
import javax.swing.text.NumberFormatter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.util.Filepath;

final class RegionOverridePanel extends PluginPanel
{
	private static final Color CARD_COLOR = new Color(38, 38, 38);
	private static final Color FIELD_COLOR = new Color(30, 30, 30);
	private static final Dimension ICON_SIZE = new Dimension(24, 24);
	/** Typed names and region IDs are saved after this pause (and at once on Enter or focus loss). */
	static final int TEXT_SAVE_DELAY_MS = 400;
	private static final int MAX_LISTED_CONFLICTS = 12;

	/** Asks whether duplicate region IDs move to this override. Replaced in tests, where no dialog can open. */
	interface ConflictPrompt
	{
		boolean moveHere(String message);
	}

	private final GpuSkyExtensionPlugin plugin;
	private final IconTextField search = new IconTextField();
	private final JComboBox<SortOrder> sortOrder = new JComboBox<>(SortOrder.values());
	private final JPanel list = new JPanel();
	private final Set<String> expanded = new HashSet<>();
	private final Set<String> expandedRegionLists = new HashSet<>();
	private final Set<String> newlyAddedOverrides = new HashSet<>();
	/** Overrides with a panorama import running; it survives reloads, unlike the editors. */
	private final Set<String> importsInProgress = new HashSet<>();
	/** Regions the recorder left alone because another override uses them, with that override's name. */
	private final Map<Integer, String> skippedRegions = new LinkedHashMap<>();
	private final Timer regionRecorder = new Timer(250, event -> recordCurrentRegion());
	private final AWTEventListener outsideRegionClickListener = this::onGlobalMouseEvent;
	private final JButton addButton;
	private final JPanel unreadableNotice = new JPanel(new BorderLayout(0, 4));
	private final JLabel recordingNote = new JLabel();
	private Component temporarilyExpandedRegionField;
	private Runnable temporaryRegionCollapse;
	private String recordingOverrideId;
	private long recordingProfile;
	private int lastRecordedRegion = -1;
	/** Bumped by every reload; editors from an older reload are no longer shown. */
	private int generation;
	private RuneliteColorPicker openPicker;
	private boolean disposed;
	/** The editors of the latest reload. */
	private final List<OverrideEditor> editors = new ArrayList<>();
	/** A duplicate-region dialog is open; another check does not start meanwhile. */
	private boolean validatingRegions;
	ConflictPrompt conflictPrompt = this::askToMoveRegions;

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
		addButton = iconButton("+", "Add region override");
		addButton.addActionListener(event ->
		{
			RegionOverride created = plugin.addRegionOverride(plugin.profileId());
			if (created != null)
			{
				newlyAddedOverrides.add(created.id);
				expanded.add(created.id);
			}
			reload();
		});
		header.add(addButton, BorderLayout.EAST);
		add(header);

		// Shown instead of the list when the saved overrides could not be read.
		unreadableNotice.setBackground(CARD_COLOR);
		unreadableNotice.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.PROGRESS_ERROR_COLOR), new EmptyBorder(6, 6, 6, 6)));
		JLabel unreadableText = new JLabel("<html>Your saved region overrides could not be read. A copy was kept in "
			+ "the setting '" + GpuSkyExtensionPlugin.UNREADABLE_OVERRIDES_KEY + "', and editing is paused so "
			+ "it is not overwritten.</html>");
		unreadableText.setForeground(Color.WHITE);
		unreadableNotice.add(unreadableText, BorderLayout.CENTER);
		JButton startOver = new JButton("Start over");
		startOver.setFocusable(false);
		startOver.addActionListener(event ->
		{
			long profile = plugin.profileId();
			int choice = JOptionPane.showConfirmDialog(this,
				"Replace the unreadable overrides with the default list? The copy in '"
					+ GpuSkyExtensionPlugin.UNREADABLE_OVERRIDES_KEY + "' is kept.",
				"Start over", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				plugin.discardUnreadableOverrides(profile);
			}
		});
		unreadableNotice.add(startOver, BorderLayout.SOUTH);
		unreadableNotice.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		unreadableNotice.setVisible(false);
		add(unreadableNotice);

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

		// Regions the recorder skipped, instead of a dialog popping up while playing.
		recordingNote.setForeground(ColorScheme.BRAND_ORANGE);
		recordingNote.setBorder(new EmptyBorder(0, 2, 4, 2));
		recordingNote.setVisible(false);
		add(recordingNote);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(list);
		reload();
	}

	void reload()
	{
		commitFocusedSpinner();
		// Typed text not yet saved is saved now if it is still for the active profile (the plugin
		// also checks the override exists); text typed for another profile is dropped.
		for (OverrideEditor editor : editors)
		{
			editor.flushPending();
		}
		editors.clear();
		generation++;
		collapseTemporaryRegionList();
		list.removeAll();
		boolean unreadable = plugin.overridesUnreadable();
		unreadableNotice.setVisible(unreadable);
		addButton.setEnabled(!unreadable);
		updateRecordingNote();
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		// Profile before the list, matching the plugin, so a switch in between only makes edits refused.
		long profile = plugin.profileId();
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
			OverrideEditor editor = new OverrideEditor(override, profile);
			editors.add(editor);
			list.add(editor);
			list.add(Box.createVerticalStrut(4));
			matches++;
		}

		if (matches == 0 && !unreadable)
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
		commitFocusedSpinner();
		// Same checked save as a reload; nothing is saved for another profile or a removed override.
		for (OverrideEditor editor : editors)
		{
			editor.flushPending();
		}
		editors.clear();
		disposed = true;
		collapseTemporaryRegionList();
		Toolkit.getDefaultToolkit().removeAWTEventListener(outsideRegionClickListener);
		stopRecording();
		RuneliteColorPicker picker = openPicker;
		openPicker = null;
		if (picker != null)
		{
			// Closed without saving: dispose does not run the picker's close callback.
			picker.dispose();
		}
	}

	void stopRecording()
	{
		regionRecorder.stop();
		recordingOverrideId = null;
		lastRecordedRegion = -1;
	}

	/** The note stays after recording stops, so it can still be acted on, until the next recording. */
	private void clearSkippedRegions()
	{
		skippedRegions.clear();
		updateRecordingNote();
	}

	private void updateRecordingNote()
	{
		if (skippedRegions.isEmpty())
		{
			recordingNote.setText("");
			recordingNote.setVisible(false);
			return;
		}
		StringBuilder text = new StringBuilder("<html>Skipped while recording, already used by another override:");
		for (Map.Entry<Integer, String> skipped : skippedRegions.entrySet())
		{
			text.append("<br>Region ").append(skipped.getKey()).append(" (\"")
				.append(escapeHtml(skipped.getValue())).append("\")");
		}
		text.append("<br>To move one, stand in it and press + beside the override's region IDs.</html>");
		recordingNote.setText(text.toString());
		recordingNote.setVisible(true);
	}

	private static String escapeHtml(String value)
	{
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Text of the skipped-regions note, for tests. */
	String recordingNoteText()
	{
		return recordingNote.isVisible() ? recordingNote.getText() : "";
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
			stopRecording();
			clearSkippedRegions();
			recordingOverrideId = overrideId;
			recordingProfile = plugin.profileId();
			regionRecorder.start();
			recordCurrentRegion();
		}
		reload();
	}

	void recordCurrentRegion()
	{
		if (recordingOverrideId == null)
		{
			return;
		}
		if (plugin.profileId() != recordingProfile)
		{
			// The override belongs to the previous profile; never record into the new one.
			stopRecording();
			clearSkippedRegions();
			reload();
			return;
		}
		int regionId = plugin.currentRegionId();
		if (regionId < 0 || regionId == lastRecordedRegion)
		{
			return;
		}
		lastRecordedRegion = regionId;
		// Typed but unsaved IDs count too: save them before looking for the region's owner.
		flushAllPending();

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
			// No dialog: this runs on a timer while the player is moving around.
			if (!existingOverride.equals(skippedRegions.put(regionId, existingOverride)))
			{
				updateRecordingNote();
				revalidate();
				repaint();
			}
			return;
		}
		skippedRegions.remove(regionId);
		plugin.reassignRegion(regionId, recordingOverrideId, recordingProfile);
		reload();
	}

	/**
	 * A number typed into a spinner is only applied on Enter or focus loss. Before the editors
	 * are replaced, apply a valid one now; it is saved through the editor's usual profile and
	 * ID checks, so a value typed for another profile is still dropped. Invalid text is ignored.
	 */
	private void commitFocusedSpinner()
	{
		Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if (focus == null)
		{
			return;
		}
		JSpinner spinner = focus instanceof JSpinner ? (JSpinner) focus
			: (JSpinner) SwingUtilities.getAncestorOfClass(JSpinner.class, focus);
		if (spinner == null || !SwingUtilities.isDescendingFrom(spinner, this))
		{
			return;
		}
		try
		{
			spinner.commitEdit();
		}
		catch (java.text.ParseException ignored)
		{
			// Not a valid number: nothing to save.
		}
	}

	/** Imports are tracked per profile, so the same built-in override in another profile is not locked. */
	private static String importKey(long profile, String id)
	{
		return profile + ":" + id;
	}

	/** Saves every editor's pending typing (checked), so conflict checks see what is shown. */
	private void flushAllPending()
	{
		for (OverrideEditor editor : editors)
		{
			editor.flushPending();
		}
	}

	private boolean askToMoveRegions(String message)
	{
		Object[] options = {"Keep existing", "Move here"};
		return JOptionPane.showOptionDialog(this, message, "Regions already assigned", JOptionPane.DEFAULT_OPTION,
			JOptionPane.WARNING_MESSAGE, null, options, options[0]) == 1;
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
		/** Profile this editor's override was read from; its edits are refused after a switch. */
		private final long profile;
		private final int editorGeneration = generation;
		private boolean regionListTemporarilyExpanded;
		/** Typed changes not yet saved, by field; each still a field-level change. */
		private final Map<String, Consumer<RegionOverride>> pending = new LinkedHashMap<>();
		private final Timer saveTimer = new Timer(TEXT_SAVE_DELAY_MS, event -> flushPending());
		/** Set while code (not the user) changes a field's text, so that is not saved as typing. */
		private boolean muted;

		private OverrideEditor(RegionOverride override, long profile)
		{
			this.override = override;
			this.profile = profile;
			saveTimer.setRepeats(false);
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
				boolean enabled = !override.enabled;
				edit(target -> target.enabled = enabled);
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
				plugin.removeRegionOverride(override.id, profile);
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
				if (muted)
				{
					return;
				}
				String text = name.getText();
				editLater("name", target -> target.name = text, true);
				if (!isDefaultOverrideName(override.name))
				{
					newlyAddedOverrides.remove(override.id);
				}
				nameLabel.setText(displayName());
			}));
			name.addActionListener(event -> flushPending());
			name.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent event)
				{
					flushPending();
					if (current() && initiallyDefaultName && !isDefaultOverrideName(override.name)
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
				if (!muted)
				{
					String text = regions.getText();
					editLater("regionIds", target -> target.regionIds = text, true);
				}
				updateRegionRows(regions, regionScroll);
			}));
			regions.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent event)
				{
					flushPending();
					// Alt-Tab, menus and dialogs only take focus for a moment: no check then.
					if (!event.isTemporary())
					{
						// After the click that moved focus has finished, which may itself reload.
						SwingUtilities.invokeLater(() -> validateManualRegionAssignments(regions));
					}
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
			browse.addActionListener(event -> chooseSkybox(skybox, browse));
			skyboxRow.add(browse, BorderLayout.EAST);
			boolean importing = importsInProgress.contains(importKey(profile, override.id));
			skybox.setEnabled(!importing);
			browse.setEnabled(!importing);
			skybox.getDocument().addDocumentListener(documentListener(() ->
			{
				if (muted)
				{
					return;
				}
				// A path is only tried once complete: saved on Enter or focus loss, not per key.
				String text = skybox.getText();
				editLater("skyboxFile", target -> target.skyboxFile = text, false);
			}));
			skybox.addActionListener(event -> flushPending());
			skybox.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent event)
				{
					// Not on a temporary loss: opening the file chooser must not save a half-typed path.
					if (!event.isTemporary())
					{
						flushPending();
					}
				}
			});
			addField(5, "Custom sky", skyboxRow);

			JComboBox<GpuSkyExtensionConfig.RegionWeatherOverride> weather =
				new JComboBox<>(GpuSkyExtensionConfig.RegionWeatherOverride.values());
			weather.setSelectedItem(override.weather);
			weather.setBackground(FIELD_COLOR);
			weather.setFocusable(false);
			weather.addActionListener(event ->
			{
				GpuSkyExtensionConfig.RegionWeatherOverride value =
					(GpuSkyExtensionConfig.RegionWeatherOverride) weather.getSelectedItem();
				edit(target -> target.weather = value);
			});
			addField(2, "Weather", weather);

			JSpinner intensity = percentageSpinner(override.weatherIntensity, JTextField.LEFT);
			intensity.addChangeListener(event ->
			{
				int value = (Integer) intensity.getValue();
				edit(target -> target.weatherIntensity = value);
			});
			addField(3, "Intensity", intensity);

			JComboBox<GpuSkyExtensionConfig.SkyboxImage> skyboxImage =
				new JComboBox<>(GpuSkyExtensionConfig.SkyboxImage.values());
			skyboxImage.setSelectedItem(override.skyboxImage);
			skyboxImage.setBackground(FIELD_COLOR);
			skyboxImage.setFocusable(false);
			skyboxImage.addActionListener(event ->
			{
				GpuSkyExtensionConfig.SkyboxImage value = (GpuSkyExtensionConfig.SkyboxImage) skyboxImage.getSelectedItem();
				edit(target -> target.skyboxImage = value);
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
				int value = (Integer) fov.getValue();
				edit(target -> target.horizontalFov = value);
			});
			addSettingField(customSkySettings, 0, "FOV", fov);
			JSpinner yaw = new JSpinner(new SpinnerNumberModel(override.yawOffset, -180, 180, 1));
			yaw.addChangeListener(event ->
			{
				int value = (Integer) yaw.getValue();
				edit(target -> target.yawOffset = value);
			});
			addSettingField(customSkySettings, 1, "Horizontal", yaw);
			JSpinner pitch = new JSpinner(new SpinnerNumberModel(override.pitchOffset, -10, 10, 1));
			pitch.addChangeListener(event ->
			{
				int value = (Integer) pitch.getValue();
				edit(target -> target.pitchOffset = value);
			});
			addSettingField(customSkySettings, 2, "Vertical", pitch);
			JSpinner exposure = new JSpinner(new SpinnerNumberModel(override.exposure, 10, 300, 5));
			exposure.addChangeListener(event ->
			{
				int value = (Integer) exposure.getValue();
				edit(target -> target.exposure = value);
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
				boolean value = inherit.isSelected();
				edit(target -> target.inheritSkySettings = value);
				customSkySettings.setVisible(!override.inheritSkySettings);
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
				int value = (Integer) worldLighting.getValue();
				edit(target -> target.worldLighting = value);
			});
			addSettingField(customLightSettings, 0, "World", worldLighting);
			JSpinner rimLighting = percentageSpinner(override.rimLighting);
			rimLighting.addChangeListener(event ->
			{
				int value = (Integer) rimLighting.getValue();
				edit(target -> target.rimLighting = value);
			});
			addSettingField(customLightSettings, 1, "Rim", rimLighting);
			JSpinner backlighting = percentageSpinner(override.backlighting);
			backlighting.addChangeListener(event ->
			{
				int value = (Integer) backlighting.getValue();
				edit(target -> target.backlighting = value);
			});
			addSettingField(customLightSettings, 2, "Backlight", backlighting);
			JSpinner skyboxTint = percentageSpinner(override.skyboxTint);
			skyboxTint.addChangeListener(event ->
			{
				int value = (Integer) skyboxTint.getValue();
				edit(target -> target.skyboxTint = value);
			});
			addSettingField(customLightSettings, 3, "Tint", skyboxTint);
			JSpinner atmosphere = percentageSpinner(override.atmosphere);
			atmosphere.addChangeListener(event ->
			{
				int value = (Integer) atmosphere.getValue();
				edit(target -> target.atmosphere = value);
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
				boolean value = inheritLights.isSelected();
				edit(target -> target.inheritLightSettings = value);
				customLightSettings.setVisible(!override.inheritLightSettings);
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
					RuneliteColorPicker picker =
						plugin.colorPickerManager().create(RegionOverridePanel.this, start, "Water color", true);
					openPicker = picker;
					picker.setLocationRelativeTo(waterColor);
					picker.setOnColorChange(color ->
					{
						waterColor.setColor(color);
						waterColor.setText(RegionOverride.formatWaterColor(color));
					});
					picker.setOnClose(color ->
					{
						if (openPicker == picker)
						{
							openPicker = null;
						}
						String value = RegionOverride.formatWaterColor(color);
						edit(target -> target.waterColor = value);
						showWaterColor.run();
						if (!current() && !disposed)
						{
							// The list was rebuilt while the picker was open; show the saved color there.
							reload();
						}
					});
					picker.setVisible(true);
				}
			});
			// Reset to the default water color (button, or right-click like the config panel).
			Runnable resetWaterColor = () ->
			{
				String value = RegionOverride.formatWaterColor(com.endrit.gpusky.water.WaterConfig.DEFAULT_WATER_COLOR);
				edit(target -> target.waterColor = value);
				showWaterColor.run();
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
				String value = inheritWater.isSelected() ? "" : RegionOverride.formatWaterColor(plugin.mainWaterColor());
				edit(target -> target.waterColor = value);
				customWaterSettings.setVisible(!inheritWater.isSelected());
				showWaterColor.run();
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
				if (RegionOverride.isRegion(token, regionId))
				{
					return;
				}
			}
			flushAllPending(); // Other overrides' typed IDs count as used.
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
					plugin.reassignRegion(regionId, override.id, profile);
					reload();
				}
				return;
			}
			setRegionIds(regions, formatRegionIds(value + " " + regionId));
		}

		/** A change made by a button, not typing: shown and saved once, with no empty intermediate save. */
		private void setRegionIds(JTextArea regions, String text)
		{
			setQuietly(regions, text);
			pending.remove("regionIds");
			edit(target -> target.regionIds = text);
		}

		private void removeCurrentRegion(JTextArea regions)
		{
			int regionId = plugin.currentRegionId();
			if (regionId < 0)
			{
				return;
			}
			StringBuilder remaining = new StringBuilder();
			for (String token : regions.getText().trim().split("[,\\s]+"))
			{
				if (!token.isEmpty() && !RegionOverride.isRegion(token, regionId))
				{
					if (remaining.length() > 0)
					{
						remaining.append('\n');
					}
					remaining.append(token);
				}
			}
			setRegionIds(regions, formatRegionIds(remaining.toString()));
		}

		/**
		 * One dialog for all region IDs in this field that another override already uses. Runs
		 * after a permanent focus loss, never while another such dialog is open, and checks the
		 * panel, profile and text again after the dialog, before changing anything.
		 */
		private void validateManualRegionAssignments(JTextArea regions)
		{
			// A replaced editor, or one for another profile, asks nothing.
			if (!current() || validatingRegions || plugin.profileId() != profile)
			{
				return;
			}
			// Saved lists then match what is shown, here and in the other editors.
			flushAllPending();
			String text = regions.getText();
			Map<Integer, String> conflicts = regionConflicts(text);
			if (conflicts.isEmpty())
			{
				return;
			}
			boolean moveHere;
			validatingRegions = true;
			try
			{
				moveHere = conflictPrompt.moveHere(conflictMessage(conflicts));
			}
			finally
			{
				validatingRegions = false;
			}
			if (!current() || plugin.profileId() != profile || !text.equals(regions.getText()))
			{
				return; // Rebuilt, switched or edited while the dialog was open.
			}
			Map<Integer, String> remaining = regionConflicts(text);
			if (remaining.isEmpty())
			{
				return;
			}
			if (moveHere)
			{
				for (int regionId : remaining.keySet())
				{
					plugin.reassignRegion(regionId, override.id, profile);
				}
				reload();
				return;
			}
			setRegionIds(regions, formatRegionIds(withoutRegions(text, remaining.keySet())));
		}

		private Map<Integer, String> regionConflicts(String text)
		{
			Map<Integer, String> conflicts = new LinkedHashMap<>();
			for (String token : text.trim().split("[,\\s]+"))
			{
				try
				{
					int regionId = Integer.parseInt(token);
					if (regionId >= 0 && regionId <= 65535 && !conflicts.containsKey(regionId))
					{
						String owner = plugin.regionOverrideUsing(regionId, override.id);
						if (owner != null)
						{
							conflicts.put(regionId, owner);
						}
					}
				}
				catch (NumberFormatException ignored)
				{
					// Invalid text remains visible but cannot conflict with a region assignment.
				}
			}
			return conflicts;
		}

		private String conflictMessage(Map<Integer, String> conflicts)
		{
			boolean one = conflicts.size() == 1;
			StringBuilder message = new StringBuilder(one
				? "This region is already used by another override:"
				: "These regions are already used by other overrides:");
			int listed = 0;
			for (Map.Entry<Integer, String> conflict : conflicts.entrySet())
			{
				if (listed++ == MAX_LISTED_CONFLICTS)
				{
					message.append("\n  ...and ").append(conflicts.size() - MAX_LISTED_CONFLICTS).append(" more");
					break;
				}
				message.append("\n  Region ").append(conflict.getKey())
					.append(" (\"").append(conflict.getValue()).append("\")");
			}
			message.append("\n\nMove ").append(one ? "it" : "them").append(" to \"").append(displayName())
				.append("\", or keep ").append(one ? "it where it is" : "them where they are").append('?');
			return message.toString();
		}

		private String withoutRegions(String text, Set<Integer> removed)
		{
			StringBuilder remaining = new StringBuilder();
			for (String token : text.trim().split("[,\\s]+"))
			{
				boolean remove = false;
				try
				{
					remove = removed.contains(Integer.parseInt(token));
				}
				catch (NumberFormatException ignored)
				{
					// Preserve invalid text so the user can correct it.
				}
				if (!remove && !token.isEmpty())
				{
					if (remaining.length() > 0)
					{
						remaining.append(' ');
					}
					remaining.append(token);
				}
			}
			return remaining.toString();
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

		private void chooseSkybox(JTextField skybox, JButton browse)
		{
			if (importsInProgress.contains(importKey(profile, override.id)))
			{
				return;
			}
			Filepath.Chooser chooser = new Filepath.Chooser().setIsOpen().setAcceptsFiles()
				.setDialogTitle("Choose region panorama")
				.addExtensionFilter("Panorama images", "png", "jpg", "jpeg");
			Filepath directory = plugin.skyboxDirectory();
			if (directory != null)
			{
				chooser.setCurrentDirectory(directory);
			}
			List<Filepath> selected = chooser.showDialog(RegionOverridePanel.this);
			if (selected == null || selected.isEmpty() || disposed)
			{
				return;
			}
			String id = override.id;
			// The chosen file replaces any path typed but not yet saved.
			pending.remove("skyboxFile");
			String importKey = importKey(profile, id);
			importsInProgress.add(importKey);
			skybox.setEnabled(false);
			browse.setEnabled(false);
			// The plugin assigns the imported file to the override by ID when it finishes.
			plugin.importSkybox(selected.get(0), id, profile).whenComplete((path, failure) ->
				SwingUtilities.invokeLater(() ->
			{
				importsInProgress.remove(importKey);
				if (disposed)
				{
					return;
				}
				// Only for the profile that started it; another profile never asked for this import.
				if (failure != null && plugin.profileId() == profile)
				{
					Throwable cause = failure.getCause() == null ? failure : failure.getCause();
					JOptionPane.showMessageDialog(RegionOverridePanel.this, cause.getMessage(),
						"Unable to import panorama", JOptionPane.ERROR_MESSAGE);
				}
				if (current())
				{
					skybox.setEnabled(true);
					browse.setEnabled(true);
					if (failure == null)
					{
						// The plugin already saved it by ID; showing it is not a new edit.
						override.skyboxFile = path;
						pending.remove("skyboxFile");
						setQuietly(skybox, path);
					}
					else
					{
						// Show what is actually saved (read fresh by ID), not the typed path that was dropped.
						for (RegionOverride saved : plugin.regionOverrides())
						{
							if (saved.id.equals(id))
							{
								override.skyboxFile = saved.skyboxFile;
								setQuietly(skybox, saved.skyboxFile == null ? "" : saved.skyboxFile);
								break;
							}
						}
					}
				}
				else
				{
					// This editor was replaced during the import; rebuild so the new one shows the result.
					reload();
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

		/** Whether this editor is from the latest reload, so it is the one shown. */
		private boolean current()
		{
			return editorGeneration == generation && !disposed;
		}

		/**
		 * Applies one field change to this editor's copy and to the saved override with the same
		 * ID, so the other fields are never written back from an older copy. The change must only
		 * assign values already worked out, because it runs on both copies.
		 */
		private void edit(Consumer<RegionOverride> change)
		{
			change.accept(override);
			if (!disposed)
			{
				plugin.updateRegionOverride(override.id, profile, change);
			}
		}

		/**
		 * Typing: shown in this editor at once, saved later as one field-level change: after
		 * TEXT_SAVE_DELAY_MS without typing ({@code timed}), or on Enter, focus loss or reload.
		 */
		private void editLater(String field, Consumer<RegionOverride> change, boolean timed)
		{
			change.accept(override);
			pending.put(field, change);
			if (timed)
			{
				saveTimer.restart();
			}
		}

		/** Saves pending typing; after a profile switch it is dropped, because it belongs to the other profile. */
		private void flushPending()
		{
			saveTimer.stop();
			if (pending.isEmpty())
			{
				return;
			}
			List<Consumer<RegionOverride>> changes = new ArrayList<>(pending.values());
			pending.clear();
			if (disposed || plugin.profileId() != profile)
			{
				return;
			}
			plugin.updateRegionOverride(override.id, profile, target ->
			{
				for (Consumer<RegionOverride> change : changes)
				{
					change.accept(target);
				}
			});
		}

		/** Sets text without it being saved as typing (the change is saved by the caller, if at all). */
		private void setQuietly(JTextComponent field, String text)
		{
			muted = true;
			try
			{
				field.setText(text);
			}
			finally
			{
				muted = false;
			}
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
