# Skybox for GPU

A RuneLite plugin that adds panoramic skyboxes, sky lighting, world-space weather
and animated, reflective water. It runs on RuneLite's GPU plugin through the
experimental **GPU extension API** by Adam.

## Features

**Sky and weather**
- Panoramic skybox presets (Daylight, Sunlight, Moonlight, Rain, Snow, Blossom,
  Sandstorm, Ashfall, Bloodfall, CoX) with smooth crossfades, or your own panorama.
- World-space weather effects (Sunlight, Moonlight, Rain, Blossom, Fall, Snow,
  Ashfall, Bloodfall, Sandstorm) with adjustable intensity, plus Random weather.
- Sky lighting: world lighting, rim lighting, backlighting, tint and atmosphere.
- Region overrides (side panel): per-region sky, weather, lighting and water color.
- Texture resolution: 2K, 4K (default) or 8K.

**Water**
- Procedural animated water replacing OSRS water: waves, shore-following waves,
  surface depth, ripples and surface grain.
- Shore foam (swash style or a five-layer alternate foam) and surface bubbles.
- True transparency: objects below the surface stay visible and fade with depth.
- Reflects the active skybox, with a sun/moon glint, an optional camera glint and
  drifting reflection noise. Adjustable water color.

All settings are in the plugin's config panel; the water settings are in the
"Water" sections below the skybox sections.

## Requirements

- RuneLite with Adam's **GPU extension API** (tested on RuneLite 1.13.1 with the API
  through commit `6b5a9193f53ec90ff628c589483fbd0161badc35`). The API is not in an
  official RuneLite release yet, so this plugin cannot run on the stock client.
- The GPU plugin enabled.

## Building

Build the RuneLite checkout with the GPU API first:

```powershell
cd path\to\runelite-with-gpu-api
.\gradlew.bat :client:jar :client:shadowJar :runelite-api:jar
```

Then build and run this plugin:

```powershell
cd path\to\skybox-for-gpu
.\gradlew.bat build
.\gradlew.bat runClient
```

Options:
- `-PgpuApiProject=<path>`: location of the RuneLite + GPU API checkout
  (default `../adam-gpu-api-1.13.1`).
- `-PruneLiteVersion=<version>`: RuneLite version of that checkout (default `1.13.1`).

## Assets

The skybox presets and weather textures (~8.4 MB) are bundled in the plugin under
`src/main/resources/com/endrit/gpusky/assets/`. On first use any missing file is
copied into `.runelite/plugin-data/skybox-for-gpu`; existing files are never
overwritten. Custom panoramas can be placed there too, or imported from the region
override panel.

## License

BSD 2-Clause, see [LICENSE](LICENSE).
