package com.endrit.gpusky;

import java.awt.image.BufferedImage;

/** Identity and lighting metadata for one immutable uploaded panorama; never owns pixels. */
final class SkyboxAsset
{
	final GpuSkyExtension.SkyAnalysis analysis;
	final float upperBrightness;

	SkyboxAsset(BufferedImage image)
	{
		analysis = GpuSkyExtension.analyzeSkySource(image);
		upperBrightness = GpuSkyExtension.findUpperSkyBrightness(image);
	}
}
