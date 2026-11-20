package com.endrit.gpusky;

import com.endrit.gpusky.GpuSkyExtensionConfig.WeatherEffect;

/** Sky-only stars; no particle textures or additional GL resources. */
final class GalaxyStars
{
	static final float SLOT_SPACING = 0.5f;
	static final float POSITION_JITTER = 0.015f;
	static final float STAR_EXTENT = 0.23f;

	static final String SHADER = String.join("\n",
		"uniform float galaxyTime;",
		"uniform float galaxyIntensity;",
		"uniform float galaxyVisibility;",
		"const float galaxySlotSpacing = " + SLOT_SPACING + ";",
		"const float galaxyPositionJitter = " + POSITION_JITTER + ";",
		"const float galaxyStarExtent = " + STAR_EXTENT + ";",
		"vec3 galaxyHash(vec3 p) {",
		"  p = fract(p * vec3(0.1031, 0.1030, 0.0973));",
		"  p += dot(p, p.yxz + 33.33);",
		"  return fract((p.xxy + p.yxx) * p.zyx);",
		"}",
		"float galaxyStar(vec2 grid, float pixel, vec3 cell, vec2 slotCenter, float intensity, float sizeScale) {",
		"  vec3 seed = galaxyHash(cell + 19.7);",
		"  float clock = galaxyTime / mix(6.0, 12.0, seed.x) + seed.y;",
		"  float phase = fract(clock);",
		"  vec3 star = galaxyHash(cell + vec3(31.7, 9.2, floor(clock) * 7.13));",
		"  if (star.z > intensity * 0.35) return 0.0;",
		"  float life = smoothstep(0.05, 0.22, phase) * (1.0 - smoothstep(0.35, 0.65, phase));",
		// Reserve disjoint footprints, including across cell edges, even when every layer is active.
		"  vec2 position = slotCenter + (star.xy * 2.0 - 1.0) * galaxyPositionJitter;",
		"  vec2 p = (fract(grid) - position) / sizeScale;",
		"  float distanceToStar = length(p);",
		// Short bright points give the compact core a star shape without brightening the outer rays.
		"  vec2 q = abs(p);",
		"  float aa = pixel * 0.45 / sizeScale;",
		"  float radius = mix(0.010, 0.016, seed.z);",
		"  float core = 1.0 - smoothstep(max(0.0, radius - aa), radius + aa, q.x + q.y);",
		"  vec2 innerTaper = 1.0 - smoothstep(vec2(radius), vec2(radius * 5.0), q);",
		"  vec2 innerWidth = radius * (vec2(0.20) + 0.40 * innerTaper * innerTaper);",
		"  vec2 innerRays = (1.0 - smoothstep(innerWidth, innerWidth + vec2(aa), q.yx)) * innerTaper;",
		"  core = max(core, max(innerRays.x, innerRays.y) * 0.95);",
		"  float halo = 0.022 * exp(-distanceToStar * distanceToStar / 0.0015);",
		"  vec2 taper = 1.0 - smoothstep(vec2(0.0), vec2(mix(0.16, 0.21, seed.z)), q);",
		"  vec2 width = vec2(0.002) + 0.018 * taper * taper * taper;",
		"  vec2 arms = (1.0 - smoothstep(width, width + vec2(aa), q.yx)) * taper;",
		"  float shine = max(arms.x, arms.y);",
		"  vec2 diagonal = abs(vec2(p.x + p.y, p.x - p.y)) * 0.70710678;",
		"  vec2 diagonalTaper = 1.0 - smoothstep(vec2(0.0), vec2(0.12), diagonal);",
		"  vec2 diagonalArms = (1.0 - smoothstep(vec2(0.002), vec2(0.002 + aa), diagonal.yx)) * diagonalTaper;",
		"  float faintRays = max(diagonalArms.x, diagonalArms.y) * 0.06;",
		"  float sparkle = 0.85 + 0.15 * sin(galaxyTime * 3.0 + seed.z * 6.2831853);",
		"  float edge = 1.0 - smoothstep(0.18, galaxyStarExtent, distanceToStar);",
		// Avoid adding the rays onto the core, which widens the saturated white area.
		"  float brightness = max(core, halo + (shine * 0.32 + faintRays) * life);",
		"  return clamp(brightness * life * sparkle * edge * galaxyVisibility, 0.0, 1.0);",
		"}",
		"float galaxyStars(vec3 ray) {",
		"  if (galaxyIntensity <= 0.0 || galaxyVisibility <= 0.0) return 0.0;",
		// Cube-face coordinates avoid the panorama's pinching at the poles.
		"  vec3 axis = abs(ray);",
		"  vec2 uv;",
		"  float face;",
		"  if (axis.x >= axis.y && axis.x >= axis.z) {",
		"    uv = ray.zy / axis.x; face = ray.x < 0.0 ? 0.0 : 1.0;",
		"  } else if (axis.y >= axis.z) {",
		"    uv = ray.xz / axis.y; face = ray.y < 0.0 ? 2.0 : 3.0;",
		"  } else {",
		"    uv = ray.xy / axis.z; face = ray.z < 0.0 ? 4.0 : 5.0;",
		"  }",
		"  vec2 grid = (uv * 0.5 + 0.5) * 28.0;",
		"  float pixel = clamp(max(length(dFdx(grid)), length(dFdy(grid))), 0.002, 0.08);",
		// Shuffle which reserved slot appears first; never reshuffle while stars are visible.
		"  float slotRotation = floor(galaxyHash(vec3(floor(grid), face) + 47.2).x * 4.0);",
		"  float brightness = 0.0;",
		"  for (int layer = 0; layer < 4; layer++) {",
		"    float intensity = clamp(galaxyIntensity - float(layer) * 2.0, 0.0, 2.0);",
		"    if (intensity <= 0.0) break;",
		"    float slot = mod(float(layer) + slotRotation, 4.0);",
		"    vec2 slotCenter = (vec2(mod(slot, 2.0), floor(slot * 0.5)) + 0.5) * galaxySlotSpacing;",
		"    vec3 cell = vec3(floor(grid), face + float(layer) * 6.0);",
		// Two full layers are reached at 100%; only the extra layers use smaller stars.
		"    float sizeScale = layer < 2 ? 1.0 : 0.5;",
		"    brightness = max(brightness, galaxyStar(grid, pixel, cell, slotCenter, intensity, sizeScale));",
		"  }",
		"  return brightness;",
		"}");

	private boolean initialized;
	private long startedAt;
	private long updatedAt;
	private float visibility;

	static float intensity(int percent)
	{
		return Math.max(0, Math.min(200, percent)) / 25f;
	}

	float update(WeatherEffect effect, long now)
	{
		if (!initialized)
		{
			initialized = true;
			startedAt = now;
			updatedAt = now;
		}
		float step = Math.max(0f, (now - updatedAt) / 750_000_000f);
		updatedAt = now;
		visibility = effect == WeatherEffect.GALAXY
			? Math.min(1f, visibility + step) : Math.max(0f, visibility - step);
		return visibility;
	}

	float seconds(long now)
	{
		return initialized ? Math.max(0f, (now - startedAt) / 1_000_000_000f) : 0f;
	}

	void reset()
	{
		initialized = false;
		visibility = 0f;
	}
}
