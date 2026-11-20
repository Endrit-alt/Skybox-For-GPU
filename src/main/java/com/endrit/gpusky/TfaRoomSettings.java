package com.endrit.gpusky;

enum TfaRoomSettings
{
	DEFAULT(0, 0, 0),
	RAISED(5, 0, 0),
	LOWER(-6, 0, 0),
	HUE_SHIFTED(0, 87, 0),
	REWARD(0, -30, -30);

	final int verticalOffset;
	final int hueOffset;
	final int saturationOffset;

	TfaRoomSettings(int verticalOffset, int hueOffset, int saturationOffset)
	{
		this.verticalOffset = verticalOffset;
		this.hueOffset = hueOffset;
		this.saturationOffset = saturationOffset;
	}

	static TfaRoomSettings resolve(RegionOverride override, int regionId)
	{
		if (override == null || !DefaultRegionOverrides.TFA_ID.equals(override.id) || !override.matches(regionId))
		{
			return DEFAULT;
		}
		switch (regionId)
		{
			case 16470: return RAISED;
			case 16472: return LOWER;
			case 16474: return REWARD;
			case 16728: return HUE_SHIFTED;
			default: return DEFAULT;
		}
	}

	int pitch(int base)
	{
		return Math.max(-10, Math.min(10, base + verticalOffset));
	}
}
