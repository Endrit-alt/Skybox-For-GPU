package com.endrit.gpusky;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DefaultRegionOverrides
{
	private DefaultRegionOverrides()
	{
	}

	static List<RegionOverride> create(GpuSkyExtensionConfig config)
	{
		List<RegionOverride> defaults = new ArrayList<>();
		defaults.add(create(config, "Desert", GpuSkyExtensionConfig.RegionWeatherOverride.SIROCCO,
			new RegionIds().add(
				12586, 12587, 12588, 12589, 12590, 12591,
				12842, 12843, 12844, 12845, 12846, 12847, 12848,
				13098, 13099, 13100, 13101, 13102, 13103, 13104, 13105, 13106, 13107,
				13354, 13355, 13356, 13357, 13358, 13359, 13360, 13361, 13362, 13363,
				13610, 13611, 13612, 13613, 13614, 13615, 13616, 13617,
				13870, 13871, 13872)));
		defaults.add(create(config, "Morytania", GpuSkyExtensionConfig.RegionWeatherOverride.MOONLIGHT,
			new RegionIds().add(
				13618, 13619, 13620, 13621, 13622, 13623,
				13873, 13874, 13875, 13876, 13877, 13878, 13879,
				14129, 14130, 14131, 14133, 14134, 14135,
				14385, 14389, 14390, 14391,
				14641, 14643, 14645, 14646, 14647,
				14899, 14900, 14901, 15153, 13723, 6727)));

		RegionIds snow = new RegionIds()
			.addRange(4160, 11840, 256)
			.addRange(4159, 11839, 256)
			.addRange(4158, 11838, 256)
			.addRange(4157, 11837, 256)
			.add(
				6204, 7484,
				4405, 4661, 4917, 5173, 5172, 4916, 4660, 4659, 4403, 4404,
				5683, 5682, 5681, 5680, 5939, 6195, 6194, 6451, 6450, 6707, 6706,
				11580, 11324, 11068, 10812, 10811, 11067, 11323, 11579, 11578,
				11322, 11066, 10810, 12181, 11419, 11675, 11167, 10911, 11425,
				10658, 20597, 10583, 10584, 10585, 10841, 11097, 13656, 13654,
				13910, 16729, 8267, 11595, 13636, 6214, 5190, 10042, 9532, 9276,
				8763, 7995, 8253, 8509, 8252, 8508);
		defaults.add(create(config, "Snow", GpuSkyExtensionConfig.RegionWeatherOverride.SNOW, snow));

		defaults.add(create(config, "Wilderness", GpuSkyExtensionConfig.RegionWeatherOverride.ASH,
			new RegionIds().add(
				11831, 11832, 11833, 11834, 11835, 11836,
				12087, 12088, 12089, 12090, 12091, 12092, 12093,
				12343, 12344, 12345, 12346, 12347, 12348, 12349,
				12599, 12600, 12601, 12602, 12603, 12604, 12605,
				12855, 12856, 12857, 12858, 12859, 12860, 12861,
				13111, 13112, 13113, 13114, 13115, 13116, 13117,
				13367, 13368, 13369, 13370, 13371, 13372, 13373,
				5690, 5691, 5692, 5946, 5947, 5948, 6202, 6203,
				13374, 13375, 13376, 13630, 13631, 13632,
				13886, 13887, 13888, 14142, 14143, 14144,
				14398, 14399, 14400)));
		defaults.add(create(config, "Lava", GpuSkyExtensionConfig.RegionWeatherOverride.ASH,
			new RegionIds().add(
				9552, 9808, 10064, 9551, 9807, 10063, 5975, 9043,
				13135, 13134, 13133, 13642, 13641, 10315,
				12630, 12886, 13142, 12629, 12885, 13141,
				12946, 13202, 11157, 11414, 11413, 13465, 13464,
				13981, 10649, 10650, 12445, 12446, 12190, 12192, 12193)));
		defaults.add(create(config, "Blood", GpuSkyExtensionConfig.RegionWeatherOverride.PESTILENCE,
			new RegionIds().add(
				12664, 10106, 10362, 10361, 10617, 10618, 11645, 10874, 10873,
				10105, 14642, 14388, 14132, 14644, 14387, 14386)));
		defaults.add(createCox(config));
		return defaults;
	}

	static RegionOverride createCox(GpuSkyExtensionConfig config)
	{
		RegionOverride override = create(config, "Cox", GpuSkyExtensionConfig.RegionWeatherOverride.DISABLED,
			new RegionIds().add(RegionOverride.COX_OLM_REGION_ID));
		override.skyboxImage = GpuSkyExtensionConfig.SkyboxImage.COX;
		return override;
	}

	private static RegionOverride create(GpuSkyExtensionConfig config, String name,
		GpuSkyExtensionConfig.RegionWeatherOverride weather, RegionIds regionIds)
	{
		RegionOverride override = RegionOverride.create(name, config.horizontalFov(), config.yawOffset(),
			config.pitchOffset(), config.exposure(), config.worldLighting(), config.rimLighting(),
			config.backlighting(), config.skyboxTint(), config.atmosphere());
		override.regionIds = regionIds.format();
		override.weather = weather;
		return override;
	}

	private static final class RegionIds
	{
		private final Set<Integer> values = new LinkedHashSet<>();

		private RegionIds add(int... regionIds)
		{
			for (int regionId : regionIds)
			{
				values.add(regionId);
			}
			return this;
		}

		private RegionIds addRange(int first, int last, int step)
		{
			for (int regionId = first; regionId <= last; regionId += step)
			{
				values.add(regionId);
			}
			return this;
		}

		private String format()
		{
			StringBuilder formatted = new StringBuilder();
			int index = 0;
			for (int regionId : values)
			{
				if (index > 0)
				{
					formatted.append(index % 2 == 0 ? '\n' : ' ');
				}
				formatted.append(regionId).append(',');
				index++;
			}
			return formatted.toString();
		}
	}
}
