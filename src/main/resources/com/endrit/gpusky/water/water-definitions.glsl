in vec3 mawWorldPosition;
in vec3 mawToCamera;
uniform int mawEnabled;
// 0: ordinary/opaque material; 1: omit water (including depth); 2: water-only replay.
uniform int mawWaterPass;
uniform float mawWaterOpacity;
uniform int mawUnderwaterAlpha;
uniform sampler2D mawWaterSurface;
uniform sampler2D mawWaterDepth;
uniform ivec4 mawWaterViewport;
uniform sampler2D mawSceneDepth;
uniform int mawDepthFade;
// Water opacity over objects just below the surface, relative to the base opacity.
const float MAW_NEAR_OBJECT_CLARITY = 0.80;
uniform float mawDepthBias;
// Perspective reversed-depth ratios recover vertical submersion, not raw
// nonlinear depth differences. Handles both host clip-depth conventions.
float mawSubmersionFade(float nearDepth, float farDepth, float verticalToNear) {
  float nearZ = nearDepth - mawDepthBias;
  float farZ = farDepth - mawDepthBias;
  if (nearZ <= 0.0 || farZ <= 0.0) return 0.0;
  float submerged = max(0.0, verticalToNear * (nearZ / farZ - 1.0));
  // Objects stay clearly visible just under the surface and fade gradually
  // into the open-water look, fully gone at one tile deep.
  return smoothstep(12.0, 128.0, submerged);
}
uniform float mawTime;
uniform float mawSpeed;
uniform float mawWaveStrength;
uniform float mawWaterBrightness;
uniform vec3 mawWaterTint; // the chosen water color (0..1)
uniform float mawHighlightIntensity;
uniform float mawNormalDetailStrength;
uniform int mawAlternateWaves;
uniform int mawShoreFollowingWaves;
uniform int mawShoreMaskValid;
uniform sampler2D mawShoreMask;
uniform int mawShoreHistory;
uniform sampler2D mawPreviousShoreMask;
uniform sampler2D mawShoreCoverage;
uniform vec2 mawShoreOffset;
uniform vec2 mawPreviousShoreOffset;
uniform float mawShoreBlend;
uniform int mawPreviousShoreValid;
uniform vec2 mawWorldBase;
uniform vec2 mawShoreTransform;
// Do not turn the transparent terrain/water contact into an opaque dark seam.
// Apply the same bank guard to alpha objects at their ray's WATER intersection,
// not at the object's deeper, horizontally displaced fragment position.
float mawBankFadeAt(vec3 waterPosition) {
  if (mawShoreMaskValid == 0) return 1.0;
  vec2 local = waterPosition.xz / 128.0;
  vec2 sceneUv = (local + vec2(mawShoreTransform.x)) * mawShoreTransform.y;
  vec2 uv = (local + (mawShoreHistory != 0 ? mawShoreOffset : vec2(0.0))
    + vec2(mawShoreTransform.x)) * mawShoreTransform.y;
  if (any(lessThan(sceneUv, vec2(0.0))) || any(greaterThan(sceneUv, vec2(1.0)))) return 1.0;
  vec2 coverage = mawShoreHistory != 0 ? texture(mawShoreCoverage, sceneUv).rg : vec2(1.0, 0.0);
  float current = 1.0;
  if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0))))
    current = mix(1.0, smoothstep(0.05, 0.5, texture(mawShoreMask, uv).r), coverage.r);
  if (mawShoreHistory != 0 && mawPreviousShoreValid != 0 && mawShoreBlend < 1.0) {
    vec2 previousUv = (local + mawPreviousShoreOffset + vec2(mawShoreTransform.x)) * mawShoreTransform.y;
    if (all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThanEqual(previousUv, vec2(1.0))))
      current = mix(mix(1.0, smoothstep(0.05, 0.5, texture(mawPreviousShoreMask, previousUv).r), coverage.g), current, mawShoreBlend);
  }
  return current;
}
// Shore-distance depth proxy, not measured riverbed depth. Return weighted tone
// and influence separately so region history blends colors, never contour fields.
vec2 mawShallowWaterTone(sampler2D mask, vec2 uv, vec4 data, float visibility) {
  if (visibility <= 0.0 || data.r < 0.0) return vec2(0.0);
  // Smooth width over a depth-color-only neighborhood. Skip land and keep the
  // existing foam fields untouched. This lets an ocean-facing river mouth pick
  // up its wider surroundings before reaching the end of its narrow chord.
  // Ignore the mixed shoreline texel's width, NOT its color influence. Fading
  // the latter to zero exposed a thin stripe of dark ocean color at pond banks.
  float centerWeight = 2.0 * smoothstep(0.04, 0.10, data.r);
  float widthSum = max(0.15, data.a) * centerWeight;
  float weightSum = centerWeight;
  for (int ring = 0; ring < 2; ring++) for (int y = -1; y <= 1; y += 2) for (int x = -1; x <= 1; x += 2) {
    float radius = ring == 0 ? 1.0 : 3.0;
    vec2 sampleUv = uv + vec2(float(x), float(y)) * (radius * mawShoreTransform.y);
    if (any(lessThan(sampleUv, vec2(0.0))) || any(greaterThan(sampleUv, vec2(1.0)))) continue;
    vec4 sampleData = texture(mask, sampleUv);
    float weight = smoothstep(0.04, 0.80, sampleData.r);
    widthSum += max(0.15, sampleData.a) * weight;
    weightSum += weight;
  }
  data.a = weightSum > 0.0 ? widthSum / weightSum : 8.0;
  float halfWidth = max(0.15, data.a);
  // Begin releasing the river/pond tint sooner as opposing banks open out,
  // keeping a long zero-slope handoff to the unchanged ocean coloring.
  float handoff = clamp((halfWidth - 1.75) / 6.15, 0.0, 1.0);
  handoff = handoff*handoff*handoff*(handoff*(handoff*6.0-15.0)+10.0);
  float boundedWater = 1.0 - handoff;
  // Original bank-distance depth estimate, without the later broad-field blur.
  float distance = max(0.0, mix(data.r, data.g, 0.20));
  float centerDepth = distance / halfWidth;
  float towardMiddle = smoothstep(0.03, 1.05, centerDepth);
  // Use half the original depth-darkening amplitude, without changing its footprint.
  float tone = 0.56 - (0.56 - 0.30) * 0.50 * towardMiddle;
  float influence = boundedWater * visibility;
  return vec2(tone * influence, influence);
}
float mawFilteredSine(vec2 p, vec2 direction, float frequency, float phase, out vec2 gradient) {
  float argument = dot(p, direction) * frequency + phase;
  float footprint = length(fwidth(p)) * frequency;
  float visibility = 1.0 - smoothstep(0.32, 1.15, footprint);
  gradient = direction * frequency * cos(argument) * visibility;
  return sin(argument) * visibility;
}
float mawHash(vec2 p) {
  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}
