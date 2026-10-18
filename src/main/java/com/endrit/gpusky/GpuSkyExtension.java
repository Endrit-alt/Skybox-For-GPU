package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.gpu.api.GpuExtension;
import net.runelite.client.callback.RenderCallbackManager;
import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GpuSkyExtension extends GpuExtension
{
	private static final Logger log = LoggerFactory.getLogger(GpuSkyExtension.class);
	private static final long TRANSITION_NANOS = 1_000_000_000L;
	private static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	private static final float TERRAIN_HEIGHT_TOLERANCE = 1f;
	private static final float EDGE_LIGHT_BASE_SCALE = 1.4f;
	// Preserve the wide framing calibrated at 106% resizable scaling without depending on stretching.
	private static final float REFERENCE_SCALE_RATIO = 0.26219955f * 2.06f;
	private static final float REFERENCE_ASPECT_RATIO = 512f / 334f;
	private static final String VERTEX_SHADER = String.join("\n",
		"#version 330",
		"layout(location = 0) in vec3 position;",
		"layout(location = 1) in vec2 tex;",
		"out vec2 fragUv;",
		"void main() {",
		"  fragUv = tex;",
		"  gl_Position = vec4(position.xy, 0.0, 1.0);",
		"}");
	private static final String FRAGMENT_SHADER = String.join("\n",
		"#version 330",
		"layout(std140) uniform uniforms {",
		"  mat4 worldProj;",
		"  vec3 cameraPos;",
		"  float cameraYaw;",
		"  float cameraPitch;",
		"};",
		"uniform sampler2D currentSky;",
		"uniform sampler2D previousSky;",
		"uniform float skyBlend;",
		"uniform float horizontalFov;",
		"uniform float aspect;",
		"uniform float yawOffset;",
		"uniform float pitchOffset;",
		"uniform float exposure;",
		"in vec2 fragUv;",
		"out vec4 fragColor;",
		"const float PI = 3.14159265358979323846;",
		"const float TWO_PI = 6.28318530717958647692;",
		"vec2 skyUv(vec2 screen) {",
		"  float hh = horizontalFov * 0.5;",
		"  float hv = atan(tan(hh) / max(aspect, 0.1));",
		"  vec3 ray = normalize(vec3(screen.x * tan(hh), screen.y * tan(hv), 1.0));",
		"  float ps = sin(cameraPitch + pitchOffset);",
		"  float pc = cos(cameraPitch + pitchOffset);",
		"  ray = vec3(ray.x, ray.y * pc - ray.z * ps, ray.y * ps + ray.z * pc);",
		"  float yaw = -cameraYaw + yawOffset;",
		"  float ys = sin(yaw);",
		"  float yc = cos(yaw);",
		"  ray = vec3(ray.x * yc + ray.z * ys, ray.y, ray.z * yc - ray.x * ys);",
		"  return vec2(atan(ray.x, ray.z) / TWO_PI, 0.5 + asin(clamp(ray.y, -1.0, 1.0)) / PI);",
		"}",
		"void main() {",
		"  vec2 screen = vec2(fragUv.x * 2.0 - 1.0, (1.0 - fragUv.y) * 2.0 - 1.0);",
		"  vec2 uv = skyUv(screen);",
		"  vec3 color = mix(textureLod(previousSky, uv, 0.0).rgb, textureLod(currentSky, uv, 0.0).rgb, skyBlend);",
		"  fragColor = vec4(vec3(1.0) - exp(-color * exposure), 1.0);",
		"}");
	private static final String SCENE_VERTEX_DEFINITIONS = String.join("\n",
		"uniform sampler2D gpuSkyHeights;",
		"uniform int gpuSkyHeightMapEnabled;",
		"uniform vec2 gpuSkyHeightTextureSize;",
		"uniform float gpuSkyHeightMapOffset;",
		"uniform sampler2D gpuSkyVertexOffsets;",
		"uniform sampler2D gpuSkyVertexHeights;",
		"uniform int gpuSkyVertexHeightsEnabled;",
		"out vec3 gpuSkyWorldPosition;",
		"out vec4 gpuSkyTerrainDistances;",
		"out vec2 gpuSkyFloorPosition;",
		"out float gpuSkyTerrainValid;",
		"vec4 gpuSkyHeightAtVertex(vec3 worldPosition) {",
		"  gpuSkyTerrainValid = 0.0;",
		"  gpuSkyFloorPosition = vec2(0.0);",
		"  if (gpuSkyHeightMapEnabled == 0 || entityProj != mat4(1.0)) return vec4(1e20);",
		"  vec2 samplePosition = worldPosition.xz / 128.0 + vec2(gpuSkyHeightMapOffset);",
		"  ivec2 size = ivec2(gpuSkyHeightTextureSize);",
		"  if (any(lessThan(samplePosition, vec2(0.0))) || any(greaterThan(samplePosition, vec2(size - ivec2(1))))) return vec4(1e20);",
		"  ivec2 tile = min(ivec2(floor(samplePosition)), size - ivec2(2));",
		"  vec2 local = samplePosition - vec2(tile);",
		"  vec4 sw = texelFetch(gpuSkyHeights, tile, 0);",
		"  vec4 se = texelFetch(gpuSkyHeights, tile + ivec2(1, 0), 0);",
		"  vec4 ne = texelFetch(gpuSkyHeights, tile + ivec2(1, 1), 0);",
		"  vec4 nw = texelFetch(gpuSkyHeights, tile + ivec2(0, 1), 0);",
		"  vec4 heights = mix(mix(sw, se, local.x), mix(nw, ne, local.x), local.y);",
		"  vec2 quarter = local * 4.0;",
		"  vec2 snapped = round(quarter);",
		"  if (gpuSkyVertexHeightsEnabled != 0 && all(lessThan(abs(quarter - snapped), vec2(0.0001)))",
		"      && all(greaterThan(snapped, vec2(0.0))) && all(lessThan(snapped, vec2(4.0)))) {",
		"    ivec2 point = ivec2(snapped) - ivec2(1);",
		"    vec4 starts = texelFetch(gpuSkyVertexOffsets, tile, 0);",
		"    int valueWidth = textureSize(gpuSkyVertexHeights, 0).x;",
		"    for (int plane = 0; plane < 4; plane++) {",
		"      if (starts[plane] < 1.0) continue;",
		"      int index = int(starts[plane]) - 1 + point.y * 3 + point.x;",
		"      float actual = texelFetch(gpuSkyVertexHeights, ivec2(index % valueWidth, index / valueWidth), 0).r;",
		"      if (actual < 1e19) heights[plane] = actual;",
		"    }",
		"  }",
		"  gpuSkyFloorPosition = samplePosition;",
		"  gpuSkyTerrainValid = 1.0;",
		"  return heights;",
		"}");
	private static final String SCENE_VERTEX_MAIN_POST = String.join("\n",
		"gpuSkyWorldPosition = worldPos.xyz;",
		"// Absolute distance BEFORE interpolation prevents false seams across an unused floor height.",
		"gpuSkyTerrainDistances = abs(gpuSkyHeightAtVertex(worldPos.xyz) - vec4(worldPos.y));");
	private static final String SCENE_FRAGMENT_DEFINITIONS = String.join("\n",
		"#define GPU_SKY_LIGHTING_API 1",
		"bool gpuSkyLightingApplied = false;",
		"layout(std140) uniform uniforms {",
		"  mat4 worldProj;",
		"  vec3 cameraPos;",
		"  float cameraYaw;",
		"  float cameraPitch;",
		"};",
		"uniform sampler2D gpuSkyCurrent;",
		"uniform sampler2D gpuSkyPrevious;",
		"uniform int gpuSkyEnabled;",
		"uniform int gpuSkyHeightMapEnabled;",
		"uniform float gpuSkyBlend;",
		"uniform vec2 gpuSkyViewport;",
		"uniform vec2 gpuSkyViewportOrigin;",
		"uniform float gpuSkyFov;",
		"uniform float gpuSkyAspect;",
		"uniform float gpuSkyYawOffset;",
		"uniform float gpuSkyPitchOffset;",
		"uniform float gpuSkyExposure;",
		"uniform float gpuSkyDrawDistance;",
		"uniform float gpuSkyWorldLight;",
		"uniform float gpuSkyRimLight;",
		"uniform float gpuSkyBacklight;",
		"uniform float gpuSkyTint;",
		"uniform float gpuSkyAtmosphere;",
		"uniform float gpuSkyUpperBrightness;",
		"uniform float gpuSkySourceContrast;",
		"uniform vec3 gpuSkyLightDirection;",
		"uniform int gpuWeatherGrade;",
		"uniform int gpuSkySoftFacets;",
		"in vec3 gpuSkyWorldPosition;",
		"in vec4 gpuSkyTerrainDistances;",
		"in vec2 gpuSkyFloorPosition;",
		"in float gpuSkyTerrainValid;",
		"uniform sampler2D gpuSkyFloorPresence;",
		"const float GPU_PI = 3.14159265358979323846;",
		"const float GPU_TWO_PI = 6.28318530717958647692;",
		"const float GPU_ATMOSPHERE_SCALE = 0.50;",
		"const float GPU_SKY_FACET_SOFTEN = 0.25;",
		"vec3 gpuSkySampleRaw(vec2 uv) {",
		"  vec3 a = textureLod(gpuSkyPrevious, uv, 7.0).rgb;",
		"  vec3 b = textureLod(gpuSkyCurrent, uv, 7.0).rgb;",
		"  return mix(a, b, gpuSkyBlend);",
		"}",
		"vec3 gpuSkyAverage(float u, float v) {",
		"  vec2 uv = vec2(u, clamp(v, 0.0, 1.0));",
		"  vec3 c = gpuSkySampleRaw(uv) * 4.0;",
		"  c += gpuSkySampleRaw(uv + vec2(-0.025, 0.0));",
		"  c += gpuSkySampleRaw(uv + vec2(0.025, 0.0));",
		"  c += gpuSkySampleRaw(vec2(u, clamp(v - 0.018, 0.0, 1.0)));",
		"  c += gpuSkySampleRaw(vec2(u, clamp(v + 0.018, 0.0, 1.0)));",
		"  return c * 0.125;",
		"}",
		"vec3 gpuSkyColorUv(vec2 uv) {",
		"  return vec3(1.0) - exp(-gpuSkyAverage(fract(uv.x), uv.y) * gpuSkyExposure);",
		"}",
		"vec2 gpuSkyUvAt(vec2 coord) {",
		"  vec2 screen = clamp(coord / max(gpuSkyViewport, vec2(1.0)), vec2(0.0), vec2(1.0)) * 2.0 - 1.0;",
		"  float hh = gpuSkyFov * 0.5;",
		"  float hv = atan(tan(hh) / max(gpuSkyAspect, 0.1));",
		"  vec3 ray = normalize(vec3(screen.x * tan(hh), screen.y * tan(hv), 1.0));",
		"  float ps = sin(cameraPitch + gpuSkyPitchOffset);",
		"  float pc = cos(cameraPitch + gpuSkyPitchOffset);",
		"  ray = vec3(ray.x, ray.y * pc - ray.z * ps, ray.y * ps + ray.z * pc);",
		"  float yaw = -cameraYaw + gpuSkyYawOffset;",
		"  float ys = sin(yaw);",
		"  float yc = cos(yaw);",
		"  ray = vec3(ray.x * yc + ray.z * ys, ray.y, ray.z * yc - ray.x * ys);",
		"  float u = atan(ray.x, ray.z) / GPU_TWO_PI;",
		"  float v = 0.5 + asin(clamp(ray.y, -1.0, 1.0)) / GPU_PI;",
		"  return vec2(u, v);",
		"}",
		"vec3 gpuSkyColorAt(vec2 coord) {",
		"  return gpuSkyColorUv(gpuSkyUvAt(coord));",
		"}",
		"vec3 gpuSkyTintColor(float u) {",
		"  vec3 horizon = gpuSkyColorUv(vec2(u, 0.50));",
		"  vec3 middle = gpuSkyColorUv(vec2(u, 0.65));",
		"  vec3 upper = gpuSkyColorUv(vec2(u, 0.80));",
		"  return horizon * 0.50 + middle * 0.32 + upper * 0.18;",
		"}",
		"vec3 gpuSkyColorDirection(vec3 worldDirection) {",
		"  vec3 direction = normalize(worldDirection);",
		"  float cameraYawSin = sin(cameraYaw);",
		"  float cameraYawCos = cos(cameraYaw);",
		"  float cameraPitchSin = sin(cameraPitch);",
		"  float cameraPitchCos = cos(cameraPitch);",
		"  vec3 right = vec3(cameraYawCos, 0.0, cameraYawSin);",
		"  vec3 forward = vec3(-cameraYawSin * cameraPitchCos, -cameraPitchSin, cameraYawCos * cameraPitchCos);",
		"  vec3 up = vec3(forward.y * right.z, forward.z * right.x - forward.x * right.z, -forward.y * right.x);",
		"  vec3 cameraRay = vec3(dot(direction, right), dot(direction, up), dot(direction, forward));",
		"  float pitch = cameraPitch - gpuSkyPitchOffset;",
		"  float ps = sin(pitch);",
		"  float pc = cos(pitch);",
		"  float directionY = cameraRay.y * pc - cameraRay.z * ps;",
		"  float rotatedZ = cameraRay.y * ps + cameraRay.z * pc;",
		"  float yaw = -cameraYaw + gpuSkyYawOffset;",
		"  float ys = sin(yaw);",
		"  float yc = cos(yaw);",
		"  float directionX = cameraRay.x * yc + rotatedZ * ys;",
		"  float directionZ = -cameraRay.x * ys + rotatedZ * yc;",
		"  float u = atan(directionX, directionZ) / GPU_TWO_PI;",
		"  float v = 0.5 + asin(clamp(directionY, -1.0, 1.0)) / GPU_PI;",
		"  return gpuSkyColorUv(vec2(u, v));",
		"}",
		"vec3 gpuSkyLightingColor(vec3 color) {",
		"  float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));",
		"  float darkColor = 1.0 - smoothstep(0.18, 0.50, luminance);",
		"  float lift = 1.0 + 0.50 * (1.0 - gpuSkySourceContrast) * darkColor;",
		"  return color * lift;",
		"}",
		"vec3 gpuWeatherColor(vec3 c) {",
		"  if (gpuWeatherGrade == 1) return c * vec3(1.05, 0.98, 0.88);",
		"  if (gpuWeatherGrade == 2) return mix(c, c * vec3(0.72, 0.92, 0.68), 0.16);",
		"  if (gpuWeatherGrade == 3) return c * vec3(1.02, 0.94, 0.88);",
		"  if (gpuWeatherGrade == 4) return mix(c, c * vec3(0.82, 0.92, 1.08), 0.12);",
		"  if (gpuWeatherGrade == 5) return mix(c, c * vec3(1.04, 0.98, 1.02), 0.07);",
		"  if (gpuWeatherGrade == 6) return mix(c, c * vec3(0.90, 0.97, 1.10), 0.12);",
		"  if (gpuWeatherGrade == 7) return mix(c, c * vec3(0.78, 0.90, 1.14), 0.14);",
		"  if (gpuWeatherGrade == 8) return mix(c, c * vec3(1.12, 1.04, 0.82), 0.14);",
		"  return c;",
		"}",
		"bool gpuSkyIsTerrain(vec3 worldPosition) {",
		"  if (gpuSkyHeightMapEnabled == 0 || gpuSkyTerrainValid < 0.9999) return false;",
		"  ivec2 tile = clamp(ivec2(floor(gpuSkyFloorPosition)), ivec2(0), textureSize(gpuSkyFloorPresence, 0) - ivec2(2));",
		"  vec4 present = texelFetch(gpuSkyFloorPresence, tile, 0);",
		"  vec4 difference = mix(vec4(1e20), gpuSkyTerrainDistances, greaterThan(present, vec4(0.5)));",
		"  float nearest = min(min(difference.x, difference.y), min(difference.z, difference.w));",
		"  return nearest <= " + TERRAIN_HEIGHT_TOLERANCE + ";",
		"}");
	// Cooperating material extensions can apply scene lighting first, regardless of registration order.
	private static final String SCENE_FRAGMENT_LIGHTING = String.join("\n",
		"void gpuSkyApplyLighting(vec4 c, vec4 fogColor, float fFogAmount) {",
		"if (gpuSkyLightingApplied) return;",
		"gpuSkyLightingApplied = true;",
		"if (gpuSkyEnabled != 0) {",
		"  vec3 sky = gpuSkyColorAt(gl_FragCoord.xy - gpuSkyViewportOrigin);",
		"  vec3 surfaceColor = c.rgb;",
		"  vec3 matchedFogColor = fogColor.rgb;",
		"  float baseBrightness = max(c.r, max(c.g, c.b));",
		"  float darkSurfaceMask = smoothstep(0.04, 0.14, baseBrightness);",
		"  vec3 view = normalize(cameraPos - gpuSkyWorldPosition);",
		"  vec3 normal = cross(dFdx(gpuSkyWorldPosition), dFdy(gpuSkyWorldPosition));",
		"  float normalLengthSquared = dot(normal, normal);",
		"  normal = normalLengthSquared < 0.000001 ? view : normal * inversesqrt(normalLengthSquared);",
		"  if (dot(normal, view) < 0.0) normal = -normal;",
		"  // Soft facets: lean the per-triangle normal toward the smoothly varying view vector.",
		"  if (gpuSkySoftFacets != 0) normal = normalize(mix(normal, view, GPU_SKY_FACET_SOFTEN));",
		"  float wallFade = gpuSkySoftFacets != 0 ? 1.0 - smoothstep(0.78, 0.98, abs(normal.y)) : (abs(normal.y) <= 0.92 ? 1.0 : 0.0);",
		"  // Soft curves lower peak edge light; these restore roughly the previous strength.",
		"  float softRimGain = gpuSkySoftFacets != 0 ? 1.50 : 1.0;",
		"  float softBacklightGain = gpuSkySoftFacets != 0 ? 1.90 : 1.0;",
		"  float softBroadGain = gpuSkySoftFacets != 0 ? 1.50 : 1.0;",
		"  bool terrain = gpuSkyIsTerrain(gpuSkyWorldPosition);",
		"  float viewYaw = -cameraYaw;",
		"  vec3 side = normalize(vec3(cos(viewYaw), 0.0, -sin(viewYaw)));",
		"  float visible = 1.0 - smoothstep(0.0, 0.82, clamp(fFogAmount, 0.0, 1.0));",
		"  vec2 centerScreen = (gl_FragCoord.xy - gpuSkyViewportOrigin) / max(gpuSkyViewport, vec2(1.0)) * 2.0 - 1.0;",
		"  float centerEdge = smoothstep(0.20, 0.85, length(centerScreen));",
		"  float centerLightScale = mix(0.30, 1.0, centerEdge);",
		"  if (gpuSkyAtmosphere > 0.0) {",
		"    float atmosphereStrength = gpuSkyAtmosphere * centerLightScale;",
		"    vec3 left = gpuSkyColorAt(gl_FragCoord.xy - gpuSkyViewportOrigin - vec2(gpuSkyViewport.x * 0.28, 0.0));",
		"    vec3 right = gpuSkyColorAt(gl_FragCoord.xy - gpuSkyViewportOrigin + vec2(gpuSkyViewport.x * 0.28, 0.0));",
		"    float up = 0.32 + smoothstep(-0.35, 1.0, normal.y) * 0.48;",
		"    surfaceColor += sky * up * 0.10 * GPU_ATMOSPHERE_SCALE * visible * atmosphereStrength;",
		"    float facing = dot(normal, side);",
		"    vec3 sideColor = mix(left, right, (gpuSkySoftFacets != 0 ? smoothstep(-0.9, 0.9, facing) : smoothstep(-0.4, 0.4, facing)));",
		"    surfaceColor += sideColor * (0.18 + smoothstep(0.0, 1.0, abs(facing)) * 0.42) * 0.08 * GPU_ATMOSPHERE_SCALE * visible * atmosphereStrength;",
		"    float broad = (gpuSkySoftFacets != 0 ? smoothstep(-0.10, 1.05, 1.0 - clamp(dot(normal, view), 0.0, 1.0)) : smoothstep(0.15, 0.95, 1.0 - clamp(dot(normal, view), 0.0, 1.0)));",
		"    surfaceColor += sky * broad * 0.018 * GPU_ATMOSPHERE_SCALE * visible * atmosphereStrength * softBroadGain;",
		"    float below = 1.0 - smoothstep(-64.0, 320.0, cameraPos.y - gpuSkyWorldPosition.y);",
		"    surfaceColor = mix(surfaceColor, sky, below * 0.10 * GPU_ATMOSPHERE_SCALE * visible * atmosphereStrength);",
		"    surfaceColor = mix(surfaceColor, gpuWeatherColor(surfaceColor), visible * atmosphereStrength);",
		"  }",
		"  if (gpuSkyRimLight > 0.0 && !terrain && wallFade > 0.0) {",
		"    float rim = (gpuSkySoftFacets != 0 ? smoothstep(-0.15, 0.95, 1.0 - clamp(dot(normal, view), 0.0, 1.0)) : smoothstep(0.08, 0.92, 1.0 - clamp(dot(normal, view), 0.0, 1.0))) * wallFade;",
		"    float skyBrightness = 1.0 - exp(-gpuSkyUpperBrightness * gpuSkyExposure);",
		"    float darkSkyBoost = mix(2.3, 1.0, smoothstep(0.12, 0.32, skyBrightness));",
		"    float adaptiveRim = darkSkyBoost * mix(1.0, 0.45, smoothstep(0.30, 0.65, skyBrightness));",
		"    float lightFacing = (gpuSkySoftFacets != 0 ? smoothstep(-0.70, 0.90, dot(normal, gpuSkyLightDirection)) : smoothstep(-0.20, 0.50, dot(normal, gpuSkyLightDirection)));",
		"    float directionalStrength = mix(0.80, mix(0.60, 0.85, lightFacing), gpuSkySourceContrast);",
		"    float directionalRim = rim * directionalStrength;",
		"    vec3 rimSky = gpuSkyLightingColor(gpuSkyColorDirection(normal));",
		"    surfaceColor += rimSky * directionalRim * 0.20 * gpuSkyRimLight * adaptiveRim * darkSurfaceMask * softRimGain;",
		"  }",
		"  if (gpuSkyBacklight > 0.0 && !terrain && wallFade > 0.0) {",
		"    float edge = (gpuSkySoftFacets != 0 ? smoothstep(0.10, 1.05, 1.0 - clamp(dot(normal, view), 0.0, 1.0)) : smoothstep(0.42, 0.96, 1.0 - clamp(dot(normal, view), 0.0, 1.0))) * wallFade;",
		"    float skyBrightness = 1.0 - exp(-gpuSkyUpperBrightness * gpuSkyExposure);",
		"    float darkSkyBoost = mix(2.3, 1.0, smoothstep(0.12, 0.32, skyBrightness));",
		"    float adaptiveRim = darkSkyBoost * mix(1.0, 0.45, smoothstep(0.30, 0.65, skyBrightness));",
		"    float adaptiveBacklight = mix(1.0, adaptiveRim, 0.50);",
		"    float lightFacing = (gpuSkySoftFacets != 0 ? smoothstep(-0.70, 0.90, dot(normal, gpuSkyLightDirection)) : smoothstep(-0.20, 0.50, dot(normal, gpuSkyLightDirection)));",
		"    float directionalStrength = mix(0.80, mix(0.60, 0.85, lightFacing), gpuSkySourceContrast);",
		"    vec3 backlightSky = gpuSkyLightingColor(gpuSkyColorDirection(normal));",
		"    surfaceColor += backlightSky * edge * directionalStrength * (1.0 - (gpuSkySoftFacets != 0 ? smoothstep(-0.95, 0.05, dot(normal, side)) : smoothstep(-0.65, -0.35, dot(normal, side)))) * 0.20 * gpuSkyBacklight * adaptiveBacklight * darkSurfaceMask * softBacklightGain;",
		"  }",
		"  if (gpuSkyWorldLight > 0.0) {",
		"    float distanceRatio = length(cameraPos - gpuSkyWorldPosition) / max(gpuSkyDrawDistance, 1.0);",
		"    surfaceColor = mix(surfaceColor, sky, smoothstep(0.01, 1.0, distanceRatio) * 0.50 * gpuSkyWorldLight);",
		"  }",
		"  matchedFogColor = sky;",
		"  if (gpuSkyTint > 0.0) {",
		"    float tintU = gpuSkyUvAt(gpuSkyViewport * 0.5).x;",
		"    vec3 tintSky = gpuSkyTintColor(tintU);",
		"    float lum = dot(tintSky, vec3(0.2126, 0.7152, 0.0722));",
		"    vec3 ratio = clamp(tintSky / max(lum, 0.05), vec3(0.55), vec3(1.65));",
		"    surfaceColor *= mix(vec3(1.0), ratio, 0.10 * gpuSkyTint * centerLightScale);",
		"  }",
		"  FragColor = vec4(mix(surfaceColor, matchedFogColor, fFogAmount), c.a);",
		"}",
		"}");
	private static final String SCENE_FRAGMENT_MAIN_POST = "gpuSkyApplyLighting(c, fogColor, fFogAmount);";

	private final Client client;
	private final GpuSkyExtensionConfig config;
	private final WeatherRenderer weatherRenderer = new WeatherRenderer();
	private final LightWeatherFade lightWeatherFade = new LightWeatherFade();
	private final EnumMap<SkySlot, SkyboxAsset> images = new EnumMap<>(SkySlot.class);
	private final Map<String, SkyboxAsset> preloadedImages = new HashMap<>();
	private final EnumMap<SkySlot, SkyDirection> brightestSkyDirections = new EnumMap<>(SkySlot.class);
	private final EnumMap<SkySlot, Float> upperSkyBrightnesses = new EnumMap<>(SkySlot.class);
	private final EnumMap<SkySlot, Float> skySourceContrasts = new EnumMap<>(SkySlot.class);
	private final EnumMap<GpuSkyExtensionConfig.WeatherEffect, WeatherRenderer.LoadedWeatherAssets> weatherAssets =
		new EnumMap<>(GpuSkyExtensionConfig.WeatherEffect.class);
	private boolean regionOverrideActive;
	private GpuSkyExtensionConfig.WeatherEffect mainWeather = GpuSkyExtensionConfig.WeatherEffect.DISABLED;
	private GpuSkyExtensionConfig.RegionWeatherOverride regionWeather =
		GpuSkyExtensionConfig.RegionWeatherOverride.MAIN;
	private int regionWeatherIntensity = 100;
	private boolean regionSkyboxDisabled;
	private boolean regionInheritSkySettings = true;
	private int regionHorizontalFov = 110;
	private int regionYawOffset = 160;
	private int regionPitchOffset;
	private int regionExposure = 110;
	private boolean regionInheritLightSettings = true;
	private int regionWorldLighting = 70;
	private int regionRimLighting = 70;
	private int regionBacklighting = 70;
	private int regionSkyboxTint = 70;
	private int regionAtmosphere = 70;
	private OpenGlContext gpu;
	private volatile boolean contextReady;
	private volatile int contextGeneration;
	private final Runnable contextChanged;
	private final Map<GpuSkyExtensionConfig.WeatherEffect, Consumer<Boolean>> weatherCompletions =
		new EnumMap<>(GpuSkyExtensionConfig.WeatherEffect.class);
	private SkyboxTextures skyTextures;
	private final List<Runnable> preloadCompletions = new ArrayList<>();
	private int program;
	private int sceneProgram;
	private int buffer;
	private final RenderCallbackManager renderCallbacks;
	private int vertexOffsetTexture, vertexHeightTexture, floorPresenceTexture, correctedVertices;
	private Scene uploadedScene;
	private volatile boolean floorPresenceDirty = true;
	private int floorMinLevel = -1;
	private float[] uploadedFloorPresence;
	private int heightTexture;
	private int heightTextureWidth;
	private int heightTextureHeight;
	private int heightMapOffset;
	private int[][][] uploadedTileHeights;
	private boolean heightMapAvailable;
	private boolean heightMapDirty = true;
	private int currentTexture;
	private int previousTexture;
	private SkyDirection currentSkyDirection = SkyDirection.UP;
	private SkyDirection previousSkyDirection = SkyDirection.UP;
	private float currentUpperSkyBrightness;
	private float previousUpperSkyBrightness;
	private float currentSkySourceContrast;
	private float previousSkySourceContrast;
	private long transitionStart;

	GpuSkyExtension(Client client, GpuSkyExtensionConfig config, RenderCallbackManager renderCallbacks,
		Runnable contextChanged)
	{
		this.client = client;
		this.config = config;
		this.renderCallbacks = renderCallbacks;
		this.contextChanged = contextChanged;
	}

	boolean hasContext()
	{
		return contextReady;
	}

	int skyboxContext()
	{
		return contextReady ? contextGeneration : -1;
	}

	private void setContextReady(boolean ready)
	{
		contextReady = ready;
		contextChanged.run();
	}

	@Override
	public String injectShaderExtension(String hook)
	{
		switch (hook)
		{
			case "rlst_vert_definitions":
				return SCENE_VERTEX_DEFINITIONS;
			case "rlst_vert_main_post":
				return SCENE_VERTEX_MAIN_POST;
			case "rlst_frag_definitions":
				return SCENE_FRAGMENT_DEFINITIONS + "\n" + SCENE_FRAGMENT_LIGHTING;
			case "rlst_frag_main_post":
				return SCENE_FRAGMENT_MAIN_POST;
			default:
				return null;
		}
	}

	@Override
	public void onContextCreate()
	{
		ensureStarted();
	}

	@Override
	public void onContextDestroy()
	{
		destroyContext();
	}

	@Override
	public void onProgramCreate(int program)
	{
		ensureStarted();
		sceneProgram = program;
		gpu.cacheSceneUniforms(program,
			"gpuSkyEnabled", "gpuSkyCurrent", "gpuSkyPrevious", "gpuSkyHeights",
			"gpuSkyHeightMapEnabled", "gpuSkyHeightTextureSize", "gpuSkyHeightMapOffset",
			"gpuSkyVertexOffsets", "gpuSkyVertexHeights", "gpuSkyVertexHeightsEnabled",
			"gpuSkyFloorPresence", "gpuSkyBlend", "gpuSkyViewport", "gpuSkyViewportOrigin",
			"gpuSkyFov", "gpuSkyAspect", "gpuSkyYawOffset", "gpuSkyPitchOffset",
			"gpuSkyExposure", "gpuSkyDrawDistance", "gpuSkyWorldLight", "gpuSkyRimLight",
			"gpuSkyBacklight", "gpuSkyTint", "gpuSkyAtmosphere", "gpuSkyUpperBrightness",
			"gpuSkySourceContrast", "gpuSkyLightDirection", "gpuWeatherGrade", "gpuSkySoftFacets");
	}

	@Override
	public boolean drawSkybox()
	{
		if (sceneProgram == 0)
		{
			return false;
		}
		OpenGlContext.DrawState state = new OpenGlContext.DrawState();
		try
		{
			ensureStarted();
			long now = System.nanoTime();
			resolveSkybox(now);
			int[] viewport = viewport();
			// Camera scale uses logical pixels, not the stretched/DPI-scaled GL viewport.
			int width = Math.max(1, client.getViewportWidth());
			int height = Math.max(1, client.getViewportHeight());
			float skyFov = horizontalFov(width, client.getScale(), activeHorizontalFov());
			float skyAspect = width / (float) height;
			updateScene(sceneProgram, viewport, now, skyFov, skyAspect);
			if (currentTexture == 0)
			{
				return false;
			}

			gpu.useProgram(program);
			gpu.bindTexture("currentSky", currentTexture, 0);
			gpu.bindTexture("previousSky", previousTexture == 0 ? currentTexture : previousTexture, 1);
			gpu.uniform1f("skyBlend", transitionBlend(now));
			setProjectionUniforms(skyFov, skyAspect);
			gpu.draw(buffer, 0, 6, OpenGlContext.Depth.NONE, OpenGlContext.Blend.ALPHA);
			return true;
		}
		finally
		{
			state.restore();
		}
	}

	@Override
	public void onPostDrawToplevel()
	{
		if (sceneProgram == 0)
		{
			return;
		}
		OpenGlContext.DrawState state = new OpenGlContext.DrawState();
		try
		{
			ensureStarted();
			GpuSkyExtensionConfig.WeatherEffect effect = activeWeather();
			long now = System.nanoTime();
			boolean weatherReady = effect != null && weatherRenderer.activate(effect);
			// Hide the beams while their source direction moves between skybox images.
			float lightWeatherOpacity = lightWeatherFade.update(effect,
				weatherReady && transitionBlend(now) >= 1f, now);
			if (weatherReady)
			{
				int[] viewport = viewport();
				float cameraX = client.getCameraFpX();
				float cameraY = client.getCameraFpZ();
				float cameraZ = client.getCameraFpY();
				float cameraYaw = client.getCameraFpYaw();
				float cameraPitch = client.getCameraFpPitch();
				float anchorX = cameraX;
				float anchorZ = cameraZ;
				Player player = client.getLocalPlayer();
				if (player != null)
				{
					LocalPoint local = player.getLocalLocation();
					if (local != null)
					{
						anchorX = local.getX();
						anchorZ = local.getY();
					}
				}
				SkyDirection moonDirection = activeMoonDirection(cameraPitch, cameraYaw, now);
				weatherRenderer.draw(effect, viewport[2], viewport[3], activeWeatherIntensity(),
					cameraX, cameraY, cameraZ, cameraYaw, cameraPitch, anchorX, anchorZ,
					client.getScene(), client.getTileSettings(), client.getPlane(),
					moonDirection.x, moonDirection.y, moonDirection.z, lightWeatherOpacity);
			}

			// If RuneLite drew a scene-owned skybox, keep our scene shader inactive
			// until drawSkybox() explicitly enables it on a later frame.
			gpu.useSceneProgram(sceneProgram);
			gpu.uniform1i("gpuSkyEnabled", 0);
		}
		finally
		{
			state.restore();
		}
	}

	void close()
	{
		completePreload();
		destroyContext();
		images.clear();
		preloadedImages.clear();
		brightestSkyDirections.clear();
		upperSkyBrightnesses.clear();
		skySourceContrasts.clear();
		weatherAssets.clear();
		weatherCompletions.clear();
		regionOverrideActive = false;
	}

	private void ensureStarted()
	{
		if (gpu != null)
		{
			return;
		}
		OpenGlContext nextGpu = new OpenGlContext(client);
		gpu = nextGpu;
		try
		{
			program = gpu.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
			buffer = gpu.createBuffer();
			gpu.upload(buffer, fullscreenQuad(), 6);
			skyTextures = new SkyboxTextures(new SkyboxTextures.Backend()
			{
				@Override
				public int upload(BufferedImage image)
				{
					return gpu.createTexture(image, OpenGlContext.Wrap.REPEAT, OpenGlContext.Wrap.CLAMP);
				}

				@Override
				public void destroy(int texture)
				{
					gpu.destroyTexture(texture);
				}
			});
			contextGeneration++;
			setContextReady(true);
			weatherRenderer.start(gpu);
			for (WeatherRenderer.LoadedWeatherAssets assets : weatherAssets.values())
			{
				publishWeather(assets);
			}
			heightMapDirty = true;
		}
		catch (RuntimeException | Error ex)
		{
			setContextReady(false);
			weatherRenderer.stop();
			nextGpu.close();
			gpu = null;
			program = 0;
			buffer = 0;
			skyTextures = null;
			throw ex;
		}
	}

	private void destroyContext()
	{
		sceneProgram = 0;
		lightWeatherFade.reset();
		setContextReady(false);
		completePreload();
		images.clear();
		preloadedImages.clear();
		brightestSkyDirections.clear();
		upperSkyBrightnesses.clear();
		skySourceContrasts.clear();
		weatherRenderer.stop();
		if (gpu != null)
		{
			gpu.close();
			gpu = null;
		}
		program = 0;
		buffer = 0;
		heightTexture = 0;
		vertexOffsetTexture = vertexHeightTexture = floorPresenceTexture = correctedVertices = 0;
		uploadedScene = null;
		uploadedFloorPresence = null;
		floorPresenceDirty = true;
		floorMinLevel = -1;
		heightTextureWidth = 0;
		heightTextureHeight = 0;
		heightMapOffset = 0;
		uploadedTileHeights = null;
		heightMapAvailable = false;
		heightMapDirty = true;
		skyTextures = null;
		currentTexture = 0;
		previousTexture = 0;
		currentSkyDirection = SkyDirection.UP;
		previousSkyDirection = SkyDirection.UP;
		currentUpperSkyBrightness = 0f;
		previousUpperSkyBrightness = 0f;
		currentSkySourceContrast = 0f;
		previousSkySourceContrast = 0f;
	}

	private void updateScene(int sceneProgram, int[] viewport, long now, float skyFov, float skyAspect)
	{
		int width = viewport[2];
		int height = viewport[3];
		gpu.useSceneProgram(sceneProgram);
		gpu.uniform1i("gpuSkyEnabled", currentTexture == 0 ? 0 : 1);
		if (currentTexture == 0)
		{
			return;
		}
		gpu.bindTexture("gpuSkyCurrent", currentTexture, 0);
		gpu.bindTexture("gpuSkyPrevious", previousTexture == 0 ? currentTexture : previousTexture, 1);
		updateHeightMap();
		gpu.uniform1i("gpuSkyHeightMapEnabled", heightMapAvailable ? 1 : 0);
		if (heightMapAvailable)
		{
			updateFloorPresence();
			gpu.bindTexture("gpuSkyVertexOffsets", vertexOffsetTexture, 3);
			gpu.bindTexture("gpuSkyVertexHeights", vertexHeightTexture, 4);
			gpu.bindTexture("gpuSkyFloorPresence", floorPresenceTexture, 5);
			gpu.uniform1i("gpuSkyVertexHeightsEnabled", correctedVertices == 0 ? 0 : 1);
			gpu.bindTexture("gpuSkyHeights", heightTexture, 2);
			gpu.uniform2f("gpuSkyHeightTextureSize", heightTextureWidth, heightTextureHeight);
			gpu.uniform1f("gpuSkyHeightMapOffset", heightMapOffset);
		}
		gpu.uniform1f("gpuSkyBlend", transitionBlend(now));
		// gl_FragCoord still needs the physical viewport; ray projection uses logical dimensions.
		gpu.uniform2f("gpuSkyViewport", width, height);
		gpu.uniform2f("gpuSkyViewportOrigin", viewport[0], viewport[1]);
		gpu.uniform1f("gpuSkyFov", skyFov);
		gpu.uniform1f("gpuSkyAspect", skyAspect);
		gpu.uniform1f("gpuSkyYawOffset", radians(activeYawOffset()));
		gpu.uniform1f("gpuSkyPitchOffset", radians(activePitchOffset()));
		gpu.uniform1f("gpuSkyExposure", activeExposure() / 100f);
		gpu.uniform1f("gpuSkyDrawDistance",
			client.getScene().getDrawDistance() * (float) Perspective.LOCAL_TILE_SIZE);
		gpu.uniform1f("gpuSkyWorldLight", activeWorldLighting() / 100f);
		gpu.uniform1f("gpuSkyRimLight", activeRimLighting() / 50f * EDGE_LIGHT_BASE_SCALE);
		gpu.uniform1f("gpuSkyBacklight", activeBacklighting() / 50f * EDGE_LIGHT_BASE_SCALE);
		gpu.uniform1f("gpuSkyTint", activeSkyboxTint() / 50f);
		gpu.uniform1f("gpuSkyAtmosphere", activeAtmosphere() / 100f);
		gpu.uniform1i("gpuSkySoftFacets", config.softFacets() ? 1 : 0);
		gpu.uniform1f("gpuSkyUpperBrightness", activeUpperSkyBrightness(now));
		gpu.uniform1f("gpuSkySourceContrast", activeSkySourceContrast(now));
		SkyDirection lightDirection =
			activeMoonDirection(client.getCameraFpPitch(), client.getCameraFpYaw(), now);
		gpu.uniform3f("gpuSkyLightDirection", lightDirection.x, lightDirection.y, lightDirection.z);
		GpuSkyExtensionConfig.WeatherEffect effect = activeWeather();
		gpu.uniform1i("gpuWeatherGrade", effect == null ? 0 : effect.shaderGrade());
	}

	void invalidateHeightMap()
	{
		heightMapDirty = true;
	}

	void invalidateFloorPresence()
	{
		floorPresenceDirty = true;
	}

	private int updateDataTexture(int texture, int width, int height, float[] data)
	{
		if (texture == 0) return gpu.createFloatTexture(width, height, data);
		gpu.updateFloatTexture(texture, width, height, data);
		return texture;
	}

	private void updateFloorPresence()
	{
		if (!floorPresenceDirty && floorPresenceTexture != 0 && floorMinLevel == uploadedScene.getMinLevel()) return;
		// Clear first so a concurrent config event can request another refresh.
		floorPresenceDirty = false;
		try
		{
			float[] next = TerrainFloorPresence.build(uploadedScene, heightTextureWidth, heightTextureHeight, renderCallbacks);
			if (floorPresenceTexture == 0 || !Arrays.equals(next, uploadedFloorPresence))
			{
				floorPresenceTexture = updateDataTexture(floorPresenceTexture, heightTextureWidth, heightTextureHeight, next);
				uploadedFloorPresence = next;
			}
			floorMinLevel = uploadedScene.getMinLevel();
		}
		catch (RuntimeException | Error ex)
		{
			floorPresenceDirty = true;
			throw ex;
		}
	}

	private void updateHeightMap()
	{
		Scene scene = client.getScene();
		int[][][] tileHeights = scene == null ? null : scene.getTileHeights();
		if (!heightMapDirty && scene == uploadedScene && tileHeights == uploadedTileHeights)
		{
			return;
		}

		heightMapDirty = false;
		heightMapAvailable = false;
		if (tileHeights == null || tileHeights.length == 0
			|| tileHeights[0] == null || tileHeights[0].length < 2
			|| tileHeights[0][0] == null || tileHeights[0][0].length < 2)
		{
			uploadedTileHeights = tileHeights;
			return;
		}

		int width = tileHeights[0].length;
		int height = tileHeights[0][0].length;
		for (int plane = 0; plane < Math.min(4, tileHeights.length); ++plane)
		{
			if (tileHeights[plane] == null || tileHeights[plane].length != width)
			{
				uploadedTileHeights = tileHeights;
				return;
			}
			for (int x = 0; x < width; ++x)
			{
				if (tileHeights[plane][x] == null || tileHeights[plane][x].length != height)
				{
					uploadedTileHeights = tileHeights;
					return;
				}
			}
		}

		float[] packed = new float[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
		int index = 0;
		for (int z = 0; z < height; ++z)
		{
			for (int x = 0; x < width; ++x)
			{
				for (int plane = 0; plane < 4; ++plane)
				{
					packed[index++] = plane < tileHeights.length
						? tileHeights[plane][x][z]
						: Float.MAX_VALUE;
				}
			}
		}

		if (heightTexture == 0)
		{
			heightTexture = gpu.createFloatTexture(width, height, packed);
		}
		else
		{
			gpu.updateFloatTexture(heightTexture, width, height, packed);
		}
		heightTextureWidth = width;
		heightTextureHeight = height;
		heightMapOffset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;
		TerrainVertexHeights.Data vertices = TerrainVertexHeights.build(scene, tileHeights, heightMapOffset);
		vertexOffsetTexture = updateDataTexture(vertexOffsetTexture, width, height, vertices.offsets);
		vertexHeightTexture = updateDataTexture(vertexHeightTexture, TerrainVertexHeights.VALUE_WIDTH, vertices.rows, vertices.values);
		correctedVertices = vertices.vertices;
		uploadedScene = scene;
		uploadedTileHeights = tileHeights;
		floorPresenceDirty = true;
		heightMapAvailable = true;
		log.debug("Terrain height data refreshed: {} shaped-tile vertex corrections", correctedVertices);
	}

	void setImages(SkyboxAsset overworld, SkyboxAsset cave, SkyboxAsset region)
	{
		setImage(SkySlot.OVERWORLD, overworld);
		setImage(SkySlot.CAVE, cave);
		setImage(SkySlot.REGION, region);
		syncRequiredImages();
	}

	void setPreloadedSkyboxes(Map<String, SkyboxAsset> skyboxes, Runnable complete)
	{
		preloadedImages.clear();
		preloadedImages.putAll(skyboxes);
		preloadCompletions.add(complete);
		syncRequiredImages();
		checkPreloadComplete();
	}

	void retainPreloadedSkybox(String path, SkyboxAsset asset)
	{
		preloadedImages.put(path, asset);
		syncRequiredImages();
		pruneSkyboxes();
	}

	private void syncRequiredImages()
	{
		if (skyTextures != null)
		{
			List<SkyboxAsset> required = new ArrayList<>();
			required.add(images.get(activeSlot()));
			required.addAll(images.values());
			required.addAll(preloadedImages.values());
			skyTextures.require(required);
		}
	}

	private void checkPreloadComplete()
	{
		if (preloadedImages.isEmpty() || skyTextures != null && !skyTextures.pending(preloadedImages.values()))
		{
			completePreload();
		}
	}

	private void completePreload()
	{
		if (!preloadCompletions.isEmpty())
		{
			List<Runnable> completions = new ArrayList<>(preloadCompletions);
			preloadCompletions.clear();
			completions.forEach(Runnable::run);
		}
	}

	void uploadSkybox(SkyboxAsset asset, BufferedImage image)
	{
		if (skyTextures == null)
		{
			throw new IllegalStateException("GPU context is unavailable");
		}
		skyTextures.upload(asset, image);
	}

	void pruneSkyboxes()
	{
		if (skyTextures != null)
		{
			skyTextures.prune();
		}
	}

	void setRegionOverride(boolean active,
		GpuSkyExtensionConfig.RegionWeatherOverride weather, int weatherIntensity,
		boolean skyboxDisabled,
		boolean inheritSkySettings, int horizontalFov, int yawOffset, int pitchOffset, int exposure,
		boolean inheritLightSettings, int worldLighting, int rimLighting, int backlighting,
		int skyboxTint, int atmosphere)
	{
		this.regionOverrideActive = active;
		this.regionWeather = weather;
		this.regionWeatherIntensity = weatherIntensity;
		this.regionSkyboxDisabled = skyboxDisabled;
		this.regionInheritSkySettings = inheritSkySettings;
		this.regionHorizontalFov = horizontalFov;
		this.regionYawOffset = yawOffset;
		this.regionPitchOffset = pitchOffset;
		this.regionExposure = exposure;
		this.regionInheritLightSettings = inheritLightSettings;
		this.regionWorldLighting = worldLighting;
		this.regionRimLighting = rimLighting;
		this.regionBacklighting = backlighting;
		this.regionSkyboxTint = skyboxTint;
		this.regionAtmosphere = atmosphere;
	}

	void uploadWeather(WeatherRenderer.LoadedWeatherAssets assets, Consumer<Boolean> complete)
	{
		weatherAssets.put(assets.effect(), assets);
		weatherCompletions.put(assets.effect(), complete);
		if (gpu != null)
		{
			publishWeather(assets);
		}
	}

	private void publishWeather(WeatherRenderer.LoadedWeatherAssets assets)
	{
		boolean success = weatherRenderer.upload(assets);
		Consumer<Boolean> complete = weatherCompletions.get(assets.effect());
		if (complete != null)
		{
			complete.accept(success);
		}
	}

	private void setImage(SkySlot slot, SkyboxAsset image)
	{
		if (images.get(slot) == image)
		{
			return;
		}
		if (image == null)
		{
			images.remove(slot);
			brightestSkyDirections.remove(slot);
			upperSkyBrightnesses.remove(slot);
			skySourceContrasts.remove(slot);
			return;
		}
		images.put(slot, image);
		SkyAnalysis analysis = image.analysis;
		brightestSkyDirections.put(slot, analysis.direction);
		skySourceContrasts.put(slot, analysis.sourceContrast);
		upperSkyBrightnesses.put(slot, image.upperBrightness);
	}

	private void resolveSkybox(long now)
	{
		if (transitionBlend(now) >= 1f)
		{
			skyTextures.finishTransition();
		}
		SkySlot slot = activeSlot();
		SkyboxAsset image = images.get(slot);
		if (skyTextures.select(image))
		{
			SkyDirection nextDirection = brightestSkyDirections.getOrDefault(slot, SkyDirection.UP);
			float nextBrightness = upperSkyBrightnesses.getOrDefault(slot, 0f);
			float nextContrast = skySourceContrasts.getOrDefault(slot, 0f);
			previousSkyDirection = currentTexture == 0 ? nextDirection : currentSkyDirection;
			previousUpperSkyBrightness = currentTexture == 0 ? nextBrightness : currentUpperSkyBrightness;
			previousSkySourceContrast = currentTexture == 0 ? nextContrast : currentSkySourceContrast;
			currentSkyDirection = nextDirection;
			currentUpperSkyBrightness = nextBrightness;
			currentSkySourceContrast = nextContrast;
			transitionStart = now;
		}
		currentTexture = skyTextures.currentTexture();
		previousTexture = skyTextures.previousTexture();
	}

	private SkySlot activeSlot()
	{
		if (regionActive() && regionSkyboxDisabled)
		{
			return null;
		}
		if (regionActive() && images.containsKey(SkySlot.REGION))
		{
			return SkySlot.REGION;
		}
		if (isCave() && images.containsKey(SkySlot.CAVE))
		{
			return SkySlot.CAVE;
		}
		return SkySlot.OVERWORLD;
	}

	private SkyDirection activeMoonDirection(float cameraPitch, float cameraYaw, long now)
	{
		SkyDirection direction = activeSkyDirection(now);

		float yaw = -cameraYaw + radians(activeYawOffset());
		float yawSin = (float) Math.sin(yaw);
		float yawCos = (float) Math.cos(yaw);
		float cameraRayX = direction.x * yawCos - direction.z * yawSin;
		float rotatedZ = direction.z * yawCos + direction.x * yawSin;

		float pitch = cameraPitch - radians(activePitchOffset());
		float pitchSin = (float) Math.sin(pitch);
		float pitchCos = (float) Math.cos(pitch);
		float cameraRayY = direction.y * pitchCos + rotatedZ * pitchSin;
		float cameraRayZ = -direction.y * pitchSin + rotatedZ * pitchCos;

		float cameraYawSin = (float) Math.sin(cameraYaw);
		float cameraYawCos = (float) Math.cos(cameraYaw);
		float cameraPitchSin = (float) Math.sin(cameraPitch);
		float cameraPitchCos = (float) Math.cos(cameraPitch);
		float rightX = cameraYawCos;
		float rightZ = cameraYawSin;
		float viewX = -cameraYawSin * cameraPitchCos;
		float viewY = -cameraPitchSin;
		float viewZ = cameraYawCos * cameraPitchCos;
		float upX = viewY * rightZ;
		float upY = viewZ * rightX - viewX * rightZ;
		float upZ = -viewY * rightX;
		float worldX = rightX * cameraRayX + upX * cameraRayY + viewX * cameraRayZ;
		float worldY = upY * cameraRayY + viewY * cameraRayZ;
		float worldZ = rightZ * cameraRayX + upZ * cameraRayY + viewZ * cameraRayZ;
		float horizontal = (float) Math.sqrt(worldX * worldX + worldZ * worldZ);
		return new SkyDirection(
			worldX,
			worldY,
			worldZ,
			(float) Math.atan2(worldX, worldZ),
			(float) Math.atan2(-worldY, horizontal));
	}

	static SkyAnalysis analyzeSkySource(BufferedImage image)
	{
		final int columns = 96;
		final int rows = 48;
		final float imageHeightFraction = 0.65f;
		float[][] luminance = new float[rows][columns];
		float luminanceTotal = 0f;
		for (int row = 0; row < rows; row++)
		{
			int y = Math.min(image.getHeight() - 1,
				Math.round((row + 0.5f) / rows * image.getHeight() * imageHeightFraction));
			for (int column = 0; column < columns; column++)
			{
				int x = Math.min(image.getWidth() - 1,
					Math.round((column + 0.5f) / columns * image.getWidth()));
				int rgb = image.getRGB(x, y);
				float red = ((rgb >> 16) & 0xff) / 255f;
				float green = ((rgb >> 8) & 0xff) / 255f;
				float blue = (rgb & 0xff) / 255f;
				luminance[row][column] = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
				luminanceTotal += luminance[row][column];
			}
		}

		int brightestColumn = 0;
		int brightestRow = 0;
		float brightest = -1f;
		for (int row = 0; row < rows; row++)
		{
			for (int column = 0; column < columns; column++)
			{
				float sum = 0f;
				float weightSum = 0f;
				for (int offsetY = -3; offsetY <= 3; offsetY++)
				{
					int sampleRow = Math.max(0, Math.min(rows - 1, row + offsetY));
					for (int offsetX = -5; offsetX <= 5; offsetX++)
					{
						int sampleColumn = Math.floorMod(column + offsetX, columns);
						float weight = 1f / (1f + offsetX * offsetX * 0.16f + offsetY * offsetY * 0.36f);
						sum += luminance[sampleRow][sampleColumn] * weight;
						weightSum += weight;
					}
				}
				float average = sum / weightSum;
				if (average > brightest)
				{
					brightest = average;
					brightestColumn = column;
					brightestRow = row;
				}
			}
		}

		float u = (brightestColumn + 0.5f) / columns;
		float imageV = (brightestRow + 0.5f) / rows * imageHeightFraction;
		float azimuth = u * (float) (Math.PI * 2.0);
		float elevation = (0.5f - imageV) * (float) Math.PI;
		float horizontal = (float) Math.cos(elevation);
		float averageLuminance = luminanceTotal / (rows * columns);
		float prominence = (brightest - averageLuminance) / Math.max(averageLuminance, 0.05f);
		float sourceContrast = Math.max(0f, Math.min(1f, (prominence - 0.10f) / 0.90f));
		return new SkyAnalysis(new SkyDirection(
			(float) Math.sin(azimuth) * horizontal,
			-(float) Math.sin(elevation),
			(float) Math.cos(azimuth) * horizontal,
			azimuth,
			elevation), sourceContrast);
	}

	private float transitionBlend(long now)
	{
		if (config.instantTransitions() || previousTexture == 0 || previousTexture == currentTexture)
		{
			return 1f;
		}
		return Math.min(1f, (now - transitionStart) / (float) TRANSITION_NANOS);
	}

	private float activeUpperSkyBrightness(long now)
	{
		float blend = transitionBlend(now);
		return previousUpperSkyBrightness
			+ (currentUpperSkyBrightness - previousUpperSkyBrightness) * blend;
	}

	private float activeSkySourceContrast(long now)
	{
		float blend = transitionBlend(now);
		return previousSkySourceContrast
			+ (currentSkySourceContrast - previousSkySourceContrast) * blend;
	}

	private SkyDirection activeSkyDirection(long now)
	{
		float blend = transitionBlend(now);
		float x = previousSkyDirection.x + (currentSkyDirection.x - previousSkyDirection.x) * blend;
		float y = previousSkyDirection.y + (currentSkyDirection.y - previousSkyDirection.y) * blend;
		float z = previousSkyDirection.z + (currentSkyDirection.z - previousSkyDirection.z) * blend;
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		if (length < 0.0001f)
		{
			return blend < 0.5f ? previousSkyDirection : currentSkyDirection;
		}
		x /= length;
		y /= length;
		z /= length;
		float horizontal = (float) Math.sqrt(x * x + z * z);
		return new SkyDirection(x, y, z,
			(float) Math.atan2(x, z), (float) Math.atan2(-y, horizontal));
	}

	static float findUpperSkyBrightness(BufferedImage image)
	{
		final int columns = 96;
		final int rows = 48;
		final float imageHeightFraction = 0.60f;
		float total = 0f;
		for (int row = 0; row < rows; row++)
		{
			int y = Math.min(image.getHeight() - 1,
				(int) ((row + 0.5f) / rows * image.getHeight() * imageHeightFraction));
			for (int column = 0; column < columns; column++)
			{
				int x = Math.min(image.getWidth() - 1, (int) ((column + 0.5f) / columns * image.getWidth()));
				int rgb = image.getRGB(x, y);
				float red = ((rgb >> 16) & 0xff) / 255f;
				float green = ((rgb >> 8) & 0xff) / 255f;
				float blue = (rgb & 0xff) / 255f;
				float luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
				float peak = Math.max(red, Math.max(green, blue));
				total += Math.max(luminance, peak * 0.8f);
			}
		}
		return total / (columns * rows);
	}

	void setMainWeather(GpuSkyExtensionConfig.WeatherEffect weather)
	{
		mainWeather = weather;
	}

	GpuSkyExtensionConfig.WeatherEffect activeWeather()
	{
		if (regionActive())
		{
			if (regionWeather == GpuSkyExtensionConfig.RegionWeatherOverride.DISABLED)
			{
				return null;
			}
			if (regionWeather != GpuSkyExtensionConfig.RegionWeatherOverride.MAIN)
			{
				return GpuSkyExtensionConfig.WeatherEffect.valueOf(regionWeather.name());
			}
		}
		return mainWeather == GpuSkyExtensionConfig.WeatherEffect.DISABLED ? null : mainWeather;
	}

	private int activeWeatherIntensity()
	{
		if (regionActive())
		{
			if (regionWeather != GpuSkyExtensionConfig.RegionWeatherOverride.MAIN
				&& regionWeather != GpuSkyExtensionConfig.RegionWeatherOverride.DISABLED)
			{
				return regionWeatherIntensity;
			}
		}
		return config.weatherIntensity();
	}

	boolean regionActive()
	{
		// Keep the published sky/weather/settings together until the next selection is ready.
		return regionOverrideActive && !config.disableRegionOverrides();
	}

	private boolean isCave()
	{
		Player player = client.getLocalPlayer();
		return player != null && WorldPoint.getMirrorPoint(player.getWorldLocation(), true).getY() >= Constants.OVERWORLD_MAX_Y;
	}

	private void setProjectionUniforms(float skyFov, float skyAspect)
	{
		gpu.uniform1f("horizontalFov", skyFov);
		gpu.uniform1f("aspect", skyAspect);
		gpu.uniform1f("yawOffset", radians(activeYawOffset()));
		gpu.uniform1f("pitchOffset", radians(activePitchOffset()));
		gpu.uniform1f("exposure", activeExposure() / 100f);
	}

	static float horizontalFov(int logicalWidth, int cameraScale, int configuredFov)
	{
		float scale = Math.max(1f, cameraScale);
		// Height enters through the aspect ratio in the shader, not through horizontal FOV as well.
		float referenceScale = REFERENCE_SCALE_RATIO * Math.max(1, logicalWidth) / REFERENCE_ASPECT_RATIO;
		float halfFov = radians(configuredFov) * 0.5f;
		return (float) Math.atan(Math.tan(halfFov) * referenceScale / scale) * 2f;
	}

	private boolean useRegionSkySettings()
	{
		return regionActive() && !regionInheritSkySettings;
	}

	private int activeHorizontalFov()
	{
		return useRegionSkySettings() ? regionHorizontalFov : config.horizontalFov();
	}

	private int activeYawOffset()
	{
		return useRegionSkySettings() ? regionYawOffset : config.yawOffset();
	}

	private int activePitchOffset()
	{
		return useRegionSkySettings() ? regionPitchOffset : config.pitchOffset();
	}

	private int activeExposure()
	{
		return useRegionSkySettings() ? regionExposure : config.exposure();
	}

	private boolean useRegionLightSettings()
	{
		return regionActive() && !regionInheritLightSettings;
	}

	private int activeWorldLighting()
	{
		return useRegionLightSettings() ? regionWorldLighting : config.worldLighting();
	}

	private int activeRimLighting()
	{
		return useRegionLightSettings() ? regionRimLighting : config.rimLighting();
	}

	private int activeBacklighting()
	{
		return useRegionLightSettings() ? regionBacklighting : config.backlighting();
	}

	private int activeSkyboxTint()
	{
		return useRegionLightSettings() ? regionSkyboxTint : config.skyboxTint();
	}

	private int activeAtmosphere()
	{
		return useRegionLightSettings() ? regionAtmosphere : config.atmosphere();
	}

	private static int[] viewport()
	{
		int[] viewport = new int[4];
		GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, viewport);
		viewport[2] = Math.max(1, viewport[2]);
		viewport[3] = Math.max(1, viewport[3]);
		return viewport;
	}

	private static float radians(float degrees)
	{
		return (float) Math.toRadians(degrees);
	}

	private static float[] fullscreenQuad()
	{
		float[] vertices = new float[6 * OpenGlContext.VERTEX_STRIDE_FLOATS];
		vertex(vertices, 0, 1, 1, 1, 0);
		vertex(vertices, 1, 1, -1, 1, 1);
		vertex(vertices, 2, -1, -1, 0, 1);
		vertex(vertices, 3, 1, 1, 1, 0);
		vertex(vertices, 4, -1, -1, 0, 1);
		vertex(vertices, 5, -1, 1, 0, 0);
		return vertices;
	}

	private static void vertex(float[] vertices, int index, float x, float y, float u, float v)
	{
		int offset = index * OpenGlContext.VERTEX_STRIDE_FLOATS;
		vertices[offset] = x;
		vertices[offset + 1] = y;
		vertices[offset + 3] = u;
		vertices[offset + 4] = v;
	}

	private enum SkySlot
	{
		OVERWORLD,
		CAVE,
		REGION
	}

	private static final class SkyDirection
	{
		private static final SkyDirection UP = new SkyDirection(0f, -1f, 0f, 0f, (float) Math.PI * 0.5f);

		private final float x;
		private final float y;
		private final float z;
		private final float azimuth;
		private final float elevation;

		private SkyDirection(float x, float y, float z, float azimuth, float elevation)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.azimuth = azimuth;
			this.elevation = elevation;
		}
	}

	static final class SkyAnalysis
	{
		private final SkyDirection direction;
		private final float sourceContrast;

		private SkyAnalysis(SkyDirection direction, float sourceContrast)
		{
			this.direction = direction;
			this.sourceContrast = sourceContrast;
		}
	}
}
