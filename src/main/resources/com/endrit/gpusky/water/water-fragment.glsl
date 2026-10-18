#ifdef GPU_SKY_LIGHTING_API
// Apply Skybox before replacing materials, even when Water registered first.
gpuSkyApplyLighting(c, fogColor, fFogAmount);
#endif
bool mawIsWater = mawEnabled != 0 && __WATER_PREDICATE__ && c.a >= 0.999;
if (mawWaterPass == 2 && !mawIsWater) discard;
if (mawIsWater) {
  vec3 geometryNormal = normalize(cross(dFdx(mawWorldPosition), dFdy(mawWorldPosition)));
  vec3 toCamera = normalize(mawToCamera);
  if (dot(geometryNormal, toCamera) < 0.0) geometryNormal = -geometryNormal;
  float horizontalSurface = smoothstep(0.72, 0.90, abs(geometryNormal.y));
  if (mawWaterPass == 2 && horizontalSurface <= 0.0) discard;
  if (horizontalSurface > 0.0) {
    // Discard removes BOTH the original color and its depth. Lowering alpha in
    // the opaque pass alone would still occlude objects drawn later.
    if (mawWaterPass == 1) discard;
    vec2 p = (mawWorldPosition.xz + mawWorldBase) / 128.0;
    float t = mawTime * mawSpeed;
    vec2 gradA0; vec2 gradA1; vec2 gradB0; vec2 gradB1; vec2 rippleGradA; vec2 rippleGradB;
    float waveA0 = mawFilteredSine(p, normalize(vec2(0.94, 0.34)), 0.52, t * 0.34, gradA0);
    float waveA1 = mawFilteredSine(p, normalize(vec2(0.78, 0.63)), 1.10, t * 0.48 + 1.7, gradA1);
    float waveB0 = mawFilteredSine(p, normalize(vec2(-0.42, 0.91)), 0.76, -t * 0.29 + 2.2, gradB0);
    float waveB1 = mawFilteredSine(p, normalize(vec2(-0.83, 0.56)), 1.38, -t * 0.43 + 4.1, gradB1);
    vec2 rippleDirA = normalize(vec2(0.68, -0.73));
    vec2 rippleDirB = normalize(vec2(-0.91, -0.41));
    float irregularWarp = (mawNoise(p * 0.31 + vec2(t * 0.022, -t * 0.017)) - 0.5) * 2.35
      + (mawNoise(p * 0.67 + vec2(-t * 0.014, t * 0.019) + vec2(13.7, 4.2)) - 0.5) * 0.82;
    float rippleA = mawFilteredSine(p, rippleDirA, 1.85, t * 0.55 + waveB0 * 2.05 + waveA1 * 0.78 + waveB1 * 0.24 + irregularWarp, rippleGradA);
    float rippleB = mawFilteredSine(p, rippleDirB, 8.10, -t * 0.47 + waveA0 * 1.72 - waveB1 * 0.68 + waveA1 * 0.22, rippleGradB);
    float rippleDerivativeA = dot(rippleGradA, rippleDirA) / 1.85;
    float rippleDerivativeB = dot(rippleGradB, rippleDirB) / 8.10;
    rippleGradA += (gradB0 * 2.05 + gradA1 * 0.78 + gradB1 * 0.24) * rippleDerivativeA;
    rippleGradB += (gradA0 * 1.72 - gradB1 * 0.68 + gradA1 * 0.22) * rippleDerivativeB;
    vec2 waveGradient = gradA0 * 0.46 + gradA1 * 0.105 + gradB0 * 0.37 + gradB1 * 0.080
      + rippleGradA * 0.006 + rippleGradB * 0.004;
    float waveHeight = waveA0 * 0.46 + waveA1 * 0.105 + waveB0 * 0.37 + waveB1 * 0.080
      + rippleA * 0.006 + rippleB * 0.004;
    float strength = mawWaveStrength * horizontalSurface;
    float broadFootprint = length(fwidth(p * 0.18));
    float broadVisibility = 1.0 - smoothstep(0.45, 1.25, broadFootprint);
    float broadVariation = (0.5 + 0.5 * sin(dot(p, normalize(vec2(0.61, -0.79))) * 0.18 + t * 0.035)) * broadVisibility;
    float calmShallowTone = clamp(0.20 + broadVariation * 0.46, 0.0, 1.0);
    float shallowTone = clamp(calmShallowTone + waveHeight * 0.055, 0.0, 1.0);
    vec3 deepColor = vec3(0.010, 0.100, 0.178);
    vec3 shallowColor = vec3(0.036, 0.245, 0.343);
    vec3 calmWaterColor = mix(deepColor, shallowColor, calmShallowTone);
    vec3 waterColor = mix(deepColor, shallowColor, shallowTone);
    vec2 openRippleDirA = normalize(vec2(0.86, 0.51));
    vec2 openRippleDirB = normalize(vec2(-0.47, 0.88));
    vec2 openRippleTangentA = vec2(-openRippleDirA.y, openRippleDirA.x);
    vec2 openRippleTangentB = vec2(-openRippleDirB.y, openRippleDirB.x);
    vec2 openWarpUvA = p * 0.015000 + vec2(t * 0.020, -t * 0.015) + vec2(3.1, 14.8);
    vec2 openWarpUvB = p * 0.019333 + vec2(-t * 0.017, t * 0.019) + vec2(21.6, 6.4);
    vec2 openWarpA = vec2(mawNoise(openWarpUvA), mawNoise(openWarpUvA + vec2(19.7, 7.3))) - 0.5;
    vec2 openWarpB = vec2(mawNoise(openWarpUvB), mawNoise(openWarpUvB + vec2(8.4, 25.1))) - 0.5;
    vec2 openUvA = vec2(dot(p, openRippleDirA) * 0.031667, dot(p, openRippleTangentA) * 0.048333)
      + openWarpA * 0.62 + vec2(-t * 0.200, t * 0.085);
    vec2 openUvB = vec2(dot(p, openRippleDirB) * 0.024000, dot(p, openRippleTangentB) * 0.037333)
      + openWarpB * 0.54 + vec2(t * 0.150, -t * 0.066);
    float openFieldA = mawNoise(openUvA + vec2(11.8, 27.3));
    float openFieldB = mawNoise(openUvB + vec2(36.2, 9.7));
    float openAmplitudeA = mix(0.48, 1.0, mawNoise(openUvA * 0.37 + vec2(5.2, 18.4)));
    float openAmplitudeB = mix(0.42, 1.0, mawNoise(openUvB * 0.41 + vec2(28.6, 3.9)));
    float openVisibilityA = 1.0 - smoothstep(0.35, 1.10, length(fwidth(openUvA)) * 5.0);
    float openVisibilityB = 1.0 - smoothstep(0.35, 1.10, length(fwidth(openUvB)) * 5.0);
    float openRippleA = (openFieldA - 0.5) * 2.0 * openAmplitudeA * openVisibilityA;
    float openRippleB = (openFieldB - 0.5) * 2.0 * openAmplitudeB * openVisibilityB;
    float openRippleSigned = openRippleA * 0.72 + openRippleB * 0.38;
    float openRippleTexture = clamp(abs(openRippleSigned) * 1.35, 0.0, 1.0);
    waterColor *= 1.0 + (openRippleA * 0.10935 + openRippleB * 0.054675) * mawOpenRippleStrength;
    float grainFootprint = length(fwidth(p)) * 0.900000;
    float grainVisibility = 1.0 - smoothstep(0.30, 1.05, grainFootprint);
    float surfaceGrainA = mawNoise(p * 0.450000 + vec2(t * 0.150, -t * 0.105) + vec2(7.4, 32.1));
    float surfaceGrainB = mawNoise(p * 0.900000 + vec2(-t * 0.120, t * 0.140) + vec2(41.8, 5.6));
    float surfaceGrain = ((surfaceGrainA - 0.5) * 0.68 + (surfaceGrainB - 0.5) * 0.32) * grainVisibility;
    waterColor *= 1.0 + surfaceGrain * 0.0820125 * mawSurfaceGrainStrength;
    vec2 shoreLocal = mawWorldPosition.xz / 128.0;
    vec2 sceneUv = (shoreLocal + vec2(mawShoreTransform.x)) * mawShoreTransform.y;
    // Apply the integral tile offset BEFORE normalization to avoid additional
    // UV rounding drift when the scene base moves.
    vec2 shoreUv = (shoreLocal + (mawShoreHistory != 0 ? mawShoreOffset : vec2(0.0))
      + vec2(mawShoreTransform.x)) * mawShoreTransform.y;
    bool inScene = all(greaterThanEqual(sceneUv, vec2(0.0))) && all(lessThanEqual(sceneUv, vec2(1.0)));
    bool inShoreMask = mawShoreMaskValid != 0 && inScene
      && all(greaterThanEqual(shoreUv, vec2(0.0))) && all(lessThanEqual(shoreUv, vec2(1.0)));
    vec2 coverage = vec2(inShoreMask ? 1.0 : 0.0, 0.0);
    if (mawShoreHistory != 0 && mawShoreMaskValid != 0 && inScene)
      coverage = texture(mawShoreCoverage, sceneUv).rg;
    vec4 shoreData = vec4(8.0);
    if (inShoreMask) shoreData = texture(mawShoreMask, shoreUv);
    float maskVisible = coverage.r;
    vec2 depthTone = mawShallowWaterTone(mawShoreMask, shoreUv, shoreData, coverage.r);
    vec4 shoreWave = vec4(0.0);
    if (mawShoreFollowingWaves != 0) shoreWave = mawShoreWave(shoreData, p, t, coverage.r);
    MawFoam foam = mawFoam(shoreData, p, t, coverage.r);
    if (mawShoreHistory != 0 && mawShoreBlend < 1.0) {
      vec2 previousUv = (shoreLocal + mawPreviousShoreOffset + vec2(mawShoreTransform.x)) * mawShoreTransform.y;
      vec4 previousData = vec4(8.0);
      bool inPrevious = mawPreviousShoreValid != 0 && inScene
        && all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThanEqual(previousUv, vec2(1.0)));
      if (inPrevious) previousData = texture(mawPreviousShoreMask, previousUv);
      float previousVisibility = inPrevious ? coverage.g : 0.0;
      MawFoam previousFoam = mawFoam(previousData, p, t, previousVisibility);
      if (mawShoreFollowingWaves != 0)
        shoreWave = mix(mawShoreWave(previousData, p, t, previousVisibility), shoreWave, mawShoreBlend);
      foam.bands = mix(previousFoam.bands, foam.bands, mawShoreBlend);
      foam.farContact = mix(previousFoam.farContact, foam.farContact, mawShoreBlend);
      foam.approach = mix(previousFoam.approach, foam.approach, mawShoreBlend);
      depthTone = mix(mawShallowWaterTone(mawPreviousShoreMask, previousUv, previousData, previousVisibility), depthTone, mawShoreBlend);
      maskVisible = mix(previousVisibility, maskVisible, mawShoreBlend);
    }
    float rawDistance = max(0.0, shoreData.r);
    float halfWidth = max(0.04, shoreData.a);
    // Replace only the large moving base-color bands in bounded water. Retain
    // the existing ripple/grain multipliers and all later reflections/foam.
    if (depthTone.y > 0.0) {
      float bankDrivenTone = depthTone.x + calmShallowTone * (1.0 - depthTone.y);
      bankDrivenTone = clamp(bankDrivenTone + waveHeight * 0.055 * (1.0 - depthTone.y), 0.0, 1.0);
      waterColor *= mix(deepColor, shallowColor, bankDrivenTone)
        / max(mix(deepColor, shallowColor, shallowTone), vec3(0.001));
    }
    float contactFoamOpacity = foam.bands.x;
    float offsetContactFoamOpacity = foam.bands.y;
    float outerFoamOpacity = foam.bands.z;
    float farOuterFoamOpacity = foam.bands.w;
    float farContactFoamOpacity = foam.farContact;
    float clearWater = 1.0 - smoothstep(0.0, 0.065, foam.approach);
    // Swash variant replaces all five foam bands (bubbles are unaffected).
    vec4 swash = vec4(0.0);
    float swashWet = 0.0;
    if (mawSwashFoam != 0) {
      float swashWeight;
      swash = mawSwash(mawShoreMask, shoreData, shoreUv, p, t, coverage.r, swashWeight, swashWet);
      // Region handoff: cross-fade from the swash of the previous mask, like the
      // classic foam, so a changed shoreline blends instead of snapping.
      if (mawShoreHistory != 0 && mawShoreBlend < 1.0) {
        vec2 previousSwashUv = (shoreLocal + mawPreviousShoreOffset + vec2(mawShoreTransform.x)) * mawShoreTransform.y;
        bool inPreviousSwash = mawPreviousShoreValid != 0 && inScene
          && all(greaterThanEqual(previousSwashUv, vec2(0.0))) && all(lessThanEqual(previousSwashUv, vec2(1.0)));
        vec4 previousSwashData = inPreviousSwash ? texture(mawPreviousShoreMask, previousSwashUv) : vec4(8.0);
        float previousSwashWeight, previousSwashWet;
        vec4 previousSwash = mawSwash(mawPreviousShoreMask, previousSwashData, previousSwashUv, p, t,
          inPreviousSwash ? coverage.g : 0.0, previousSwashWeight, previousSwashWet);
        swash = mix(previousSwash, swash, mawShoreBlend);
        swashWet = mix(previousSwashWet, swashWet, mawShoreBlend);
      }
      // Hand narrow water back to the regular foam bands.
      float keep = 1.0 - swashWeight;
      contactFoamOpacity *= keep; offsetContactFoamOpacity *= keep; outerFoamOpacity *= keep;
      farOuterFoamOpacity *= keep; farContactFoamOpacity *= keep;
      clearWater = mix(clearWater, 1.0 - clamp(swash.z, 0.0, 1.0), swashWeight);
      if (mawBankColorValid == 0) swash.w = 0.0;
    }
    // Blend normal detail away as foam approaches, leaving all foam colors and
    // opacity untouched. The layer footprints also fade it out in distant water.
    vec2 normalDetail = mawWaterNormalDetail(p, t) * mawNormalDetailStrength * clearWater;
    // Surface relief: crisp wavelets that SHADE the base color (light facing,
    // view facing, trough occlusion) rather than only steering faint highlights.
    vec3 wavelets = mawSurfaceDepth > 0.0 ? mawSurfaceWavelets(p, t) : vec3(0.0, 0.0, 0.5);
    float reliefAmount = mawSurfaceDepth * horizontalSurface * mix(0.45, 1.0, clearWater);
    vec2 reliefGradient = wavelets.xy * reliefAmount;
    float shoreInfluence = shoreWave.w * clearWater;
    vec2 surfaceGradient = waveGradient * (1.0 - shoreInfluence) + shoreWave.xy * clearWater + normalDetail
      + reliefGradient * 0.60;
    float surfaceWaveHeight = waveHeight * (1.0 - shoreInfluence) + shoreWave.z * clearWater;
    vec3 waterNormal = normalize(vec3(-surfaceGradient.x * strength, 1.0, -surfaceGradient.y * strength));
    if (dot(waterNormal, toCamera) < 0.0) waterNormal = -waterNormal;
    vec3 lightDirection = normalize(vec3(-0.34, 0.86, 0.38));
    // With the Weather skybox, highlights and wavelet shading follow its sun/moon
    // (kept a little above the horizon so low suns still light the waves).
    if (mawSkyEnabled != 0) {
      // Flat skies report "straight up" with low contrast; keep the default
      // light there and blend toward the real sun as the sky gets contrast.
      vec3 skySun = normalize(mawSkyLight);
      skySun = normalize(vec3(skySun.x, max(skySun.y, 0.12), skySun.z));
      lightDirection = normalize(mix(lightDirection, skySun, smoothstep(0.05, 0.50, mawSkyContrast)));
    }
    vec3 halfDirection = normalize(lightDirection + toCamera);
    if (mawSkyEnabled != 0) {
      // The skybox sun uses the sky's +Y-up convention; the view vector uses the
      // scene's. Mixed, light + view can cancel to ~zero for some camera angles,
      // making the built-in highlights blow up into hard screen-space shapes.
      // Put the light in the view's convention (the camera is always above water).
      vec3 sceneLight = vec3(lightDirection.x, lightDirection.y * (toCamera.y < 0.0 ? -1.0 : 1.0), lightDirection.z);
      halfDirection = normalize(sceneLight + toCamera + vec3(0.0, 1e-4, 0.0));
    }
    vec2 macroSpecGradient = gradA0 * 0.10 + gradB0 * 0.08;
    vec2 specGradientA = (macroSpecGradient + rippleGradA * 0.34) * (1.0 - shoreInfluence)
      + shoreWave.xy * clearWater + normalDetail;
    vec3 specularNormalA = normalize(vec3(-specGradientA.x * strength, 1.0, -specGradientA.y * strength));
    float specularFacingA = max(dot(specularNormalA, halfDirection), 0.0);
    float roughnessNoiseA = mawNoise(p * 0.22 + vec2(t * 0.010, -t * 0.008) + vec2(5.3, 19.1));
    float roughnessNoiseB = mawNoise(p * 0.49 + vec2(-t * 0.007, t * 0.011) + vec2(31.4, 8.6));
    float roughness = mix(0.38, 0.76, roughnessNoiseA * 0.68 + roughnessNoiseB * 0.32);
    roughness = clamp(roughness + (0.28 - openRippleTexture) * 0.045, 0.30, 0.84);
    float glintExponentA = mix(5.5, 2.2, roughness);
    float roughnessEnergy = mix(0.82, 0.42, roughness);
    float reflectiveCrest = mix(rippleA, shoreWave.z / max(shoreWave.w * 0.32, 0.001), shoreInfluence);
    float crestSide = smoothstep(-0.55, 0.75, reflectiveCrest);
    float glintA = pow(specularFacingA, glintExponentA) * roughnessEnergy * mix(0.08, 1.0, crestSide);
    if (mawAlternateWaves != 0) {
      // Keep the original wave paths, but spread light across a broader crest
      // instead of concentrating it into a narrow ridge or roughness hotspot.
      float widerCrestSide = smoothstep(-0.95, 0.70, reflectiveCrest);
      float widerLobe = pow(specularFacingA, glintExponentA * 0.30);
      widerLobe = mix(widerLobe, smoothstep(0.0, 0.90, widerLobe), 0.60);
      float evenEnergy = mix(0.60, roughnessEnergy, 0.35);
      glintA = widerLobe * evenEnergy * mix(0.08, 1.0, widerCrestSide);
    }
    vec2 rippleTangent = vec2(-rippleDirA.y, rippleDirA.x);
    vec2 longSegmentCoordinates = vec2(dot(p, rippleTangent) * 0.075 - t * 0.012, dot(p, rippleDirA) * 0.028 + t * 0.006);
    vec2 detailSegmentCoordinates = vec2(dot(p, rippleTangent) * 0.29 - t * 0.041, dot(p, rippleDirA) * 0.105 + t * 0.014);
    float longSegment = smoothstep(0.24, 0.64, mawNoise(longSegmentCoordinates + vec2(8.1, 23.7)));
    float segmentDetail = smoothstep(0.16, 0.74, mawNoise(detailSegmentCoordinates + vec2(17.2, 9.4)));
    float breakupA = longSegment * mix(0.30, 1.0, segmentDetail);
    if (mawAlternateWaves != 0) {
      // Retain the original gaps and lengths, with less patchy lighting along
      // each crest. The new normal-map glints are separate and unchanged.
      breakupA = pow(longSegment, 0.65) * mix(0.75, 1.0, segmentDetail);
    }
    breakupA = mix(breakupA, 0.85, shoreInfluence);
    float microFootprint = length(fwidth(p)) * 1.85;
    float microVisibility = 1.0 - smoothstep(0.30, 1.02, microFootprint);
    float fresnel = pow(1.0 - clamp(dot(waterNormal, toCamera), 0.0, 1.0), 5.0);
    vec3 reflectedSky = mix(vec3(0.165, 0.280, 0.390), fogColor.rgb, 0.42);
    // The skybox works in a +Y-up sky convention, while the scene's vertical
    // axis may point the other way (OSRS heights are negative upward). The camera
    // is always above the water, so this view vector is "toward the camera" in
    // the sky's convention. Mixing the two made the sun glint never line up.
    vec3 skyView = normalize(vec3(toCamera.x, abs(toCamera.y), toCamera.z));
    vec3 skyMirror = vec3(0.0);
    // 0 near the camera .. 1 far away (~5.5 to ~28 tiles): wave detail in the
    // reflection fades with distance, like the overpaint, instead of keeping
    // full patch/gap contrast all the way to the horizon.
    float reflectionDistance = smoothstep(704.0, 3584.0, length(mawToCamera));
    if (mawSkyEnabled != 0) {
      // Mirror the skybox along the wave-perturbed reflection direction (never
      // below the horizon). A blurred mip keeps it soft on small ripples.
      // Reflection normal: main waves plus the surface-depth wavelets, with
      // extra slope at grazing angles where a flat-looking normal would mirror
      // nearly the same sky everywhere (flat fill at low camera angles).
      float grazing = 1.0 - clamp(skyView.y, 0.0, 1.0);
      vec2 reflectGradient = surfaceGradient * strength + reliefGradient * 1.1;
      reflectGradient *= mix(1.0, 3.5, grazing * grazing);
      vec3 reflectNormal = normalize(vec3(-reflectGradient.x, 1.0, -reflectGradient.y));
      vec3 reflectDirection = reflect(-skyView, reflectNormal);
      reflectDirection.y = abs(reflectDirection.y);
      // Sharper sky at grazing angles so the distorted shapes stay readable.
      skyMirror = mawSkyColor(reflectDirection, mix(2.5, 1.0, grazing));
      // Wave-shape shading: crests and slopes facing the camera catch a little
      // more sky, mostly at low angles.
      vec2 viewFlat = skyView.xz / max(length(skyView.xz), 0.0001);
      float shape = (wavelets.z - 0.5) * 0.55 * min(reliefAmount * 8.0, 1.0)
        + dot(reflectGradient, viewFlat) * 1.20;
      // Brighten only: darkening back slopes read as dark blotches on calm water.
      skyMirror *= 1.0 + clamp(shape * grazing, 0.0, 0.30);
      // Far away, blend toward the calm flat-water reflection.
      vec3 flatReflect = reflect(-skyView, vec3(0.0, 1.0, 0.0));
      flatReflect.y = abs(flatReflect.y);
      skyMirror = mix(skyMirror, mawSkyColor(flatReflect, 2.5), reflectionDistance * 0.65);
      // reflectedSky feeds the existing pre-brightness terms, which were tuned
      // for a dark sky color; scale the skybox down to that range there.
      reflectedSky = mix(reflectedSky, skyMirror * 0.38, 0.85);
    }
    waterColor = mix(waterColor, reflectedSky, fresnel * mawHighlightIntensity * 0.19 * clearWater);
    vec3 reliefNormal = normalize(vec3(-reliefGradient.x, 1.0, -reliefGradient.y));
    // Relative to a flat surface, so a zero depth setting changes nothing.
    float reliefLight = dot(reliefNormal, lightDirection) - lightDirection.y;
    float reliefView = dot(reliefNormal, toCamera) - toCamera.y;
    float reliefCrest = (wavelets.z - 0.45) * reliefAmount;
    // Slopes facing the light brighten; slopes facing the camera look down into
    // the darker water body; troughs get a little occlusion, crests lift.
    float reliefShade = reliefLight * 1.60 - reliefView * 0.90 + reliefCrest * 0.30;
    // Softer-shadow variant: scale only the darkening half; brightening, sky
    // reflection and crest glints stay identical.
    if (mawSoftReliefShadows != 0 && reliefShade < 0.0) reliefShade *= 0.40;
    waterColor *= clamp(1.0 + reliefShade, mawSoftReliefShadows != 0 ? 0.82 : 0.55, 1.60);
    // Slopes tilted away from the camera pick up the sky reflection.
    float reliefSky = clamp(-reliefView * 2.5, 0.0, 1.0);
    waterColor = mix(waterColor, reflectedSky * 1.15, reliefSky * 0.35);
    float broadSheen = pow(max(dot(waterNormal, halfDirection), 0.0), 5.5) * 0.050;
    float environmentGrazing = pow(max(1.0 - dot(waterNormal, toCamera), 0.0), 0.65);
    float environmentView = mix(0.90, 1.0, environmentGrazing);
    float environmentSlope = smoothstep(0.025, 0.28, length(surfaceGradient) * strength);
    float environmentCrest = smoothstep(-0.50, 0.60, surfaceWaveHeight);
    float environmentWave = mix(0.32, 1.0, clamp(environmentSlope * 0.72 + environmentCrest * 0.28, 0.0, 1.0));
    float directionalSheenPresence = smoothstep(0.012, 0.040, broadSheen);
    float environmentSheen = environmentView * environmentWave * 0.050 * (1.0 - directionalSheenPresence);
    float fineGlints = glintA * breakupA * microVisibility;
    // Broader, more evenly lit crests cover more area; lower their brightness
    // without narrowing the shape or dimming the separate normal-map glints.
    if (mawAlternateWaves != 0) fineGlints *= 0.35;
    // Two successive 30% boosts give 1.69x coastal crest light; fade with
    // its normal blend so offshore waves and fine-detail glints are unchanged.
    if (mawShoreFollowingWaves != 0)
      fineGlints *= pow(1.0 + 0.30 * clamp(shoreInfluence / 0.85, 0.0, 1.0), 2.0);
    // Give the fine normal-map facets their own tighter, brighter highlight,
    // rather than boosting the existing broad sheen or reflection-line opacity.
    float normalDetailPresence = smoothstep(0.008, 0.075, length(normalDetail) * strength);
    float normalGlints = pow(max(dot(waterNormal, halfDirection), 0.0), 18.0) * normalDetailPresence;
    // Tight glints on wavelet crests only; troughs stay matte.
    float reliefGlint = pow(max(dot(reliefNormal, halfDirection), 0.0), 48.0)
      * smoothstep(0.55, 0.95, wavelets.z) * min(reliefAmount, 1.0);
    float specular = (broadSheen + environmentSheen + fineGlints * 0.20 + normalGlints * 0.080
      + reliefGlint * 0.35) * clearWater;
    vec3 specularColor = mix(vec3(0.595, 0.755, 0.880), vec3(0.95, 0.98, 1.0), clamp(max(fineGlints, normalGlints), 0.0, 1.0));
    waterColor += specularColor * specular * mawHighlightIntensity * 1.35;
    waterColor = mix(waterColor, vec3(0.55, 0.67, 0.77), clamp(farOuterFoamOpacity, 0.0, 1.0));
    waterColor = mix(waterColor, vec3(0.60, 0.72, 0.82), clamp(outerFoamOpacity, 0.0, 1.0));
    float waterLuminance = dot(waterColor, vec3(0.2126, 0.7152, 0.0722));
    waterColor = mix(waterColor, vec3(waterLuminance), 0.65);
    waterColor *= mawWaterBrightness;
    // Water color option: shift the hue to the chosen color in proportion to
    // each pixel's brightness, so dark and light areas both take the new color
    // and keep their light/dark pattern. The default color changes nothing.
    {
      // The water's real (desaturated) colour at this point, so a grey pick is neutral.
      const vec3 defaultWaterColor = vec3(92.0, 138.0, 159.0) / 255.0;
      const vec3 colorLuma = vec3(0.2126, 0.7152, 0.0722);
      float relativeLight = dot(waterColor, colorLuma) / dot(defaultWaterColor, colorLuma);
      waterColor = max(waterColor + (mawWaterTint - defaultWaterColor) * relativeLight, vec3(0.0));
    }
    // Water-noise mask shared by the skybox reflection and the sun/moon glint:
    // two drifting, world-anchored noise layers give irregular brighter and
    // duller patches, like light on moving water (0..1).
    vec2 noisePos = mawWorldPosition.xz;
    float reflectionNoise = smoothstep(0.28, 0.72,
      mawNoise(noisePos / 23.0 + vec2(mawTime * 0.22, -mawTime * 0.14)) * 0.6
      + mawNoise(noisePos / 8.5 + vec2(-mawTime * 0.38, mawTime * 0.30)) * 0.4);
    float reflectionNoiseOn = mawReflectionNoise != 0 ? 1.0 : 0.0;
    if (mawSkyEnabled != 0) {
      // Skybox mirror, applied after brightness/desaturation so it keeps the
      // sky's real color instead of being boosted to white.
      // Amount: per-wave Fresnel up close, a flat-water Fresnel far away, so the
      // patches and gaps merge into one even sheen toward the horizon.
      float flatFresnel = pow(1.0 - clamp(skyView.y, 0.0, 1.0), 5.0);
      float mirrorFresnel = mix(fresnel, flatFresnel, reflectionDistance * 0.65);
      // The more evened out (further away), the weaker the reflection: up to 65%
      // less at full distance (35% of the reflection remains).
      // Slightly desaturated so a colorful skybox brightens the water rather than
      // making it look more vibrant.
      vec3 mirrorColor = mix(vec3(dot(skyMirror, vec3(0.2126, 0.7152, 0.0722))), skyMirror, 0.70);
      mirrorColor *= 1.12; // a little brighter, same saturation
      float mirrorAmount = clamp((0.06 + 0.45 * mirrorFresnel) * mawSkyReflection * mix(1.0, mix(0.902, 1.056, reflectionNoise), reflectionNoiseOn) * (1.0 - 0.65 * reflectionDistance), 0.0, 1.0) * clearWater;
      {
        // Keep the water's own shading: carry its wave/depth light and dark
        // (relative to the calm water tone) onto the reflection, and let a
        // little of the water's own colour show through.
        const vec3 shadeLuma = vec3(0.2126, 0.7152, 0.0722);
        float waterShade = dot(waterColor, shadeLuma) / max(dot(calmWaterColor * mawWaterBrightness, shadeLuma), 0.002);
        mirrorColor *= clamp(waterShade, 0.45, 2.2);
        mirrorAmount *= 0.85;
      }
      {
        // Adapt to the sky: a flat, dull sky (no sun/bright area standing out)
        // reflects up to 45% less.
        mirrorAmount *= mix(0.55, 1.0, smoothstep(0.15, 0.80, mawSkySun.z));
      }
      waterColor = mix(waterColor, mirrorColor, mirrorAmount);
    }
    if (mawSkyEnabled != 0 && mawSkyGlint > 0.0) {
      // Glints: where a wave facet reflects a light source in the panorama.
      // Steeper facets than the visible waves scatter it into a sparkling path.
      vec2 glintGradient = (surfaceGradient * strength + reliefGradient * 0.9) * 1.8;
      vec3 glintNormal = normalize(vec3(-glintGradient.x, 1.0, -glintGradient.y));
      vec3 glintRay = reflect(-skyView, glintNormal);
      glintRay.y = abs(glintRay.y);
      vec3 glintRayDirection = mawPanoramaDirection(mawSkyUv(glintRay));
      // The calm (wave-free) reflection tells how far a pixel lies to the side
      // of a glint's centre: the centre stays solid, the sides break into ever
      // smaller, sparser ripple sparkles without dimming.
      vec3 calmRay = reflect(-skyView, vec3(0.0, 1.0, 0.0));
      calmRay.y = abs(calmRay.y);
      vec3 calmDirection = mawPanoramaDirection(mawSkyUv(calmRay));
      const vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
      float glintMask = mix(1.0, mix(0.734, 1.105, reflectionNoise), reflectionNoiseOn);
      // Fade glints out on and just around foam (swash line, haze, film and
      // the classic foam bands) so they never sparkle through the foam.
      float foamCover = max(max(swash.x, swash.y), clamp(swash.w, 0.0, 1.0));
      foamCover = max(foamCover, max(max(contactFoamOpacity, offsetContactFoamOpacity),
        max(max(outerFoamOpacity, farOuterFoamOpacity), farContactFoamOpacity)));
      float glintFoamFade = 1.0 - smoothstep(0.02, 0.30, foamCover);
      // Layer 0: the sun/moon found in the panorama (SkyboxBridge), only when it
      // clearly stands out. Layer 1 (optional): a virtual light ahead of the
      // camera, coloured live by that part of the sky.
      for (int layer = 0; layer < 2; layer++) {
        vec2 glintSunUv = mawSkySun.xy;
        float glintLod = 2.0;
        float sunPresence = smoothstep(0.15, 0.50, mawSkySun.z) * mawSkySun.w;
        float spread = 1.0;   // angular size multiplier
        float edgeSoft = 0.0; // extra soft fade toward the edges
        if (layer == 1) {
          if (mawGlintFollowsCamera == 0) break;
          // Same heading as the camera; raised less than the camera looks down,
          // so its reflection lands further out on the water than the player.
          float cameraYaw = mawCameraAngles.x;
          float lookDown = clamp(abs(sin(mawCameraAngles.y)) * 0.55, 0.06, 0.60);
          float across = sqrt(1.0 - lookDown * lookDown);
          vec3 virtualSun = vec3(-sin(cameraYaw) * across, lookDown, cos(cameraYaw) * across);
          glintSunUv = mawSkyUv(virtualSun);
          glintLod = 4.0;
          // ~71% weaker overall (30%, 30%, 40% cuts), and up to a further 60% weaker as the camera
          // tilts toward a top-down view.
          float topDown = smoothstep(0.45, 0.95, abs(sin(mawCameraAngles.y)));
          sunPresence = 0.294 * mix(1.0, 0.40, topDown);
          spread = 3.0;   // area: about three times as wide and deep
          edgeSoft = 1.0;
        }
        if (sunPresence <= 0.0) continue;
        vec3 glintSunDirection = mawPanoramaDirection(glintSunUv);
        // A bigger area comes from steeper facets (each wave tilts the reflection
        // further), not from a looser match, so every sparkle stays as crisp as
        // the sun/moon glint's.
        vec3 layerRayDirection = glintRayDirection;
        if (spread > 1.0) {
          // Wider than deep: tilt facets 1.6x more sideways (across the camera
          // heading) than along it, so the glint spreads out to the sides.
          vec2 heading = vec2(-sin(mawCameraAngles.x), cos(mawCameraAngles.x));
          vec2 alongPart = heading * dot(glintGradient, heading);
          vec2 steepGradient = alongPart * spread + (glintGradient - alongPart) * spread * 1.6;
          vec3 steepRay = reflect(-skyView, normalize(vec3(-steepGradient.x, 1.0, -steepGradient.y)));
          steepRay.y = abs(steepRay.y);
          layerRayDirection = mawPanoramaDirection(mawSkyUv(steepRay));
        }
        float sunAlign = max(dot(layerRayDirection, glintSunDirection), 0.0);
        float sideAngle = acos(clamp(dot(calmDirection, glintSunDirection), -1.0, 1.0));
        // Same sharpness for every layer; only the area (side range) grows.
        float side = smoothstep(0.0, 0.42 * spread, sideAngle);
        float sharpness = mix(70.0, 3000.0, side * side);
        float sparkle = pow(sunAlign, sharpness) * mix(1.0, 1.8, side) * (1.0 - smoothstep(0.42 * spread, 0.55 * spread, sideAngle));
        float core = pow(sunAlign, 900.0) * 1.2;
        float halo = pow(sunAlign, 10.0) * 0.04 * (1.0 - side);
        // Edges thin out (sparser sparkles) and only lose a little brightness.
        sparkle *= mix(1.0, 1.0 - 0.4 * smoothstep(0.5, 1.0, side), edgeSoft);
        vec3 rawSun = mix(textureLod(mawSkyPrevious, glintSunUv, glintLod).rgb, textureLod(mawSkyCurrent, glintSunUv, glintLod).rgb, mawSkyBlend);
        vec3 sunColor = vec3(1.0) - exp(-rawSun * mawSkyExposure);
        vec3 glintColor = sunColor / max(dot(sunColor, lumaWeights), 0.25);
        if (layer == 1) glintColor = vec3(1.0); // camera glint: plain white
        waterColor += glintColor * (sparkle + core + halo) * glintMask * sunPresence * mawSkyGlint * clearWater * glintFoamFade;
      }
    }
    waterColor = mix(waterColor, vec3(1.0), clamp(farContactFoamOpacity, 0.0, 1.0));
    waterColor = mix(waterColor, vec3(1.0), clamp(offsetContactFoamOpacity, 0.0, 1.0));
    waterColor = mix(waterColor, vec3(1.0), clamp(contactFoamOpacity, 0.0, 1.0));
    if (mawSwashFoam != 0 && swash.w > 0.0) {
      // Terrain continues up to the foam: paint the receded film with the
      // terrain's own Gouraud colors, rasterized at 4 texels per tile.
      vec3 bank = texture(mawBankColor, (shoreLocal + mawBankOffset + vec2(mawShoreTransform.x)) * mawShoreTransform.y).rgb;
      // Gentle wet-sand darkening toward the foam: 4.8% darker (60% of the former 8%).
      waterColor = mix(waterColor, bank * mix(1.0, 0.952, swashWet), clamp(swash.w, 0.0, 1.0));
    }
    if (mawSwashFoam != 0) {
      // Foam is mostly withheld where it lies over the wet-sand strip, so that
      // strip keeps one even wet color instead of being lightened back to dry
      // sand by the foam line; the foam then starts where the water begins.
      // Ride the surface-depth wavelets: foam is denser on crests, thinner in troughs.
      float swashRelief = mix(0.70, 1.15, wavelets.z);
      waterColor = mix(waterColor, vec3(1.0), clamp(swash.y * swashRelief * 0.42 * (1.0 - 0.85 * clamp(swash.w, 0.0, 1.0)), 0.0, 1.0));
      // Foam opacity: line at 22%, haze at 42% of the original swash strength.
      waterColor = mix(waterColor, vec3(1.0), clamp(swash.x * swashRelief * 0.22 * (1.0 - 0.85 * clamp(swash.w, 0.0, 1.0)), 0.0, 1.0));
    }

    float bubbleAlpha = 0.0;
    for (int i = 0; i < mawBubbleCount; i++) {
      vec4 event = mawBubbles[i];
      vec2 delta = (p - event.xy) / max(mawBubbleScale[i], 0.6);
      if (dot(delta, delta) > 1.0) continue;
      float life = clamp(event.z / 3.5, 0.0, 1.0);
      float eventAlpha = mawBubbleEvent(delta, life, event.w);
      bubbleAlpha = max(bubbleAlpha, eventAlpha);
    }
    bubbleAlpha *= mawBubbleOpacity * smoothstep(0.05, 0.25, rawDistance) * maskVisible;
    waterColor = mix(waterColor, vec3(1.0), clamp(bubbleAlpha, 0.0, 1.0));
    if (mawDebugView == 6) {
      // Distance in tiles: dark at the bank, progressively lighter offshore.
      waterColor = mix(vec3(0.08, 0.15, 0.22), vec3(0.85), clamp(rawDistance / 4.0, 0.0, 1.0));
    } else if (mawDebugView == 7) {
      waterColor = mix(vec3(0.9, 0.20, 0.12), vec3(0.08, 0.55, 0.85), smoothstep(0.4, 4.0, halfWidth));
    }
    if (mawDebugView != 0 && !inShoreMask) waterColor = vec3(0.65, 0.05, 0.65);
    // Transparent water never mixes in the stock texture, even on sloped banks.
    vec3 modernWater = mawWaterPass == 2 ? waterColor : mix(c.rgb, waterColor, horizontalSurface);
    float waterAlpha = mawWaterPass == 2 ? clamp(mawWaterOpacity, 0.0, 1.0) : c.a;
    // Swash film is painted terrain, so it is opaque like the bank it continues.
    if (mawWaterPass == 2 && mawSwashFoam != 0) waterAlpha = mix(waterAlpha, 1.0, clamp(swash.w, 0.0, 1.0));
    if (mawWaterPass == 2 && mawDepthFade != 0) {
      ivec2 pixel = ivec2(gl_FragCoord.xy) - mawWaterViewport.xy;
      float sceneDepth = texelFetch(mawSceneDepth, pixel, 0).r;
      float deepFade = mawSubmersionFade(gl_FragCoord.z, sceneDepth, abs(mawToCamera.y)) * mawBankFadeAt(mawWorldPosition);
      // Deep objects fade into the same look as open water (water over the empty
      // background), not into solid water color, so they leave no light columns.
      // Final = mix(object, mix(W, deepLook, f), mix(a, 1, f)).
      vec3 deepLook = mix(mawOpenWaterBackground(gl_FragCoord.xy, fogColor.rgb), modernWater, waterAlpha);
      // Clearer water over objects just below the surface (something is drawn
      // behind: not the empty background), so even dark rocks read through it.
      bool objectBehind = sceneDepth - mawDepthBias > 0.0000001;
      // (The opaque swash film over the bank stays opaque.)
      float filmCover = mawSwashFoam != 0 ? clamp(swash.w, 0.0, 1.0) : 0.0;
      float nearAlpha = objectBehind ? mix(waterAlpha * MAW_NEAR_OBJECT_CLARITY, waterAlpha, filmCover) : waterAlpha;
      modernWater = mix(modernWater, deepLook, deepFade);
      waterAlpha = mix(nearAlpha, 1.0, deepFade);
    }
    vec3 waterFogColor = fogColor.rgb;
    if (mawSkyFog != 0) {
      // Match the Weather plugin: fade toward the sky behind this pixel with
      // distance ("world light"), then fog in that same sky color.
      vec3 skyBehind = mawSkyScreenColor(gl_FragCoord.xy);
      float distanceRatio = length(mawToCamera) / max(mawSkyDistanceFade.x, 1.0);
      modernWater = mix(modernWater, skyBehind, smoothstep(0.01, 1.0, distanceRatio) * 0.50 * mawSkyDistanceFade.y);
      waterFogColor = skyBehind;
    }
    FragColor = vec4(mix(modernWater, waterFogColor, fFogAmount), waterAlpha);
  }
}