float mawNoise(vec2 p) {
  vec2 cell = floor(p);
  vec2 local = fract(p);
  local = local * local * (3.0 - 2.0 * local);
  float a = mawHash(cell);
  float b = mawHash(cell + vec2(1.0, 0.0));
  float c = mawHash(cell + vec2(0.0, 1.0));
  float d = mawHash(cell + vec2(1.0, 1.0));
  return mix(mix(a, b, local.x), mix(c, d, local.x), local.y);
}
// Weighted world-space slopes, height and influence of a shoreline-shaped wave.
// Blend slopes instead of adding bright bands over unrelated ocean normals.
vec4 mawShoreWave(vec4 data, vec2 p, float time, float visibility) {
  // Round tight shoreline corners without changing the foam's distance fields.
  float distance = max(0.0, mix(data.g, data.b, 0.35));
  // A long, zero-slope handoff starts near shore instead of retaining full
  // coastal bands until a short offshore switch. Keep a little ocean motion
  // underneath even close to shore so the two styles remain visually related.
  float handoff = clamp((distance - 0.6) / 6.7, 0.0, 1.0);
  handoff = handoff*handoff*handoff*(handoff*(handoff*6.0-15.0)+10.0);
  float influence = 0.85 * (1.0 - handoff) * visibility;
  float shape = mawNoise(p * 0.16 + vec2(time * 0.012, -time * 0.009) + vec2(7.2, 18.1));
  float broadWarp = mawNoise(p * 0.075 + vec2(-time * 0.006, time * 0.008) + vec2(24.1, 5.7));
  // Slow, coherent warping varies spacing and width without chopping crests
  // into detached patches. Slightly broader crests match the offshore style.
  float phase = distance * 1.55 + time * (0.46 * 1.30) + (shape - 0.5) * 1.05 + (broadWarp - 0.5) * 0.55;
  float footprint = abs(fwidth(phase));
  float filtered = 1.0 - smoothstep(0.30, 1.02, footprint);
  influence *= filtered;
  float variation = mix(0.74, 1.0, broadWarp);
  // Harmonics give a defined crest and asymmetric, softer trailing slope.
  float height = (cos(phase) + 0.24 * cos(2.0 * phase) + 0.12 * sin(2.0 * phase)) * 0.27 * variation;
  vec2 dx = dFdx(p), dy = dFdy(p);
  float hx = dFdx(height), hy = dFdy(height);
  float determinant = dx.x * dy.y - dx.y * dy.x;
  vec2 slope = vec2(0.0);
  if (abs(determinant) > 0.00000001)
    slope = vec2(hx * dy.y - hy * dx.y, hy * dx.x - hx * dy.x) / determinant;
  // Avoid unstable normals at poorly resolved distance-field ridges/horizons.
  slope *= min(1.0, 1.5 / max(length(slope), 0.001));
  return vec4(slope * influence, height * influence, influence);
}
// Analytic slopes of a shader-generated normal map. Quintic interpolation keeps
// both slopes and their derivatives continuous across cells; no texture seams.
vec2 mawNormalMapSlope(vec2 uv) {
  vec2 cell = floor(uv), x = fract(uv);
  vec2 blend = x*x*x*(x*(x*6.0-15.0)+10.0);
  vec2 derivative = 30.0*x*x*(x-1.0)*(x-1.0);
  float a = mawHash(cell), b = mawHash(cell + vec2(1.0, 0.0));
  float c = mawHash(cell + vec2(0.0, 1.0)), d = mawHash(cell + vec2(1.0));
  return vec2(mix(b-a, d-c, blend.y), mix(c-a, d-b, blend.x)) * derivative;
}
vec2 mawWaterNormalDetail(vec2 p, float time) {
  // Different scales, orientations and scroll directions conceal repetition.
  vec2 uvA = p * 1.80 + vec2(time * 0.065, -time * 0.045);
  mat2 rotation = mat2(0.8, 0.6, -0.6, 0.8);
  vec2 uvB = rotation * p * 3.10 + vec2(-time * 0.050, time * 0.070) + vec2(17.3, 9.1);
  float visibleA = 1.0 - smoothstep(0.18, 0.65, length(fwidth(uvA)));
  float visibleB = 1.0 - smoothstep(0.18, 0.65, length(fwidth(uvB)));
  // Rotate the second map's slopes back into the shared world-space basis.
  return mawNormalMapSlope(uvA) * (0.65 * visibleA)
    + transpose(rotation) * mawNormalMapSlope(uvB) * (0.35 * visibleB);
}

