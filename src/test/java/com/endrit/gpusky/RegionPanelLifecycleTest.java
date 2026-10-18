package com.endrit.gpusky;

import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegionPanelLifecycleTest
{
	@Test
	public void deletingRecordingOverrideKeepsPanelListenerUntilDisposal() throws Exception
	{
		// This is an isolated Swing component test, not input into a running RuneLite client.
		SwingUtilities.invokeAndWait(() ->
		{
			FakePlugin plugin = new FakePlugin();
			RegionOverridePanel panel = new RegionOverridePanel(plugin);
			int installed = Toolkit.getDefaultToolkit().getAWTEventListeners().length;
			try
			{
				button(panel, "Record regions while exploring").doClick(0);
				button(panel, "Delete override").doClick(0);
				assertTrue(plugin.overrides.isEmpty());
				assertEquals(installed, Toolkit.getDefaultToolkit().getAWTEventListeners().length);
			}
			finally
			{
				panel.dispose();
			}
			assertEquals(installed - 1, Toolkit.getDefaultToolkit().getAWTEventListeners().length);
		});
	}

	private static JButton button(Container container, String tooltip)
	{
		for (Component child : container.getComponents())
		{
			if (child instanceof JButton && tooltip.equals(((JButton) child).getToolTipText()))
			{
				return (JButton) child;
			}
			if (child instanceof Container)
			{
				JButton found = button((Container) child, tooltip);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static final class FakePlugin extends GpuSkyExtensionPlugin
	{
		private final List<RegionOverride> overrides = new ArrayList<>();

		private FakePlugin()
		{
			overrides.add(RegionOverride.create("Test", 110, 160, 0, 110, 100, 100, 100, 100, 100));
		}

		@Override
		List<RegionOverride> regionOverrides()
		{
			return new ArrayList<>(overrides);
		}

		@Override
		void removeRegionOverride(String id)
		{
			overrides.removeIf(override -> override.id.equals(id));
		}
	}
}
