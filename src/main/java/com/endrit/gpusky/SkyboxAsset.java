package com.endrit.gpusky;

import java.awt.image.BufferedImage;

/** Identity and lighting metadata for one immutable uploaded panorama; never owns pixels. */
final class SkyboxAsset
{
	final GpuSkyExtension.SkyAnalysis analysis;
	final float upperBrightness;
	/** Strength of the Sunlight/Moonlight glow on this sky (1 = full). */
	final float glowStrength;

	SkyboxAsset(BufferedImage image)
	{
		this(image, "");
	}

	/** @param file the sky's file name or path; some skies get a weaker glow */
	SkyboxAsset(BufferedImage image, String file)
	{
		glowStrength = SunHalo.skyStrength(file);
		analysis = GpuSkyExtension.analyzeSkySource(image);
		upperBrightness = GpuSkyExtension.findUpperSkyBrightness(image);
	}
}