uniform float mawSurfaceDepth;
uniform int mawSoftReliefShadows;
// Quintic value noise with analytic derivatives: x value (0..1), yz slope.
vec3 mawNoiseDerivative(vec2 uv) {
  vec2 cell = floor(uv), x = fract(uv);
  vec2 blend = x*x*x*(x*(x*6.0-15.0)+10.0);
  vec2 derivative = 30.0*x*x*(x-1.0)*(x-1.0);
  float a = mawHash(cell), b = mawHash(cell + vec2(1.0, 0.0));
  float c = mawHash(cell + vec2(0.0, 1.0)), d = mawHash(cell + vec2(1.0));
  float value = a + (b-a)*blend.x + (c-a)*blend.y + (a-b-c+d)*blend.x*blend.y;
  vec2 slope = derivative * vec2(b-a + (a-b-c+d)*blend.y, c-a + (a-b-c+d)*blend.x);
  return vec3(value, slope);
}
// One rotated, scrolling noise octave; slope rotated back to world space.
float mawChopOctave(vec2 p, mat2 rotation, float frequency, vec2 drift, float amplitude, inout vec2 gradient) {
  vec3 n = mawNoiseDerivative(rotation * p * frequency + drift);
  float visibility = 1.0 - smoothstep(0.20, 0.80, length(fwidth(p)) * frequency);
  gradient += transpose(rotation) * n.yz * (amplitude * frequency * visibility);
  return (n.x - 0.5) * amplitude * visibility;
}
// Irregular, rounded chop (roughly one to two wavelets per tile) that gives the
// surface visible relief. Noise octaves only: no directional sines, so nothing
// lines up into long streaks. Larger octaves dominate for chunky, blobby cells.
// xy: world-space slope; z: normalized height, 0 trough .. 1 crest.
vec3 mawSurfaceWavelets(vec2 p, float t) {
  // Wavelet size: 1/1.7 = 1.7x the original cell size. Slopes are deliberately
  // not rescaled, so bigger wavelets keep the same light/shadow contrast.
  p *= 1.0 / 1.7;
  // Wavelet animation rate, relative to the rest of the water (1.3 = 30% faster).
  t *= 1.3;
  // Two warp layers: a broad slow one bends the cells, a faster finer one makes
  // them visibly swell, stretch and roll as they travel (not a rigid slide).
  vec2 warp = vec2(mawNoise(p * 0.35 + vec2(t * 0.12, -t * 0.09)),
    mawNoise(p * 0.35 + vec2(-t * 0.10, t * 0.13) + vec2(9.3, 4.1))) - 0.5;
  vec2 roll = vec2(mawNoise(p * 0.80 + vec2(-t * 0.30, t * 0.22) + vec2(41.2, 7.7)),
    mawNoise(p * 0.80 + vec2(t * 0.26, t * 0.31) + vec2(5.9, 33.4))) - 0.5;
  vec2 q = p + warp * 0.90 + roll * 0.40;
  vec2 gradient = vec2(0.0);
  float h = 0.0;
  // Paired large octaves travel in roughly opposite directions, so their sum
  // keeps forming and breaking crests instead of sliding as one rigid pattern.
  h += mawChopOctave(q, mat2(0.80, 0.60, -0.60, 0.80), 1.25, vec2(t * 0.55, -t * 0.35), 0.110, gradient);
  h += mawChopOctave(q, mat2(-0.28, 0.96, -0.96, -0.28), 1.40, vec2(-t * 0.45, t * 0.50) + vec2(31.7, 5.3), 0.090, gradient);
  h += mawChopOctave(q, mat2(0.53, -0.85, 0.85, 0.53), 2.50, vec2(t * 0.85, t * 0.60) + vec2(17.3, 9.1), 0.050, gradient);
  h += mawChopOctave(q, mat2(-0.96, -0.28, 0.28, -0.96), 4.30, vec2(-t * 1.05, -t * 0.80) + vec2(3.7, 21.4), 0.010, gradient);
  return vec3(gradient, clamp(0.5 + h * 4.0, 0.0, 1.0));
}

