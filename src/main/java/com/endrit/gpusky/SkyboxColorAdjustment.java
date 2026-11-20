package com.endrit.gpusky;

/** Shared by the sky, scene lighting and water shaders; one uniform in the scene program. */
public final class SkyboxColorAdjustment
{
	public static final String GLSL = String.join("\n",
		"#ifndef GPU_SKY_COLOR_ADJUSTMENT",
		"#define GPU_SKY_COLOR_ADJUSTMENT",
		"uniform vec2 gpuSkyHueSaturation;", // Degrees and percent; zero leaves the source unchanged.
		"vec3 gpuSkyAdjustColor(vec3 color) {",
		"  if (all(equal(gpuSkyHueSaturation, vec2(0.0)))) return color;",
		"  float hi = max(color.r, max(color.g, color.b));",
		"  float lo = min(color.r, min(color.g, color.b));",
		"  float delta = hi - lo;",
		"  if (delta < 0.000001) return color;",
		"  float lightness = (hi + lo) * 0.5;",
		"  float hue = hi == color.r ? (color.g - color.b) / delta",
		"    : hi == color.g ? (color.b - color.r) / delta + 2.0 : (color.r - color.g) / delta + 4.0;",
		"  hue = fract(hue / 6.0 + gpuSkyHueSaturation.x / 360.0);",
		"  float saturation = delta / max(1.0 - abs(2.0 * lightness - 1.0), 0.000001);",
		"  saturation *= clamp(1.0 + gpuSkyHueSaturation.y / 100.0, 0.0, 1.0);",
		"  vec3 wheel = clamp(abs(fract(hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);",
		"  return vec3(lightness) + (wheel - 0.5) * (1.0 - abs(2.0 * lightness - 1.0)) * saturation;",
		"}",
		"#endif");

	private SkyboxColorAdjustment()
	{
	}
}
