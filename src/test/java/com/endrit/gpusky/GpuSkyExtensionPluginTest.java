package com.endrit.gpusky;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GpuSkyExtensionPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GpuSkyExtensionPlugin.class);
		RuneLite.main(args);
	}
}