uniform float mawFoamOpacity[5];
uniform float mawFoamWidth[5];
uniform float mawFoamSoftness[5];
uniform int mawDebugView;
uniform float mawOpenRippleStrength;
uniform float mawSurfaceGrainStrength;
uniform int mawBubbleCount;
uniform vec4 mawBubbles[16];
uniform float mawBubbleScale[16];
uniform float mawBubbleOpacity;
float mawSmoothMin(float a, float b, float radius) {
  float h = clamp(0.5 + 0.5 * (b - a) / radius, 0.0, 1.0);
  return mix(b, a, h) - radius * h * (1.0 - h);
}
float mawFoamWidthLimit(float requested, float clearance) {
  // Round the switch between configured reach and bank clearance instead of
  // leaving a derivative kink exactly where a channel opens into wider water.
  float radius = min(requested, clearance) * 0.15;
  return max(0.001, mawSmoothMin(requested, clearance, max(radius, 0.001)));
}
float mawBand(float distance, float width, float softness) {
  float aa = max(fwidth(distance), 0.002);
  float feather = min(width * 0.30, max(aa, softness));
  return 1.0 - smoothstep(width - feather, width + feather, distance);
}
float mawSoftProfile(float field, float softness) {
  float aa = max(fwidth(field), 0.002);
  return mix(smoothstep(0.5 - aa, 0.5 + aa, field),
    pow(clamp(field, 0.0, 1.0), 1.0 / max(1.0, softness)), min(1.0, softness));
}
float mawQuintic(float x) {
  return x*x*x*(x*(x*6.0-15.0)+10.0);
}
float mawFacetedBubbleRing(vec2 delta, float radius, float feather, float rotation, float sides) {
  float cs = cos(rotation), sn = sin(rotation);
  vec2 q = vec2(cs * delta.x + sn * delta.y, -sn * delta.x + cs * delta.y);
  q.y *= 1.10;
  float angle = atan(q.y, q.x);
  float halfSector = 3.14159265 / sides;
  float sector = mod(angle + halfSector, 2.0 * halfSector) - halfSector;
  // Mostly straight polygon sides, with softened corners and a slight squash.
  float polygonRadius = length(q) * cos(sector) / cos(halfSector);
  float distance = abs(mix(length(q), polygonRadius, 0.82) - radius);
  feather = max(feather, fwidth(distance) * 0.75);
  return 1.0 - smoothstep(feather * 0.35, feather, distance);
}
float mawBubbleRippleArcs(vec2 delta, float radius, float life, float seed, float pixelWidth, float phaseOffset) {
  float rotation = seed * 6.2831853;
  float cs = cos(rotation), sn = sin(rotation);
  vec2 q = vec2(cs * delta.x + sn * delta.y, -sn * delta.x + cs * delta.y);
  q.y *= 1.10;
  float angle = atan(q.y, q.x);
  // Twelve straight sides give each broken arc an additional corner without
  // rounding off the individual bends. Keep this separate from the small bubbles.
  float halfSector = 3.14159265 / 12.0;
  float sector = mod(atan(q.y, q.x) + halfSector, 2.0 * halfSector) - halfSector;
  float polygonRadius = length(q) * cos(sector) / cos(halfSector);
  float distance = abs(polygonRadius - radius);
  // About 25% wider than the former feathered stroke's half-maximum width.
  // Solid interior with only a pixel-sized antialiasing edge, rather than blur.
  float halfWidth = max(0.022, pixelWidth * 0.85);
  // Three uneven arcs trade emphasis while the gaps gently shift. Evaluating
  // one changing pattern keeps the ring broken even halfway through a change.
  float arcPhase = angle * 3.0 + phaseOffset + 0.70 * sin(life * 6.2831853 + seed * 4.0);
  float arcOffset = mod(arcPhase + 3.14159265, 6.2831853) - 3.14159265;
  // Extend the sweep from about 59 to 76 degrees while retaining three gaps.
  float arcCutoff = -0.40;
  float arcHalfAngle = acos(arcCutoff);
  // A longer entrance wedge widens gradually from the starting point; retain
  // the shorter exit taper and full thickness through the middle of the arc.
  float startTaper = clamp((arcHalfAngle + arcOffset) / (arcHalfAngle * 0.72), 0.0, 1.0);
  float endTaper = clamp((arcHalfAngle - arcOffset) / (arcHalfAngle * 0.39), 0.0, 1.0);
  float tipTaper = min(startTaper, endTaper);
  float taperedWidth = halfWidth * tipTaper;
  float edgeAA = max(fwidth(distance - taperedWidth) * 0.5, 0.0005);
  float stroke = 1.0 - smoothstep(taperedWidth - edgeAA, taperedWidth + edgeAA, distance);
  float arcSignal = cos(arcPhase);
  float arcAA = max(fwidth(arcSignal) * 0.5, 0.001);
  float arcs = smoothstep(arcCutoff - arcAA, arcCutoff + arcAA, arcSignal);
  float emphasis = 0.72 + 0.28 * sin((angle + phaseOffset / 3.0) * 2.0 - life * 5.0 + rotation);
  return stroke * arcs * emphasis;
}
float mawBrokenBubbleRipple(vec2 delta, float life, float seed, float pixelWidth) {
  // Start both sets beyond the small-bubble cluster. Keep the previous final
  // radii/footprint, but avoid tiny, tightly packed strokes at the first pulse.
  float radius = mix(0.40, 0.85, life);
  float innerRadius = mix(0.32, 0.595, life);
  float outer = mawBubbleRippleArcs(delta, radius, life, seed, pixelWidth, 0.0);
  // Half a three-arc period places the smaller echo in the outer ring's gaps.
  float inner = mawBubbleRippleArcs(delta, innerRadius, life, seed, pixelWidth, 3.14159265);
  // Let the outer arcs establish the ripple first. Introduce the inner echo
  // after the initial bubble sequence, then allow the two sets to trade places.
  float innerArrival = smoothstep(0.24, 0.38, life);
  float alternation = (0.5 - 0.5 * cos(life * 12.5663706)) * innerArrival;
  float outerWeight = mix(1.0, 0.18, alternation);
  float innerWeight = mix(0.18, 1.0, alternation) * innerArrival;
  // Trade visibility rather than stacking brightness; retain faint continuity
  // between pulses, with the inner echo arriving later in the event.
  return max(outer * outerWeight, inner * innerWeight);
}
float mawBubbleCluster(vec2 delta, float life, float seed, float pixelWidth) {
  float cluster = 0.0;
  for (int j = 0; j < 3; j++) {
    float index = float(j);
    float order = mod(index + floor(seed * 3.0), 3.0);
    float age = clamp((life - 0.035 - order * 0.14) / 0.32, 0.0, 1.0);
    float pulse = smoothstep(0.0, 0.14, age) * (1.0 - smoothstep(0.42, 1.0, age));
    float angle = seed * 6.2831853 + index * 2.39996;
    vec2 offset = vec2(cos(angle), sin(angle)) * (0.10 + index * 0.045);
    float radius = mix(0.018, 0.070 + index * 0.015, smoothstep(0.0, 0.85, age));
    float bubble = mawFacetedBubbleRing(delta - offset, radius, max(0.014, pixelWidth),
      angle + 0.16 * sin(age * 3.14159265), 5.0 + mod(index, 2.0));
    cluster = max(cluster, bubble * pulse);
  }
  return cluster;
}
float mawBubbleEvent(vec2 delta, float life, float seed) {
  float pixelWidth = max(length(fwidth(delta)) * 0.65, 0.015);
  float ring = mawBrokenBubbleRipple(delta, life, seed, pixelWidth);
  float cluster = mawBubbleCluster(delta, life, seed, pixelWidth);
  float fade = smoothstep(0.0, 0.12, life) * (1.0 - smoothstep(0.50, 1.0, life));
  // Preserve the previous distant fade and one-tile event footprint.
  float visibility = 1.0 - smoothstep(0.07, 0.30, length(fwidth(delta)));
  return max(ring, cluster) * fade * visibility;
}

