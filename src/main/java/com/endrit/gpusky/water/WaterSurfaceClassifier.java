package com.endrit.gpusky.water;

/**
 * Classification established from the OSRS cache in the controlled Lumbridge-river test.
 * RuneLite stores a texture ID plus one in the GPU vertex attribute.
 */
final class WaterSurfaceClassifier
{
	static final int WATER_TEXTURE_ID = 1;
	static final int ALTERNATE_WATER_TEXTURE_ID = 24;
	static final int OPEN_WATER_TEXTURE_ID = 130;
	static final int SHELF_WATER_TEXTURE_ID = 131;
	static final int DEEP_WATER_TEXTURE_ID = 132;
	static final int SAILING_WATER_FIRST_TEXTURE_ID = 130;
	static final int SAILING_WATER_LAST_TEXTURE_ID = 189;
	static final int SAILING_WATER_EXTRA_TEXTURE_ID = 208;
	static final String GPU_SHADER_PREDICATE =
		"(fTextureId == 2 || fTextureId == 25 || (fTextureId >= 131 && fTextureId <= 190) || fTextureId == 209)";

	private WaterSurfaceClassifier()
	{
	}

	static boolean isWaterTexture(int textureId)
	{
		return textureId == WATER_TEXTURE_ID
			|| textureId == ALTERNATE_WATER_TEXTURE_ID
			|| textureId >= SAILING_WATER_FIRST_TEXTURE_ID && textureId <= SAILING_WATER_LAST_TEXTURE_ID
			|| textureId == SAILING_WATER_EXTRA_TEXTURE_ID;
	}

	static boolean isGpuWaterTexture(int encodedTextureId)
	{
		return isWaterTexture(encodedTextureId - 1);
	}
}