// The water is already composited when the host draws alpha faces. Only fragments
// BEHIND the captured water surface inherit its color/opacity; above-water faces
// keep their original appearance and alpha. Reversed depth: larger is nearer.
if (mawUnderwaterAlpha != 0 && mawWaterPass == 0) {
  ivec2 pixel = ivec2(gl_FragCoord.xy) - mawWaterViewport.xy;
  if (all(greaterThanEqual(pixel, ivec2(0))) && all(lessThan(pixel, mawWaterViewport.zw))) {
    vec4 surface = texelFetch(mawWaterSurface, pixel, 0);
    float waterDepth = texelFetch(mawWaterDepth, pixel, 0).r;
    if (surface.a > 0.0 && gl_FragCoord.z < waterDepth - 0.0000001) {
      // B' = mix(B,W,w). Feeding mix(C,W,w) to ordinary alpha blending gives
      // mix(mix(B,C,a),W,w): the same result as the object drawn BELOW water.
      float nearZ = waterDepth - mawDepthBias;
      float farZ = gl_FragCoord.z - mawDepthBias;
      float verticalToWater = nearZ > 0.0 ? abs(mawToCamera.y) * max(0.0, farZ / nearZ) : 0.0;
      vec3 waterPosition = mawWorldPosition + mawToCamera * (1.0 - max(0.0, farZ / max(nearZ, 0.0000001)));
      float deepFade = mawSubmersionFade(waterDepth, gl_FragCoord.z, verticalToWater) * mawBankFadeAt(waterPosition);
      float opacity = mix(surface.a * MAW_NEAR_OBJECT_CLARITY, 1.0, deepFade);
      vec3 deepLook = mix(mawOpenWaterBackground(gl_FragCoord.xy, fogColor.rgb), surface.rgb, surface.a);
      FragColor.rgb = mix(FragColor.rgb, mix(surface.rgb, deepLook, deepFade), opacity);
    }
  }
}