// Evaluate bands separately, then blend opacity (never distances/contour positions).
struct MawFoam { vec4 bands; float farContact; float approach; };
MawFoam mawFoam(vec4 shoreData, vec2 p, float t, float maskVisible) {
  float rawDistance = max(0.0, shoreData.r);
  float nearDistance = max(0.0, mawSmoothMin(shoreData.g, rawDistance + 0.06, 0.12));
  float farDistance = max(0.0, shoreData.b);
  float halfWidth = max(0.04, shoreData.a);
  float distantFoamClearance = smoothstep(1.5, 3.8, halfWidth);
  float foamWidthPhase = dot(p, normalize(vec2(0.79, 0.61))) * 0.72
    + sin(dot(p, normalize(vec2(-0.38, 0.92))) * 0.29 - t * 0.33) * 0.85 - t * 1.02;
  float foamWidthWave = 0.5 + 0.5 * sin(foamWidthPhase);
  float contactLagWave = 0.5 + 0.5 * sin(foamWidthPhase + 0.70);
  float contactWidthWave = mix(foamWidthWave, contactLagWave, 0.22);
  float contactThreshold = mix(0.900, 0.760, contactWidthWave);

  float contactThresholdLinear = 0.5 - sin(asin(clamp(1.0 - 2.0 * contactThreshold, -1.0, 1.0)) / 3.0);
  float baseContactWidth = (1.0 - contactThresholdLinear) * 1.86;
  float contactWidth = mawFoamWidthLimit(baseContactWidth * mawFoamWidth[0], halfWidth * 0.38);
  float secondWidth = mawFoamWidthLimit(baseContactWidth * 1.5 * mawFoamWidth[1], halfWidth * 0.55);
  float outerWidth = mawFoamWidthLimit(1.86 * mawFoamWidth[2], halfWidth * 0.75);
  float farOuterWidth = mawFoamWidthLimit(3.72 * mawFoamWidth[3], halfWidth * 0.85);
  float nearShore = 1.0 - smoothstep(0.0, outerWidth, nearDistance);
  float farOuterShore = 1.0 - smoothstep(0.0, farOuterWidth, nearDistance);
  float animatedFoamField = mawQuintic(pow(nearShore, mix(2.35, 0.42, foamWidthWave)));
  float animatedFarOuterFoamField = mawQuintic(pow(farOuterShore, mix(1.022, 0.420, foamWidthWave)));
  float animatedContactField = mawBand(nearDistance, contactWidth, 0.014 * mawFoamSoftness[0]);
  float animatedOffsetContactField = mawBand(nearDistance, secondWidth, 0.018 * mawFoamSoftness[1]);
  float farContactPhase = dot(p, normalize(vec2(0.79, 0.61))) * 0.10
    + sin(dot(p, normalize(vec2(-0.38, 0.92))) * 0.045 - t * 0.05) * 0.14 - t * 0.36;
  float farContactBaseWave = 0.5 + 0.5 * sin(farContactPhase);
  float farContactDetailWave = 0.5 + 0.5 * sin(farContactPhase * 1.30 - t * 0.05 + 0.80);
  float farContactWidthWave = mix(farContactBaseWave, farContactDetailWave, 0.02);
  farContactWidthWave = farContactWidthWave * farContactWidthWave * (3.0 - 2.0 * farContactWidthWave);
  float farContactDistance = mix(1.65, 2.688, farContactWidthWave);

  farContactDistance *= mawFoamWidth[4];
  // Far contact formerly relied on disappearing in rivers and had no width cap.
  // Limit its reach there and use exact bank distance: the broad blurred field can
  // collapse to zero throughout a thin river, painting the whole channel.
  farContactDistance = mix(mawFoamWidthLimit(farContactDistance, halfWidth * 0.75),
    farContactDistance, distantFoamClearance);
  float farContactFieldDistance = mix(rawDistance, farDistance, distantFoamClearance);
  float animatedFarContactField = mawBand(farContactFieldDistance, farContactDistance, 0.08 * mawFoamSoftness[4]);
  // Half-strength foam in narrow water, smoothly restoring the open-sea peaks.
  // Reuse the opposite-bank width classification; do not dim bubbles or base water.
  float foamVisibility = maskVisible * mix(0.5, 1.0, distantFoamClearance);
  // Keep the bank-hugging layer present through narrow passages. Width still
  // contracts and shared narrow-water opacity still halves its strength.
  float contactFoamOpacity = animatedContactField * mawFoamOpacity[0] * foamVisibility;
  float offsetContactFoamOpacity = animatedOffsetContactField * mawFoamOpacity[1] * foamVisibility;
  float outerFoamOpacity = mawSoftProfile(animatedFoamField, mawFoamSoftness[2]) * mawFoamOpacity[2] * foamVisibility;
  float farOuterFoamOpacity = mawSoftProfile(animatedFarOuterFoamField, mawFoamSoftness[3])
    * mawFoamOpacity[3] * foamVisibility;
  float farContactFoamOpacity = animatedFarContactField * mawFoamOpacity[4] * foamVisibility;
  // Opacity-aware approach: hidden/zero-opacity layers cannot erase reflections.
  float contactApproach = (1.0 - smoothstep(contactWidth, contactWidth + min(0.8, halfWidth * 0.3), nearDistance))
    * mawFoamOpacity[0];
  float secondApproach = (1.0 - smoothstep(secondWidth, secondWidth + min(0.9, halfWidth * 0.3), nearDistance))
    * mawFoamOpacity[1];
  float outerApproach = (1.0 - smoothstep(0.0, outerWidth + min(1.0, halfWidth * 0.3), nearDistance))
    * mawFoamOpacity[2];
  float farOuterApproach = (1.0 - smoothstep(0.0, farOuterWidth + min(0.9, halfWidth * 0.3), nearDistance))
    * mawFoamOpacity[3];
  float farContactApproach = (1.0 - smoothstep(farContactDistance, farContactDistance + min(1.2, halfWidth * 0.3), farContactFieldDistance))
    * mawFoamOpacity[4];
  float approach = (contactApproach + secondApproach + outerApproach + farOuterApproach + farContactApproach) * foamVisibility;
  return MawFoam(vec4(contactFoamOpacity, offsetContactFoamOpacity, outerFoamOpacity, farOuterFoamOpacity),
    farContactFoamOpacity, approach);
}

