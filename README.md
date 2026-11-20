# Skybox for GPU

A RuneLite plugin that adds panoramic skyboxes, sky lighting, world-space weather
and animated, reflective water.

## Review snapshot

This repository contains a work-in-progress source snapshot for code review and
review-bot testing. It is not a release or a Plugin Hub submission.

Production sources, shaders, bundled assets and basic Gradle build files are
included. The experimental RuneLite GPU API/client, tests and local development
launchers are intentionally excluded.

The current code targets RuneLite 1.13.6 with the experimental GPU extension API.
To compile, provide a separately built compatible checkout:

```sh
./gradlew build -PgpuApiProject=/path/to/runelite -PruneLiteVersion=1.13.6
```

The build expects the client and API JARs under that checkout's
`runelite-client/build/libs` and `runelite-api/build/libs` directories. An
unmodified stable RuneLite client does not provide the required experimental API.

Code and particle images use the BSD 2-Clause license. The skybox preset JPGs have
separate restrictions; see [LICENSE](LICENSE).
