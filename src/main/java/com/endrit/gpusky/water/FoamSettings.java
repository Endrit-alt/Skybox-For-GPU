package com.endrit.gpusky.water;

/** Explicit layer order: contact, second contact, outer, far outer, far contact. */
final class FoamSettings
{
	static final int COUNT = 5;
	/** Per-layer peak blend at strength 8 / 70% layer opacity is baseline * 8 * 0.70. */
	private static final float[] BASELINE = {0.625f, 0.5f, 0.525f, 0.275f, 0.25f};
	/** Fixed per-layer opacity (% of baseline); formerly a per-layer setting. */
	private static final int LAYER_OPACITY = 70;
	/** Per-layer width base; Far Contact is narrower. Scaled by the shared width setting. */
	private static final float[] WIDTH_BASE = {1f, 1f, 1f, 1f, 0.6f};
	final float[] opacity = new float[COUNT];
	final float[] width = new float[COUNT];
	final float[] softness = new float[COUNT];

	void update(WaterConfig c)
	{
		float sharedWidth = c.classicFoamWidth() / 100f;
		float sharedSoftness = Math.max(0, Math.min(3f, c.classicFoamSoftness() / 100f));
		for (int i = 0; i < COUNT; i++)
		{
			boolean softOuter = i == 2 || i == 3;
			boolean visible = c.shoreFoam() && (!softOuter || c.classicSoftOuterLayers());
			opacity[i] = visible
				? Math.min(1f, Math.max(0, c.contactFoamOpacity()) * BASELINE[i] * LAYER_OPACITY / 10000f) : 0;
			width[i] = Math.max(0.25f, Math.min(2.5f, WIDTH_BASE[i] * sharedWidth));
			softness[i] = sharedSoftness;
		}
	}

	boolean anyVisible()
	{
		for (float value : opacity) if (value > 0) return true;
		return false;
	}
}