// Swash foam variant: one beach-wash front instead of the five contour bands.
// Built like the surface wavelets: noise-warped, rounded lobes that morph while
// the whole front surges in and recedes. x: opacity of the creamy leading edge,
// y: opacity of the milky trailing haze, z: reflection-fade approach,
// w: receded-water film between the front and the bank (painted bank-colored).
// weight: how much the swash replaces the regular foam (0 in narrow water).
uniform int mawSwashFoam;
uniform float mawSwashOpacity;
// Haze style: 0 Classic, 1 Soft veil.
uniform int mawHazeStyle;
uniform sampler2D mawBankColor;
uniform int mawBankColorValid;
// Tile offset re-anchoring the previous bank map until a new one is built.
uniform vec2 mawBankOffset;
vec4 mawSwash(sampler2D mask, vec4 shoreData, vec2 shoreUv, vec2 p, float t, float visibility, out float weight, out float wet) {
  wet = 0.0;
  // Channel width, averaged over a neighbourhood so the swash scale changes
  // gradually along a bank instead of in patches. Land-side mask texels (the
  // mask is a 12-per-tile staircase, so bank fragments can land on them) have
  // a meaningless width and are excluded; those fragments are treated as right
  // at the bank rather than skipped, which used to leave blue sawtooth slivers.
  float centerWeight = smoothstep(0.04, 0.50, shoreData.r);
  float widthSum = max(0.04, shoreData.a) * centerWeight;
  float weightSum = centerWeight;
  for (int ring = 0; ring < 2; ring++) for (int k = 0; k < 4; k++) {
    vec2 o = k == 0 ? vec2(1.0, 0.0) : k == 1 ? vec2(-1.0, 0.0) : k == 2 ? vec2(0.0, 1.0) : vec2(0.0, -1.0);
    vec4 n = texture(mask, shoreUv + o * ((ring == 0 ? 0.75 : 1.75) * mawShoreTransform.y));
    float w = smoothstep(0.04, 0.50, n.r);
    widthSum += max(0.04, n.a) * w;
    weightSum += w;
  }
  float halfWidth = weightSum > 0.001 ? widthSum / weightSum : max(0.04, shoreData.a);
  // One style everywhere: instead of handing narrow water to the old foam
  // (which switched on/off in patches along medium rivers), shrink the whole
  // swash to fit. 1 = open coast, ~0.25 = a one-tile river.
  float scaleT = clamp((halfWidth - 0.6) / 3.9, 0.0, 1.0);
  scaleT = scaleT * scaleT * (3.0 - 2.0 * scaleT);
  float scale = mix(0.25, 1.0, scaleT);
  weight = 1.0;
  if (visibility <= 0.0) return vec4(0.0);
  float rawDistance = max(0.0, shoreData.r);
  float d = max(0.0, mawSmoothMin(shoreData.g, rawDistance + 0.06, 0.12));
  vec2 q = p / 1.7;
  float ts = t * 1.3;
  // Shore-anchored coordinate: each point projected back onto the shoreline
  // along the shore normal (gradient of the blurred bank distance). The front's
  // bulges, surge timing and haze reach are sampled THERE, so they vary only
  // along the shore, never outward. Sampling them at the point itself let the
  // front position change faster than the bank distance going seaward, so the
  // front was crossed twice: two foam layers with a dark sliver between them.
  float gradStep = 0.6 * mawShoreTransform.y;
  vec2 grad = vec2(texture(mask, shoreUv + vec2(gradStep, 0.0)).b - texture(mask, shoreUv - vec2(gradStep, 0.0)).b,
    texture(mask, shoreUv + vec2(0.0, gradStep)).b - texture(mask, shoreUv - vec2(0.0, gradStep)).b);
  vec2 shoreNormal = dot(grad, grad) > 1e-6 ? normalize(grad) : vec2(0.0);
  vec2 qs = (p - shoreNormal * d) / 1.7;
  // Lobes along the shore: the front bulges and dips, and the bulges drift/morph.
  // Broad, smooth bulges; the fine wiggle layer is kept very faint.
  // Broad bulges only; no fine wiggle layer.
  float lobe = mawNoise(qs * 0.70 + vec2(ts * 0.06, -ts * 0.04)) - 0.5;
  float lobeDetail = mawNoise(qs * 2.4 + vec2(-ts * 0.12, ts * 0.09) + vec2(7.1, 3.3)) - 0.5;
  float dw = d + (lobe * 0.60 + lobeDetail * 0.0) * scale;
  // Surge cycle with a slowly varying phase along the coast, so neighbouring
  // stretches of beach wash in at different moments.
  float cyclePhase = ts * 0.55 + mawNoise(qs * 0.35 + vec2(3.7, 11.2)) * 5.0;
  float surge = 0.5 + 0.5 * sin(cyclePhase);
  surge = surge * surge * (3.0 - 2.0 * surge);
  // Strongest while advancing, thinning out on the backwash.
  float advancing = 0.5 + 0.5 * cos(cyclePhase);
  float limit = max(0.10, halfWidth * 0.45);
  // Open water surges further in and out (up to ~1.1 tiles of travel); the
  // extra travel fades away with the scale so rivers keep their small swash.
  float reach = min(mix(0.12, 0.62 + 0.50 * scaleT, surge) * scale, limit);
  float aa = max(fwidth(dw), 0.01);
  // Front position in bank-distance space. A smooth floor keeps even the most
  // landward lobe clear of the terrain, which overlaps the water polygon and
  // used to clip the foam into a cut-off edge.
  float floorDistance = min(0.30, halfWidth * 0.22);
  float front = -mawSmoothMin(-(reach - (lobe * 0.60 + lobeDetail * 0.0) * scale), -floorDistance, 0.12 * scale);
  // Measure the front against a smoother coastline: the broad blur (b) rounds
  // off the tile staircase and small notches. Blend back to the sharper
  // distance where b gets unreliable (narrow water, where it drops below g),
  // and right at the bank so the terrain film still reaches the land.
  float smoothD = max(0.0, shoreData.b);
  float smoothTrust = smoothstep(-0.05, 0.25, shoreData.b - shoreData.g + 0.25) * smoothstep(0.05, 0.35, d);
  float x = mix(d, smoothD, 0.75 * smoothTrust) - front;
  // Leading edge: crisp on the land side, soft decay seaward.
  float edge = smoothstep(-0.07 * scale - aa, aa, x) * exp(-max(x, 0.0) / (0.22 * scale));
  // Fainter remnant of the previous wash, further out and out of phase.
  float surge2 = 0.5 + 0.5 * sin(cyclePhase - 2.1);
  float reach2 = min(mix(0.55, 1.05 + 0.60 * scaleT, surge2) * scale, limit * 1.6);
  float x2 = dw + lobeDetail * 0.06 * scale - reach2;
  // The outer line (and the haze anchored to it) always stays seaward of the
  // main front, never drifting back over the terrain strip.
  x2 = min(x2, x - 0.15 * scale);
  float edge2 = smoothstep(-0.05 * scale - aa, aa, x2) * exp(-max(x2, 0.0) / (0.12 * scale));
  // Haze: anchored to the MAIN front (the foam closest to the terrain) and trailing seaward from it. It is
  // solid right at the line, then thins out into the ocean: opacity falls off
  // smoothly and the milk breaks into wisps that get sparser with distance.
  // Tongues let it reach far out to sea in places (~1.8 to ~4.3 tiles).
  float extent = (0.90 + 1.30 * mawNoise(qs * 0.60 + vec2(-ts * 0.04, ts * 0.03) + vec2(4.4, 27.1))) * scale;
  // Tongues: a few broad stretches of shore where the haze's own wisps trail
  // much further out. Low-frequency and shore-anchored, so the reach changes
  // gradually along the coast (no diagonal edges) and never outward.
  float tongue = smoothstep(0.55, 0.85, mawNoise(qs * 0.45 + vec2(ts * 0.02, -ts * 0.015) + vec2(73.2, 14.6)));
  extent *= 1.0 + 0.7 * tongue;
  float attach = smoothstep(-0.02 * scale - aa, 0.04 * scale + aa, x);
  float outward = max(x, 0.0) / extent;
  // Gentle, long fade so it reads as thinning out rather than ending.
  // Fade the far end over the whole outer half of the reach. A short cut-off
  // left a visible edge, and because the reach varies along the shore that
  // edge ran diagonally across the haze as faint "crossing" lines.
  float falloff = exp(-outward * 1.5) * (1.0 - smoothstep(0.30, 1.15, outward));
  // Wisps: soft-edged milky streaks stretched by a broad warp.
  // Larger, smoother wisps that drift seaward and stretch as they go.
  vec2 wq = q * 1.5 + vec2(ts * 0.08, -ts * 0.05);
  wq += (vec2(mawNoise(q * 0.75 + vec2(ts * 0.04, ts * 0.025) + vec2(19.3, 2.8)),
    mawNoise(q * 0.75 + vec2(-ts * 0.03, ts * 0.04) + vec2(7.7, 15.2))) - 0.5) * 1.6;
  float wisp = mawNoise(wq + vec2(31.0, 12.0)) * 0.75 + mawNoise(wq * 2.1 + vec2(3.3, 41.0)) * 0.25;
  // Near the line almost everything is covered; further out only the densest
  // wisps survive, so the haze thins into separate streaks before vanishing.
  // Solid near the line, a gradual transition into soft separate wisps.
  float threshold = mix(0.0, 0.60, smoothstep(0.05, 0.90, outward));
  float breakup = smoothstep(threshold - 0.16, threshold + 0.16, wisp);
  // Slightly less dense than the full thick version.
  float haze = attach * falloff * breakup * 0.85;
  if (mawHazeStyle == 1) {
    // Soft veil: one smooth milky gradient, no breakup; a gentle, slow
    // brightness drift keeps it from looking flat.
    float veilOut = max(x, 0.0) / (extent * 0.75);
    float veil = 1.0 - smoothstep(0.0, 1.0, veilOut);
    veil *= veil;
    float drift = mawNoise(q * 0.9 + vec2(ts * 0.05, -ts * 0.04) + vec2(11.0, 3.0));
    haze = attach * veil * mix(0.70, 1.0, drift) * 0.80;
  }
  float distantFade = 1.0 - smoothstep(0.10, 0.45, length(fwidth(q)) * 4.0);
  // Only the main front line; the outer echo line has been removed.
  float edgeOpacity = edge * mix(0.55, 1.0, advancing) * mix(0.75, 1.0, distantFade);
  float hazeOpacity = haze * 0.60;
  // Reflection fade: only right at the foam line, gradual, and scaled by the
  // swash opacity. It used to switch reflections fully off in a wide band
  // following the front, which showed as a dark band (and dark creases where
  // the haze thinned), even with the swash at 0%.
  float approach = (1.0 - smoothstep(-0.05 * scale, 0.30 * scale, x)) * 0.5 * min(mawSwashOpacity, 1.0);
  // Slightly fainter in narrow water so rivers are not outlined too heavily.
  float strength = visibility * mawSwashOpacity * mix(0.60, 1.0, scaleT);
  // Runs under the foam line and fades out beneath it, so no rim of dark water
  // shows between the terrain-colored strip and the (semi-transparent) foam.
  float film = (1.0 - smoothstep(-0.02 * scale, 0.10 * scale, x)) * visibility;
  // Only a thin, just-washed line behind the front reads darker; the rest of
  // the strip is the untouched bank color so it meets the terrain seamlessly.
  wet = smoothstep(-0.40 * scale, -0.04 * scale, x);
  return vec4(edgeOpacity * strength, hazeOpacity * strength, approach * visibility, film);
}

