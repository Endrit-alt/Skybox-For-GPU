package com.endrit.gpusky;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.runelite.api.Client;

final class SkyboxLoadingOverlay extends JComponent
{
	private static final String EIGHT_K_TITLE = "Loading 8K skybox textures...";
	private static final String EIGHT_K_DETAIL =
		"Choose 4K or 2K Texture resolution in Skybox for GPU settings for faster loading.";
	private static final String FOUR_K_TITLE = "Loading %s skybox presets...";
	private static final String FOUR_K_DETAIL =
		"Disable Pre-load skybox presets in Skybox for GPU settings to load them on demand.";
	private static final String WAITING_TITLE = "Skybox preloading is waiting for GPU...";
	private static final String WAITING_DETAIL = "Enable the GPU plugin to finish preloading skybox presets.";
	private static final int PADDING = 8;
	private static final int LINE_GAP = 2;
	private static final Color BACKGROUND = new Color(0, 0, 0, 190);
	private static final Color TITLE_COLOR = Color.WHITE;
	private static final Color DETAIL_COLOR = new Color(210, 210, 210);

	private final Client client;
	private final GpuSkyExtensionPlugin plugin;
	private final ComponentAdapter positionListener = new ComponentAdapter()
	{
		@Override
		public void componentResized(ComponentEvent event)
		{
			positionWindow();
		}

		@Override
		public void componentMoved(ComponentEvent event)
		{
			positionWindow();
		}

		@Override
		public void componentShown(ComponentEvent event)
		{
			refreshVisibility();
		}

		@Override
		public void componentHidden(ComponentEvent event)
		{
			refreshVisibility();
		}
	};
	private final HierarchyListener showingListener = event ->
	{
		if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0)
		{
			refreshVisibility();
		}
	};
	private Canvas canvas;
	private Window owner;
	private JWindow window;

	SkyboxLoadingOverlay(Client client, GpuSkyExtensionPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setOpaque(false);
		setFocusable(false);
	}

	void install()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::install);
			return;
		}

		if (window != null)
		{
			refreshVisibility();
			return;
		}

		canvas = client.getCanvas();
		owner = SwingUtilities.getWindowAncestor(canvas);
		if (owner == null)
		{
			return;
		}

		window = new JWindow(owner);
		window.setType(Window.Type.POPUP);
		window.setFocusableWindowState(false);
		window.setAutoRequestFocus(false);
		window.setBackground(new Color(0, 0, 0, 0));
		window.getContentPane().add(this);
		setPreferredSize(noticeSize());
		window.pack();

		canvas.addComponentListener(positionListener);
		canvas.addHierarchyListener(showingListener);
		owner.addComponentListener(positionListener);
		positionWindow();
		refreshVisibility();
	}

	void uninstall()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::uninstall);
			return;
		}

		Canvas currentCanvas = canvas;
		Window currentOwner = owner;
		JWindow currentWindow = window;
		canvas = null;
		owner = null;
		window = null;
		if (currentCanvas != null)
		{
			currentCanvas.removeComponentListener(positionListener);
			currentCanvas.removeHierarchyListener(showingListener);
		}
		if (currentOwner != null)
		{
			currentOwner.removeComponentListener(positionListener);
		}
		if (currentWindow != null)
		{
			currentWindow.setVisible(false);
			currentWindow.dispose();
		}
	}

	void refreshVisibility()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refreshVisibility);
			return;
		}

		JWindow currentWindow = window;
		Canvas currentCanvas = canvas;
		Window currentOwner = owner;
		if (currentWindow == null || currentCanvas == null || currentOwner == null)
		{
			return;
		}

		boolean loading = plugin.preloadStatus() != GpuSkyExtensionPlugin.PreloadStatus.IDLE
			&& currentCanvas.isShowing()
			&& currentOwner.isShowing();
		if (loading)
		{
			positionWindow();
			currentWindow.setVisible(true);
			repaint();
		}
		else
		{
			currentWindow.setVisible(false);
		}
	}

	private void positionWindow()
	{
		JWindow currentWindow = window;
		Canvas currentCanvas = canvas;
		if (currentWindow == null || currentCanvas == null || !currentCanvas.isShowing())
		{
			return;
		}

		Point canvasLocation = currentCanvas.getLocationOnScreen();
		int x = canvasLocation.x + Math.max(0, (currentCanvas.getWidth() - currentWindow.getWidth()) / 2);
		int y = canvasLocation.y + Math.max(0, (currentCanvas.getHeight() - currentWindow.getHeight()) / 2);
		currentWindow.setLocation(x, y);
	}

	private Dimension noticeSize()
	{
		Font baseFont = loadingFont();
		FontMetrics titleMetrics = getFontMetrics(baseFont.deriveFont(Font.BOLD));
		FontMetrics detailMetrics = getFontMetrics(
			baseFont.deriveFont(Font.PLAIN, Math.max(10f, baseFont.getSize2D() - 1f)));
		int width = Math.max(
			Math.max(titleMetrics.stringWidth(WAITING_TITLE),
				Math.max(titleMetrics.stringWidth(EIGHT_K_TITLE), titleMetrics.stringWidth(FOUR_K_TITLE))),
			Math.max(detailMetrics.stringWidth(WAITING_DETAIL),
				Math.max(detailMetrics.stringWidth(EIGHT_K_DETAIL), detailMetrics.stringWidth(FOUR_K_DETAIL))))
			+ PADDING * 2;
		int height = titleMetrics.getHeight() + detailMetrics.getHeight() + LINE_GAP + PADDING * 2;
		return new Dimension(width, height);
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		GpuSkyExtensionPlugin.PreloadStatus status = plugin.preloadStatus();
		if (status == GpuSkyExtensionPlugin.PreloadStatus.IDLE)
		{
			return;
		}
		boolean loadingEightK = plugin.isLoadingEightKPresets();
		boolean waiting = status == GpuSkyExtensionPlugin.PreloadStatus.WAITING_FOR_GPU;
		String title = waiting ? WAITING_TITLE : loadingEightK ? EIGHT_K_TITLE
			: String.format(FOUR_K_TITLE, plugin.textureResolutionName());
		String detail = waiting ? WAITING_DETAIL : loadingEightK ? EIGHT_K_DETAIL : FOUR_K_DETAIL;

		Graphics2D graphics2D = (Graphics2D) graphics.create();
		Font baseFont = loadingFont();
		Font titleFont = baseFont.deriveFont(Font.BOLD);
		Font detailFont = baseFont.deriveFont(Font.PLAIN, Math.max(10f, baseFont.getSize2D() - 1f));
		graphics2D.setFont(titleFont);
		FontMetrics titleMetrics = graphics2D.getFontMetrics();
		graphics2D.setFont(detailFont);
		FontMetrics detailMetrics = graphics2D.getFontMetrics();

		graphics2D.setColor(BACKGROUND);
		graphics2D.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
		int titleY = PADDING + titleMetrics.getAscent();
		graphics2D.setFont(titleFont);
		graphics2D.setColor(TITLE_COLOR);
		graphics2D.drawString(title, PADDING, titleY);
		graphics2D.setFont(detailFont);
		graphics2D.setColor(DETAIL_COLOR);
		graphics2D.drawString(detail, PADDING,
			titleY + titleMetrics.getDescent() + LINE_GAP + detailMetrics.getAscent());
		graphics2D.dispose();
	}

	private static Font loadingFont()
	{
		Font font = UIManager.getFont("Label.font");
		return font == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : font;
	}
}
