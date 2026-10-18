package com.endrit.gpusky;

import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SkyboxPathsTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void descriptorUsesManagedStorageWithoutLegacyMigration()
	{
		PluginDescriptor descriptor = GpuSkyExtensionPlugin.class.getAnnotation(PluginDescriptor.class);
		assertEquals("skybox-for-gpu", descriptor.internalName());
		assertEquals("", descriptor.legacyDataDirectory());
	}

	@Test
	public void relativeAndOwnedAbsolutePathsResolveAfterMigration()
	{
		Filepath home = Filepath.Unchecked.getRooted(temporary.getRoot().toPath());
		Filepath directory = home.join("plugin-data/skybox-for-gpu").rooted();
		String relative = "weather/presets/Daylight.jpg";
		Filepath expected = directory.join(relative);
		assertEquals(expected, GpuSkyExtensionPlugin.configuredSkyboxPath(directory, relative));
		assertEquals(expected, GpuSkyExtensionPlugin.configuredSkyboxPath(directory, expected.toString()));
		assertEquals(expected, GpuSkyExtensionPlugin.configuredSkyboxPath(directory,
			home.join("gpu-sky", relative).toString()));
	}

	@Test
	public void pathsCannotEscapeTheManagedDirectory()
	{
		Filepath home = Filepath.Unchecked.getRooted(temporary.getRoot().toPath());
		Filepath directory = home.join("plugin-data/skybox-for-gpu").rooted();
		assertThrows(IllegalArgumentException.class,
			() -> GpuSkyExtensionPlugin.configuredSkyboxPath(directory, "../outside.jpg"));
		assertThrows(IllegalArgumentException.class,
			() -> GpuSkyExtensionPlugin.configuredSkyboxPath(directory, home.join("outside.jpg").toString()));
		assertThrows(IllegalArgumentException.class,
			() -> GpuSkyExtensionPlugin.configuredSkyboxPath(directory,
				home.join("gpu-sky").toString() + "/../outside.jpg"));
	}
}