// Weather for GPU skybox, mirrored read-only by SkyboxBridge. The mapping is
// the same world-direction -> panorama mapping that plugin uses, so the
// reflection and the sun glint line up with its sky.
uniform int mawSkyEnabled;
uniform sampler2D mawSkyCurrent;
uniform sampler2D mawSkyPrevious;
uniform float mawSkyBlend;
uniform float mawSkyYawOffset;
uniform float mawSkyPitchOffset;
uniform float mawSkyExposure;
uniform vec3 mawSkyLight;
uniform float mawSkyContrast; // 0 flat/overcast sky .. 1 clear sun or moon
uniform vec2 mawCameraAngles; // yaw, pitch (radians)
uniform float mawSkyReflection;
uniform float mawSkyGlint;
uniform int mawReflectionNoise;
uniform int mawGlintFollowsCamera;
uniform vec4 mawSkySun; // brightest point of the panorama: u, v, stand-out (blended over the sky crossfade), glint fade
// Panorama UV for a world direction (the Weather plugin's own mapping).
vec2 mawSkyUv(vec3 worldDirection) {
  vec3 direction = normalize(worldDirection);
  float cameraYaw = mawCameraAngles.x, cameraPitch = mawCameraAngles.y;
  float cys = sin(cameraYaw), cyc = cos(cameraYaw);
  float cps = sin(cameraPitch), cpc = cos(cameraPitch);
  vec3 right = vec3(cyc, 0.0, cys);
  vec3 forward = vec3(-cys * cpc, -cps, cyc * cpc);
  vec3 up = vec3(forward.y * right.z, forward.z * right.x - forward.x * right.z, -forward.y * right.x);
  vec3 cameraRay = vec3(dot(direction, right), dot(direction, up), dot(direction, forward));
  float pitch = cameraPitch - mawSkyPitchOffset;
  float ps = sin(pitch), pc = cos(pitch);
  float directionY = cameraRay.y * pc - cameraRay.z * ps;
  float rotatedZ = cameraRay.y * ps + cameraRay.z * pc;
  float yaw = -cameraYaw + mawSkyYawOffset;
  float ys = sin(yaw), yc = cos(yaw);
  float directionX = cameraRay.x * yc + rotatedZ * ys;
  float directionZ = -cameraRay.x * ys + rotatedZ * yc;
  return vec2(fract(atan(directionX, directionZ) / 6.28318530718),
    clamp(0.5 + asin(clamp(directionY, -1.0, 1.0)) / 3.14159265359, 0.0, 1.0));
}
// Unit direction in panorama space for a panorama UV.
vec3 mawPanoramaDirection(vec2 uv) {
  float azimuth = uv.x * 6.28318530718;
  float elevation = (uv.y - 0.5) * 3.14159265359;
  return vec3(sin(azimuth) * cos(elevation), sin(elevation), cos(azimuth) * cos(elevation));
}
vec3 mawSkyColor(vec3 worldDirection, float lod) {
  vec3 direction = normalize(worldDirection);
  float cameraYaw = mawCameraAngles.x, cameraPitch = mawCameraAngles.y;
  float cys = sin(cameraYaw), cyc = cos(cameraYaw);
  float cps = sin(cameraPitch), cpc = cos(cameraPitch);
  vec3 right = vec3(cyc, 0.0, cys);
  vec3 forward = vec3(-cys * cpc, -cps, cyc * cpc);
  vec3 up = vec3(forward.y * right.z, forward.z * right.x - forward.x * right.z, -forward.y * right.x);
  vec3 cameraRay = vec3(dot(direction, right), dot(direction, up), dot(direction, forward));
  float pitch = cameraPitch - mawSkyPitchOffset;
  float ps = sin(pitch), pc = cos(pitch);
  float directionY = cameraRay.y * pc - cameraRay.z * ps;
  float rotatedZ = cameraRay.y * ps + cameraRay.z * pc;
  float yaw = -cameraYaw + mawSkyYawOffset;
  float ys = sin(yaw), yc = cos(yaw);
  float directionX = cameraRay.x * yc + rotatedZ * ys;
  float directionZ = -cameraRay.x * ys + rotatedZ * yc;
  vec2 uv = vec2(fract(atan(directionX, directionZ) / 6.28318530718),
    clamp(0.5 + asin(clamp(directionY, -1.0, 1.0)) / 3.14159265359, 0.0, 1.0));
  vec3 raw = mix(textureLod(mawSkyPrevious, uv, lod).rgb, textureLod(mawSkyCurrent, uv, lod).rgb, mawSkyBlend);
  return vec3(1.0) - exp(-gpuSkyAdjustColor(raw) * mawSkyExposure);
}

