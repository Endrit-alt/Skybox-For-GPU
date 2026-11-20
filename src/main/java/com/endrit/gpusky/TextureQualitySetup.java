package com.endrit.gpusky;

import com.endrit.gpusky.GpuSkyExtensionConfig.TextureResolution;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.runelite.client.util.Filepath;

/** Non-modal first-enable setup. Header IO runs on the existing asset worker. */
final class TextureQualitySetup
{
	static final String COMPLETED_KEY = "textureQualitySetupCompleted";
	private final Component canvas;
	private final TextureResolution initial;
	private final Consumer<TextureResolution> save;
	private final Runnable refresh;
	private final AtomicBoolean preparing = new AtomicBoolean();
	private volatile boolean pending;
	private volatile boolean closed;
	private SkyboxMemoryEstimate estimate;
	private JDialog dialog;
	private boolean listening;
	private final HierarchyListener showingListener = event ->
	{
		if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) showWhenReady();
	};

	TextureQualitySetup(Component canvas, boolean completed, TextureResolution initial,
		Consumer<TextureResolution> save, Runnable refresh)
	{
		this.canvas = canvas;
		this.pending = !completed;
		this.initial = initial;
		this.save = save;
		this.refresh = refresh;
	}

	boolean isPending()
	{
		return pending && !closed;
	}

	void prepare(Filepath directory)
	{
		if (!isPending() || !preparing.compareAndSet(false, true)) return;
		SkyboxMemoryEstimate prepared = SkyboxMemoryEstimate.read(directory);
		SwingUtilities.invokeLater(() ->
		{
			if (!isPending()) return;
			estimate = prepared;
			canvas.addHierarchyListener(showingListener);
			listening = true;
			showWhenReady();
		});
	}

	private void showWhenReady()
	{
		if (!isPending() || dialog != null || estimate == null || !canvas.isShowing()) return;
		Window owner = SwingUtilities.getWindowAncestor(canvas);
		if (owner == null) return;
		Panel panel = new Panel(initial, estimate, this::accept);
		dialog = new JDialog(owner, "Skybox for GPU - Texture quality", Dialog.ModalityType.MODELESS);
		dialog.setUndecorated(true);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent event)
			{
				accept(TextureResolution.RES_4K);
			}
		});
		dialog.setContentPane(panel);
		dialog.getRootPane().setDefaultButton(panel.continueButton);
		dialog.setResizable(false);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		dialog.setVisible(true);
	}

	synchronized void accept(TextureResolution selection)
	{
		if (!isPending()) return;
		// Keep the loader gated until both config values have been saved.
		save.accept(selection);
		pending = false;
		SwingUtilities.invokeLater(this::disposeUi);
		refresh.run();
	}

	synchronized void close()
	{
		closed = true;
		SwingUtilities.invokeLater(this::disposeUi);
	}

	private void disposeUi()
	{
		if (listening)
		{
			canvas.removeHierarchyListener(showingListener);
			listening = false;
		}
		if (dialog != null)
		{
			dialog.dispose();
			dialog = null;
		}
	}

	static final class Panel extends JPanel
	{
		private static final Color BACKGROUND = new Color(35, 35, 35);
		private static final Color ACCENT = new Color(99, 178, 214);
		private TextureResolution selected;
		final JButton continueButton = new JButton("Continue");
		final JButton closeButton = new JButton(UIManager.getIcon("InternalFrame.closeIcon"));

		Panel(TextureResolution initial, SkyboxMemoryEstimate estimate, Consumer<TextureResolution> confirm)
		{
			this(initial, estimate, confirm, ImageLoader.panoramaFits(8192, 4096));
		}

		/** {@code eightKFits}: an 8K panorama fits RuneLite's current memory limit (else it loads at 4K). */
		Panel(TextureResolution initial, SkyboxMemoryEstimate estimate, Consumer<TextureResolution> confirm,
			boolean eightKFits)
		{
			super(new BorderLayout());
			selected = initial == null ? TextureResolution.RES_4K : initial;
			setBackground(BACKGROUND);
			setBorder(BorderFactory.createLineBorder(new Color(70, 70, 70)));
			JPanel header = new JPanel(new BorderLayout());
			header.setBackground(new Color(46, 46, 46));
			header.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
			JLabel title = new JLabel("Skybox texture quality", SwingConstants.CENTER);
			title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
			title.setForeground(Color.WHITE);
			closeButton.setPreferredSize(new Dimension(28, 28));
			closeButton.setContentAreaFilled(false);
			closeButton.setBorderPainted(false);
			closeButton.setForeground(Color.WHITE);
			if (closeButton.getIcon() == null) closeButton.setText("x");
			closeButton.setToolTipText("Close and use 4K (default)");
			closeButton.getAccessibleContext().setAccessibleName("Close");
			closeButton.addActionListener(event -> confirm.accept(TextureResolution.RES_4K));
			header.add(Box.createHorizontalStrut(28), BorderLayout.WEST);
			header.add(title, BorderLayout.CENTER);
			header.add(closeButton, BorderLayout.EAST);
			MouseAdapter drag = new MouseAdapter()
			{
				private Point offset;

				@Override
				public void mousePressed(MouseEvent event)
				{
					Window window = SwingUtilities.getWindowAncestor(header);
					offset = window == null || !SwingUtilities.isLeftMouseButton(event) ? null
						: SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), window);
				}

				@Override
				public void mouseDragged(MouseEvent event)
				{
					Window window = SwingUtilities.getWindowAncestor(header);
					if (window != null && offset != null)
					{
						window.setLocation(event.getXOnScreen() - offset.x, event.getYOnScreen() - offset.y);
					}
				}
			};
			header.addMouseListener(drag);
			header.addMouseMotionListener(drag);
			title.addMouseListener(drag);
			title.addMouseMotionListener(drag);
			add(header, BorderLayout.NORTH);
			JPanel body = new JPanel(new BorderLayout(0, 16));
			body.setOpaque(false);
			body.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
			JPanel choices = new JPanel(new GridLayout(1, 3, 10, 0));
			choices.setOpaque(false);
			ButtonGroup group = new ButtonGroup();
			for (TextureResolution resolution : TextureResolution.values())
			{
				String guidance = resolution == TextureResolution.RES_2K
					? "Smaller game windows<br>Lower-end graphics"
					: resolution == TextureResolution.RES_4K
					? "Most game windows<br>Balanced quality"
					: "Large, high-res windows<br>More graphics memory";
				String recommended = resolution == TextureResolution.RES_4K
					? "<font color='#9cdaa6'>(Recommended)</font>" : "&nbsp;";
				JRadioButton option = new JRadioButton("<html><div style='width:140px;text-align:center'>"
					+ "<span style='font-size:22pt'><b>" + resolution.shortName() + "</b></span><br>"
					+ recommended + "<br><br>" + guidance + "<br><br><b>" + estimate.label(resolution).replace("<", "&lt;")
					+ "</b></div></html>", resolution == selected);
				option.setActionCommand(resolution.name());
				option.setBackground(new Color(46, 46, 46));
				option.setForeground(new Color(225, 225, 225));
				option.setOpaque(true);
				option.setBorderPainted(true);
				option.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(option.isSelected() ? ACCENT : new Color(70, 70, 70), 2),
					BorderFactory.createEmptyBorder(12, 4, 12, 4)));
				option.addItemListener(event -> option.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(option.isSelected() ? ACCENT : new Color(70, 70, 70), 2),
					BorderFactory.createEmptyBorder(12, 4, 12, 4))));
				option.addActionListener(event -> selected = resolution);
				option.setToolTipText("RGBA sky textures including mipmaps. 1 GB = 1,000,000,000 bytes.");
				group.add(option);
				choices.add(option);
			}
			body.add(choices, BorderLayout.CENTER);
			JPanel footer = new JPanel(new BorderLayout(0, 10));
			footer.setOpaque(false);
			JLabel note = new JLabel("You can change this anytime in the plugin settings.", SwingConstants.CENTER);
			note.setForeground(new Color(190, 190, 190));
			footer.add(note, BorderLayout.CENTER);
			if (!eightKFits)
			{
				JLabel memoryNote = new JLabel("<html><div style='text-align:center'>RuneLite's current memory limit is "
					+ "too low to load 8K skies, so 8K would load at 4K.</div></html>", SwingConstants.CENTER);
				memoryNote.setName("eightKMemoryNote");
				memoryNote.setForeground(new Color(232, 163, 60));
				footer.add(memoryNote, BorderLayout.NORTH);
			}
			JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
			buttons.setOpaque(false);
			continueButton.addActionListener(event -> confirm.accept(selected));
			buttons.add(continueButton);
			footer.add(buttons, BorderLayout.SOUTH);
			body.add(footer, BorderLayout.SOUTH);
			add(body, BorderLayout.CENTER);
		}

		TextureResolution selected()
		{
			return selected;
		}
	}
}