// Skybox fog matching. The Weather plugin fades the scene toward the sky color
// behind each pixel (its own screen projection, heavily blurred) and uses that
// color as fog. These replicate its gpuSkyUvAt/gpuSkyAverage exactly.
uniform int mawSkyFog;
uniform vec2 mawSkyScreen;       // fov, aspect
uniform vec4 mawSkyViewport;     // origin.xy, size.xy
uniform vec2 mawSkyDistanceFade; // draw distance, world light
// Same per-texture detail level as Skybox's terrain lighting (7 at 8K, 6 at 4K, 5 at 2K),
// so water fog matches the fog on the terrain beside it at every resolution.
uniform vec2 mawSkyLods; // current, previous
vec3 mawSkyRawLod7(vec2 uv) {
  return gpuSkyAdjustColor(mix(textureLod(mawSkyPrevious, uv, mawSkyLods.y).rgb, textureLod(mawSkyCurrent, uv, mawSkyLods.x).rgb, mawSkyBlend));
}
vec3 mawSkyScreenColor(vec2 fragCoord) {
  vec2 coord = fragCoord - mawSkyViewport.xy;
  vec2 screen = clamp(coord / max(mawSkyViewport.zw, vec2(1.0)), vec2(0.0), vec2(1.0)) * 2.0 - 1.0;
  float hh = mawSkyScreen.x * 0.5;
  float hv = atan(tan(hh) / max(mawSkyScreen.y, 0.1));
  vec3 ray = normalize(vec3(screen.x * tan(hh), screen.y * tan(hv), 1.0));
  float ps = sin(mawCameraAngles.y + mawSkyPitchOffset), pc = cos(mawCameraAngles.y + mawSkyPitchOffset);
  ray = vec3(ray.x, ray.y * pc - ray.z * ps, ray.y * ps + ray.z * pc);
  float yaw = -mawCameraAngles.x + mawSkyYawOffset;
  float ys = sin(yaw), yc = cos(yaw);
  ray = vec3(ray.x * yc + ray.z * ys, ray.y, ray.z * yc - ray.x * ys);
  float u = fract(atan(ray.x, ray.z) / 6.28318530718);
  float v = clamp(0.5 + asin(clamp(ray.y, -1.0, 1.0)) / 3.14159265359, 0.0, 1.0);
  vec3 c = mawSkyRawLod7(vec2(u, v)) * 4.0;
  c += mawSkyRawLod7(vec2(u - 0.025, v));
  c += mawSkyRawLod7(vec2(u + 0.025, v));
  c += mawSkyRawLod7(vec2(u, clamp(v - 0.018, 0.0, 1.0)));
  c += mawSkyRawLod7(vec2(u, clamp(v + 0.018, 0.0, 1.0)));
  return vec3(1.0) - exp(-c * 0.125 * mawSkyExposure);
}

