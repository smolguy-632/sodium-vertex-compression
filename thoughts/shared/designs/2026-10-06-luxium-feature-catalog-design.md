---
date: 2026-10-06
topic: "Luxium Feature Catalog — Reimplementation Reference"
status: validated
---

# Luxium — Complete Feature Catalog

**Source:** `Luxium Let there be light-2.8.0-pre-alpha.jar_Decompiler.com/` (decompiled)
**Version:** `2.8.0-pre-alpha` · **Forge:** `47.1.3` · **Java:** 17 · **Renderer:** Embeddium `0.3.31–0.4`
**Purpose:** exhaustive reference for rebuilding this mod in another codebase. No code is written here.

---

## 1. Platform & Build Facts

| Item | Value |
|---|---|
| Mod ID | `luxium` |
| Config type | `ModConfig.Type.CLIENT` (registered via `Config.register()`) |
| Loader | Minecraft Forge, mandatory `[47.1.3,)` |
| Renderer dep | **Embeddium** `[0.3.31,0.4)`, client-side, mandatory |
| Sodium dep | **None declared** — Luxium mixes into legacy `me.jellysquid.mods.sodium.*` packages that ship inside Embeddium |
| Mixin compat | `JAVA_17`, `required: true`, `defaultRequire: 1` |
| Mixin set | 50 client mixins, 0 common mixins |

**Critical reimplementation constraint:** the entire lighting/chunk pipeline is layered on Embeddium's
internal renderer, not Sodium's public API. Porting to Sodium proper, or to vanilla `LevelRenderer`,
requires replacing 14 Embeddium mixins and both accessor packages. Budget accordingly.

---

## 2. Configuration Model

### 2.1 Master gating

Two functions form the global gate:

- `Config.isEnabled()` — reads `CLIENT.luxiumEnabled` (default `true`). Driven by the `K` keybind.
- `Config.isFeatureEnabled(BooleanValue feature)` — returns `true` only if the master switch is on **and**
  the feature is on **and** block-light test does not suppress it.

`blockLightTestEnabled` acts as a mutually-exclusive override: when on, it forcibly disables
`rtxEnabled`, `gpuShadowsEnabled`, `neoGpuVanillaEnabled`, and `neoCpuShadowsEnabled` at runtime
(the code remains and its config values are preserved).

### 2.2 Enums

| Enum | Values | Note |
|---|---|---|
| `SkyGodRaysVisualPreset` | `DEFAULT`, `CINEMATIC`, `CUSTOM` | `CUSTOM` is written whenever any god-ray slider moves |
| `SkyBakeTileSize` | `SIZE_512(512)`, `SIZE_256(256)`, `SIZE_128(128)` | tile px for smooth sky cubemap bake |
| `GpuLocalLightingMode` | `GPU_ONLY`, `HYBRID`, `NEOFLOOD_ONLY` | GPU/RTX division of labour |
| `GpuLocalShadowMode` | `FAST_SPREAD`, `CUBEMAP_HARD`, `CUBEMAP_SOFT` | `FAST_SPREAD` auto-migrates to `CUBEMAP_HARD` on screen open |
| `DebugHudPosition` | `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT` | |

`Config.MAX_RTX_WORLD_TRACING_WORKERS = max(1, min(24, availableProcessors))` — dynamic upper bound for `rtx.worldTracingWorkers`.

### 2.3 Legacy / dead config values

These exist in the config file but drive nothing. **Do not implement.**

| Field | Group | Why dead |
|---|---|---|
| `gpuSpreadOcclusionStrength` | `realisticShadows` | comment: "no longer used"; Fast Spread moved to NeoGpuVanilla |
| `gpuShadowsBlurEnabled` | `realisticShadows` | comment: "legacy compatibility key", superseded by `gpuLocalShadowMode` |
| `gpuFastSpreadMaxLights` | `realisticShadows` | name retained for compat, actually live as the light cap for Cubemap Hard / bake / PCSS |
| `alphaWarningShown` | `ui` | consumed only by `AlphaWarningScreen` |
| `luxiumEnabled` | root | not in the config screen; toggled only by keybind |

---

## 3. Complete Config Catalog

277 config values across 25 Forge groups. Range is Forge `defineInRange`.

### 3.1 root

| Field | Key | Type | Default |
|---|---|---|---|
| `luxiumEnabled` | `luxiumEnabled` | bool | `true` |

### 3.2 `rtx` — NeoFlood RTX

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `rtxEnabled` | `enabled` | bool | `false` | |
| `rtxLavaTracingEnabled` | `lavaTracingEnabled` | bool | `false` | |
| `entityShadowsEnabled` | `entityShadowsEnabled` | bool | `true` | |
| `rtxWorldTracingWorkers` | `worldTracingWorkers` | int | `1` | `1..MAX_RTX_WORLD_TRACING_WORKERS` |
| `floodRadiusCap` | `radiusCap` | int | `36` | `16..36` |
| `floodPenumbraSoftness` | `penumbraSoftness` | double | `0.59` | `0..1` |
| `floodCornerSeal` | `cornerSeal` | double | `1.35` | `0..4` |
| `floodDirectionalBias` | `directionalBias` | double | `0.25` | `0.25..4` |
| `floodUpdateBudget` | `updateBudget` | int | `8` | `1..64` |

Note: `entityShadowsEnabled` is declared inside the `rtx` group but is surfaced in the
**Realistic Shadows** UI category. Solver cost scales with the cube of `radiusCap`.

### 3.3 `blockLightTest`

| Field | Key | Type | Default |
|---|---|---|---|
| `blockLightTestEnabled` | `enabled` | bool | `false` |

### 3.4 `vanillaGpu` — NeoGpuVanilla

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `neoGpuVanillaEnabled` | `enabled` | bool | `false` | |
| `neoGpuVanillaCaptureResolution` | `pixelsPerBlock` | int | `32` | `1..512` |
| `neoGpuVanillaDistance` | `sourceDistance` | int | `41` | `16..64` |
| `neoGpuVanillaCaptureBudget` | `captureBudget` | int | `300` | `1..300` |
| `neoGpuVanillaMaxSources` | `maxSources` | int | `25` | `4..40` |
| `neoGpuVanillaCutoutEnabled` | `cutout` | bool | `false` | |
| `neoGpuVanillaFastShadowsEnabled` | `fastGpuShadows` | bool | `false` | |
| `neoGpuVanillaFastShadowStrength` | `fastGpuShadowsStrength` | double | `0.97` | `0..2` |
| `neoGpuVanillaFastDiffuseWrap` | `fastGpuShadowsDiffuseWrap` | double | `0.14` | `0..0.5` |
| `neoGpuVanillaGeometryUpdates` | `geometryUpdates` | bool | `false` | |
| `neoGpuVanillaDebug` | `debug` | bool | `false` | |

### 3.5 `realisticShadows` — local lighting & shadow engines

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `realisticShadowsEnabled` | `enabled` | bool | `false` | not in UI |
| `neoCpuShadowsEnabled` | `neoCpuShadowsEnabled` | bool | `false` | not in UI |
| `gpuShadowsEnabled` | `gpuShadowsEnabled` | bool | `false` | |
| `gpuLocalLightingMode` | `gpuLocalLightingMode` | enum | `GPU_ONLY` | |
| `gpuLocalLightDistance` | `gpuLocalLightDistance` | int | `42` | `4..64` |
| `gpuLocalShadowMode` | `gpuLocalShadowMode` | enum | `CUBEMAP_SOFT` | |
| `gpuHardShadowBakeEnabled` | `gpuHardShadowBakeEnabled` | bool | `false` | |
| `gpuHardShadowCaptureBudget` | `gpuHardShadowCaptureBudget` | int | `1` | `1..300` |
| `gpuSpreadOcclusionStrength` | `gpuSpreadOcclusionStrength` | double | `1.0` | `0..1` — **dead** |
| `gpuFastSpreadMaxLights` | `gpuFastSpreadMaxLights` | int | `32` | `4..32` |
| `gpuShadowsBlurEnabled` | `gpuShadowsBlurEnabled` | bool | `false` | **dead** |
| `entityShadowUpdateFpsLimit` | `entityUpdateFps` | int | `63` | `10..120` |
| `realisticShadowsRenderScale` | `renderScale` | double | `1.0` | `0.01..1.0` |

### 3.6 `skyShadows` — NeoSkyCelestia

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `skyLightEnabled` | `skyLightEnabled` | bool | `true` | |
| `skyLightColorsEnabled` | `skyLightColorsEnabled` | bool | `true` | |
| `skyShadowRayLength` | `rayLength` | int | `248` | `32..512` |
| `skyShadowNearResolution` | `nearResolution` | int | `2304` | `256..4096` |
| `skyShadowFarResolution` | `farResolution` | int | `1536` | `256..4096` |
| `skyShadowNearRadius` | `nearRadius` | int | `31` | `16..128` |
| `skyShadowNearUpdateIntervalSeconds` | `nearUpdateIntervalSeconds` | double | `1.2` | `0.01..5` |
| `skyShadowFarRadius` | `farRadius` | int | `248` | `64..384` |
| `skyShadowFarUpdateMs` | `farUpdateMs` | int | `1050` | `50..3000` |
| `skyShadowFilterSamples` | `filterSamples` | int | `1` | `1..4` (supported: 1, 4) |
| `skyShadowSoftShadowsEnabled` | `softShadowsEnabled` | bool | `true` | |
| `skyCloudShadowsEnabled` | `cloudShadowsEnabled` | bool | `false` | |
| `skyEntityShadowsEnabled` | `entityShadowsEnabled` | bool | `false` | |
| `skyEntityShadowResolution` | `entityShadowResolution` | int | `1536` | `512..2048` |
| `skyEntityShadowRadius` | `entityShadowRadius` | int | `16` | `16..128` |
| `skyEntityShadowUpdateFps` | `entityShadowUpdateFps` | int | `61` | `10..61` |
| `skyLightSunStrength` | `sunLightStrengthPercent` | double | `200.0` | `0..200` |
| `skyLightMoonStrength` | `moonLightStrengthPercent` | double | `200.0` | `0..200` |
| `skyLightSunZenithColor` | `sunZenithColor` | int `0xRRGGBB` | `16766136` | `0..16777215` |
| `skyLightSunZenithStrength` | `sunZenithStrengthPercent` | double | `59.5` | `0..200` |
| `skyLightSunsetColor` | `sunsetColor` | int `0xRRGGBB` | `16742963` | `0..16777215` |
| `skyLightSunsetStrength` | `sunsetStrengthPercent` | double | `92.0` | `0..200` |
| `skyLightMoonColor` | `moonColor` | int `0xRRGGBB` | `6717613` | `0..16777215` |
| `skyLightMoonBaseStrength` | `moonStrengthPercent` | double | `24.0` | `0..200` |
| `skyLightAmbientStrength` | `ambientStrengthPercent` | double | `30.0` | `0..200` |
| `realisticShadowTemperature` | `realisticShadowTemperature` | bool | `true` | |
| `shadowTemperatureStrength` | `shadowTemperatureStrength` | double | `1.0` | `0..1` |
| `shadowTemperatureBias` | `shadowTemperatureBias` | int | `0` | `-100..100` |
| `vanillaBlockLightInSunShadows` | `vanillaBlockLightInSunShadowsPercent` | double | `10.0` | `0..100` |

### 3.7 `sky` — cinematic physical sky

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `skyEnabled` | `enabled` | bool | `false` | |
| `skyBakeUpdateIntervalSeconds` | `bakeUpdateIntervalSeconds` | double | `0.8` | `0.1..2` |
| `skyBakeSmoothEnabled` | `smoothBake` | bool | `true` | |
| `skyBakeWorkPerFrame` | `workPerFrame` | int | `1` | `1..6` |
| `skyBakeTileSize` | `tileSize` | enum | `SIZE_256` | |
| `skySunSize` | `sunSize` | double | `0.47` | `0.1..3.0` |
| `skyMoonSize` | `moonSize` | double | `0.54` | `0.1..3.0` |

### 3.8 `clouds` — three independent vanilla-style layers

Master: `cloudsEnabled` → `enabled`, bool, `false`. Requires vanilla Clouds = Fast or Fancy.
Each layer exposes the same 14 keys under `layer1` / `layer2` / `layer3`.

| Param | Key | Type | L1 | L2 | L3 | Range |
|---|---|---|---|---|---|---|
| enabled | `enabled` | bool | `true` | `true` | `true` | |
| height | `height` | double | `150` | `200` | `270` | `64..384` |
| speed | `speed` | double | `1.25` | `1.0` | `0.55` | `0..3` |
| direction | `direction` | double | `0` | `10` | `350` | `0..360` (0=+X, 90=+Z) |
| scale | `scale` | double | `0.8` | `1.0` | `1.7` | `0.25..4` |
| opacity | `opacity` | double | `65` | `80` | `50` | `0..100` |
| thickness | `thickness` | double | `4.0` | `4.0` | `3.0` | `0.5..16` |
| brightness | `brightness` | double | `0.95` | `1.0` | `0.95` | `0.25..1.5` |
| coverage | `coverage` | double | `42` | `50` | `38` | `0..100` |
| edgeSoftness | `edgeSoftness` | double | `0.0` | `0.0` | `0.0` | `0..4` |
| variation | `variation` | double | `10` | `8` | `15` | `0..100` |
| weatherInfluence | `weatherInfluence` | double | `40` | `70` | `100` | `0..100` |
| stormDarkening | `stormDarkening` | double | `25` | `35` | `45` | `0..100` |
| renderDistance | `renderDistance` | int | `384` | `448` | `512` | `128..1024` |

### 3.9 `reflections` — planar reflections

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `reflectionEnabled` | `enabled` | bool | `false` | |
| `reflectionBilinearFiltering` | `bilinearFiltering` | bool | `true` | |
| `reflectionRenderScale` | `renderScale` | double | `0.78` | `0.1..1.0` |
| `reflectionDistance` | `distance` | int | `24` | `5..200` |
| `reflectionScanIntervalMs` | `scanIntervalMs` | int | `800` | `5..5000` |

Supported blocks: iron block, quartz block, polished andesite only.

### 3.10 `postEffects` — shared quality

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `postEffectsRenderScale` | `renderScale` | double | `0.44` | `0.1..1.0` |

Shared by fog, Sky God Rays, Volumetric God Rays, lens flare. Tonemap is always full-res.

### 3.11 `kawaseBloom` — block-light-only dual Kawase bloom

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `kawaseBloomEnabled` | `enabled` | bool | `false` | |
| `kawaseBloomIntensity` | `intensity` | double | `0.55` | `0..3` |
| `kawaseBloomRadius` | `radius` | double | `2.45` | `0.5..4` |
| `kawaseBloomLevels` | `levels` | int | `7` | `2..7` |
| `kawaseBloomThreshold` | `sourceThreshold` | double | `0.05` | `0..1` |
| `kawaseBloomSourceDistance` | `sourceDistance` | int | `24` | `8..32` |
| `kawaseBloomDepthOcclusion` | `depthOcclusion` | bool | `false` | |
| `kawaseBloomDepthTolerance` | `depthTolerance` | double | `0.35` | `0.01..2` |

### 3.12 `skyGodRays` — screen-space sun/moon rays

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `skyGodRaysEnabled` | `skyGodRaysEnabled` | bool | `true` | |
| `skyGodRaysSunSize` | `sunSize` | double | `0.94` | `0.5..2.0` |
| `skyGodRaysMoonSize` | `moonSize` | double | `0.65` | `0.5..2.0` |
| `skyGodRaysCelestialDiscEnabled` | `celestialDiscEnabled` | bool | `true` | |
| `skyGodRaysHaloEnabled` | `haloEnabled` | bool | `true` | |
| `skyGodRaysVisualPreset` | `visualPreset` | enum | `CUSTOM` | |
| `skyGodRaysRayIntensity` | `rayIntensity` | double | `1.63` | `0..2.5` |
| `skyGodRaysHaloIntensity` | `haloIntensity` | double | `1.63` | `0..2.5` |
| `skyGodRaysDiscIntensity` | `discIntensity` | double | `0.97` | `0..2.0` |
| `skyGodRaysHaloSize` | `haloSize` | double | `1.25` | `0.5..2.5` |
| `skyGodRaysDiscSize` | `discSize` | double | `1.18` | `0.5..1.8` |
| `skyGodRaysSunDayColor` | `sunDayColor` | int `0xRRGGBB` | `16772295` | `0..16777215` |
| `skyGodRaysSunsetColor` | `sunsetColor` | int `0xRRGGBB` | `16747584` | `0..16777215` |
| `skyGodRaysMoonBaseColor` | `moonBaseColor` | int `0xRRGGBB` | `12108497` | `0..16777215` |
| `skyGodRaysMoonHaloColor` | `moonHaloColor` | int `0xRRGGBB` | `10400736` | `0..16777215` |
| `skyGodRaysWeatherInfluence` | `weatherInfluence` | double | `1.19` | `0..2` |
| `skyGodRaysCenterSuppression` | `centerSuppression` | double | `1.5` | `0..2` |

Preset values written by `applyPreset` (7 sliders, in order ray / haloIntensity / discIntensity / haloSize / discSize / weather / center):

| Preset | Values |
|---|---|
| `DEFAULT` | `1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0` |
| `CINEMATIC` | `1.65, 1.85, 1.15, 1.45, 1.1, 1.35, 1.45` |
| `CUSTOM` | not written; forced by any manual slider edit |

### 3.13 `volumetricRays` — world-space raymarched shafts

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `skyVolumetricGodRaysEnabled` | `skyVolumetricGodRaysEnabled` | bool | `true` | |
| `skyVolumetricGodRaysIntensity` | `intensity` | double | `0.97` | `0..3` |
| `skyVolumetricGodRaysDensity` | `density` | double | `0.76` | `0..3` |
| `skyVolumetricGodRaysSamples` | `raymarchSamples` | int | `13` | `8..32` |
| `skyVolumetricGodRaysUniformity` | `rayUniformityPercent` | double | `0.0` | `0..100` |
| `skyVolumetricGodRaysSideVisibility` | `sideVisibilityPercent` | double | `0.0` | `0..100` |
| `skyVolumetricGodRaysHazeSuppression` | `hazeSuppressionPercent` | double | `41.0` | `0..100` |
| `skyVolumetricGodRaysEntityOcclusion` | `entityOcclusion` | bool | `false` | |
| `skyVolumetricGodRaysMaxDistance` | `maxDistance` | double | `144.0` | `16..384` |
| `skyVolumetricGodRaysAnisotropy` | `anisotropy` | double | `0.53` | `0..0.95` |
| `skyVolumetricGodRaysCelestialColorInfluence` | `celestialColorInfluencePercent` | double | `100.0` | `0..100` |
| `skyVolumetricGodRaysAutoAdaptation` | `autoAdaptation` | bool | `true` | |
| `skyVolumetricGodRaysFullAdaptationSkylight` | `fullAdaptationSkylight` | int | `0` | `0..15` (UI slider reversed: 15→0) |

Requires `skyLightEnabled`. Raymarches through the existing GPU sky-shadow cascades; skylight is sampled
every 2 game ticks (~10 Hz) with ~0.5 s smoothing.

### 3.14 `camera` — lens flare

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `lensFlareEnabled` | `lensFlareEnabled` | bool | `true` | |
| `lensFlareIntensity` | `flareIntensity` | double | `1.87` | `0..3` |
| `lensFlareStreakIntensity` | `streakIntensity` | double | `1.0` | `0..3` |
| `lensFlareStreakLength` | `streakLength` | double | `1.0` | `0.25..3` |
| `lensFlareStreakWidth` | `streakWidth` | double | `1.0` | `0.25..3` |
| `lensFlareChromaticSpread` | `chromaticSpread` | double | `1.0` | `0..3` |
| `lensFlareGhostIntensity` | `ghostIntensity` | double | `1.0` | `0..3` |
| `lensFlareGhostSize` | `ghostSize` | double | `1.0` | `0.35..3` |
| `lensFlareSpread` | `flareSpread` | double | `1.0` | `0.35..3` |

Independent of Sky God Rays. Anamorphic horizontal streak + circular ghosts + RGB split.

### 3.15 `tonemap` — filmic

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `tonemapEnabled` | `enabled` | bool | `true` | |
| `tonemapExposure` | `exposure` | double | `1.14` | `-2..2` (EV) |
| `tonemapContrast` | `contrast` | double | `0.18` | `0..1` |
| `tonemapHighlightCompression` | `highlightCompression` | double | `0.65` | `0..1` |
| `tonemapShadowDepth` | `shadowDepth` | double | `0.1` | `0..1` |
| `tonemapSaturation` | `saturation` | double | `1.0` | `0..2` |
| `tonemapVibrance` | `vibrance` | double | `0.18` | `0..1` |
| `tonemapGamma` | `gamma` | double | `2.2` | `1.6..2.8` |
| `tonemapStrength` | `strength` | double | `1.0` | `0..1` |

Runs inside the Sky God Rays composite pass at full resolution.

### 3.16 `fog` — Mie atmospheric fog

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `fogEnabled` | `fogEnabled` | bool | `true` | |
| `fogCelestialScatteringEnabled` | `fogCelestialScatteringEnabled` | bool | `false` | |
| `fogDensity` | `density` | double | `7.12` | `0..20` |
| `fogScatteringBrightness` | `scatteringBrightness` | double | `1.2` | `0.25..1.2` |
| `fogMaxBrightness` | `maxBrightness` | double | `1.16` | `0.15..1.2` |
| `fogNearDensityBoost` | `nearDensityBoost` | double | `0.0` | `0..5` |
| `fogNearBoostRange` | `nearBoostRange` | double | `55.0` | `4..128` |
| `fogDynamicCelestialColor` | `dynamicCelestialColor` | bool | `true` | |
| `fogCelestialColorBlend` | `celestialColorBlend` | double | `0.63` | `0..1` |
| `fogStartDistance` | `startDistance` | double | `8.0` | `0..160` |
| `fogNearFade` | `nearFade` | double | `42.6` | `0..64` |
| `fogMaxOpacity` | `maxOpacity` | double | `0.64` | `0..1` |
| `fogSkyTint` | `skyTint` | double | `0.78` | `0..1` |
| `fogScatteringStrength` | `scatteringStrength` | double | `2.06` | `0..5` |
| `fogDistanceCurve` | `distanceCurve` | double | `2.54` | `0.35..3` |
| `fogHeight` | `height` | double | `81.0` | `50..384` |

Scattering works with `fogEnabled` off. Fog tint is driven by the shared `NeoSkyCelestiaLighting` state.

### 3.17 `water` — procedural realistic water

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `waterEnabled` | `enabled` | bool | `true` | |
| `waterDepthAwareRefraction` | `depthAwareRefraction` | bool | `true` | |
| `waterDetailWaves` | `detailWaves` | bool | `true` | |
| `waterRenderScale` | `renderScale` | double | `0.45` | `0.25..1.0` |
| `waterWaveStrength` | `waveStrength` | double | `0.44` | `0..5` |
| `waterWaveScale` | `waveScale` | double | `6.49` | `0.25..10` |
| `waterWaveSpeed` | `waveSpeed` | double | `4.07` | `0..6` |
| `waterDetailStrength` | `detailStrength` | double | `0.58` | `0..1` |
| `waterRefractionStrength` | `refractionStrength` | double | `3.27` | `0..4` |
| `waterAbsorptionStrength` | `absorptionStrength` | double | `2.34` | `0..3` |
| `waterVisibilityDepth` | `visibilityDepth` | double | `2.0` | `2..48` |
| `waterFresnelStrength` | `fresnelStrength` | double | `1.5` | `0..1.5` |
| `waterFresnelPower` | `fresnelPower` | double | `3.9` | `1..10` |
| `waterSurfaceOpacity` | `surfaceOpacity` | double | `0.07` | `0..0.6` |
| `waterSpecularStrength` | `specularStrength` | double | `2.0` | `0..2` |
| `waterSpecularSharpness` | `specularSharpness` | double | `281.0` | `16..512` |
| `waterSsrEnabled` | `ssrEnabled` | bool | `true` | |
| `waterSsrStrength` | `ssrStrength` | double | `1.03` | `0..2` |
| `waterSsrMaxDistance` | `ssrMaxDistance` | double | `45.0` | `4..128` |
| `waterSsrThickness` | `ssrThickness` | double | `2.0` | `0.02..2` |
| `waterSsrEdgeFade` | `ssrEdgeFade` | double | `0.01` | `0.01..0.4` |

Geometry stays perfectly flat — all motion is a packed normal field. `renderScale` below 1.0 only
rasterizes the WATER material pass at reduced resolution; native geometry resolves it in one filtered lookup.

### 3.18 `ssr` — shared quality

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `ssrQuality` | `quality` | double | `0.4` | `0.25..1.0` |

Shared by water, puddles, wet surfaces. Scales ray steps and hit refinement.

### 3.19 `rainPuddles` — command-created puddles

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `puddleMaxDepth` | `maxDepth` | double | `0.195` | `0.005..0.2` |
| `puddleDepthCurve` | `depthCurve` | double | `2.95` | `0.35..3` |
| `puddleWetDarkening` | `wetDarkening` | double | `0.52` | `0..0.6` |
| `puddleSurfaceOpacity` | `surfaceOpacity` | double | `1.0` | `0.1..1.0` |
| `puddleEdgeSoftness` | `edgeSoftness` | double | `0.51` | `0..1` |
| `puddleWaveStrength` | `waveStrength` | double | `0.05` | `0..2` |
| `puddleWaveScale` | `waveScale` | double | `9.15` | `0.25..10` |
| `puddleWaveSpeed` | `waveSpeed` | double | `1.64` | `0..4` |
| `puddleRippleStrength` | `rippleStrength` | double | `0.46` | `0..2` |
| `puddleRefractionStrength` | `refractionStrength` | double | `2.63` | `0..3` |
| `puddleSsrEnabled` | `ssrEnabled` | bool | `false` | |
| `puddleSsrStrength` | `ssrStrength` | double | `1.03` | `0..2` |
| `puddleSsrMaxDistance` | `ssrMaxDistance` | double | `45.0` | `4..96` |
| `puddleSsrThickness` | `ssrThickness` | double | `2.0` | `0.02..2` |
| `puddleSsrEdgeFade` | `ssrEdgeFade` | double | `0.19` | `0.01..0.4` |

### 3.20 `wet` — global wet-film material

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `wetEnabled` | `enabled` | bool | `false` | |
| `wetMaxDepth` | `maxDepth` | double | `0.2` | `0.005..0.2` |
| `wetDepthCurve` | `depthCurve` | double | `3.0` | `0.35..3` |
| `wetDarkening` | `wetDarkening` | double | `0.6` | `0..0.6` |
| `wetSurfaceOpacity` | `surfaceOpacity` | double | `1.0` | `0.1..1.0` |
| `wetEdgeSoftness` | `edgeSoftness` | double | `0.62` | `0..1` |
| `wetWaveStrength` | `waveStrength` | double | `0.05` | `0..2` |
| `wetWaveScale` | `waveScale` | double | `9.15` | `0.25..10` |
| `wetWaveSpeed` | `waveSpeed` | double | `1.64` | `0..4` |
| `wetRippleStrength` | `rippleStrength` | double | `0.46` | `0..2` |
| `wetRefractionStrength` | `refractionStrength` | double | `2.71` | `0..3` |
| `wetEnvironmentReflectionStrength` | `environmentReflectionStrength` | double | `1.03` | `0..2` |
| `wetCelestialSpecularStrength` | `celestialSpecularStrength` | double | `2.0` | `0..2` |
| `wetSheenFloor` | `sheenFloor` | double | `0.145` | `0..0.25` |
| `wetRippleHighlightStrength` | `rippleHighlightStrength` | double | `2.0` | `0..2` |
| `wetSsrEnabled` | `ssrEnabled` | bool | `true` | |
| `wetSsrStrength` | `ssrStrength` | double | `2.0` | `0..2` |
| `wetSsrMaxDistance` | `ssrMaxDistance` | double | `4.0` | `4..96` |
| `wetSsrThickness` | `ssrThickness` | double | `2.0` | `0.02..2` |
| `wetSsrEdgeFade` | `ssrEdgeFade` | double | `0.01` | `0.01..0.4` |

Screen-space material pass on upward-facing SOLID terrain — no CPU block scan.

### 3.21 `lsr` — Luxium Spatial Resolution

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `lsrEnabled` | `enabled` | bool | `false` | |
| `lsrRenderScale` | `renderScale` | double | `0.71` | `0.5..0.85` |
| `lsrSharpness` | `sharpness` | double | `1.0` | `0..1` |

Renders world at reduced internal resolution, reconstructs before the live first-person hand and GUI.

### 3.22 `tfr` — Temporal Frame Reprojection

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `tfrEnabled` | `enabled` | bool | `false` | |
| `tfrReprojectionStrength` | `reprojectionStrength` | double | `1.0` | `0..1` |
| `tfrHistoryStability` | `historyStability` | double | `0.06` | `0..0.35` |
| `tfrCameraCutDistance` | `cameraCutDistance` | double | `9.25` | `0.25..16` |
| `tfrCameraCutAngle` | `cameraCutAngle` | double | `14.0` | `5..90` |

Fixed REAL/SYNTH cadence: one full world frame, then one reconstructed frame. HUD, GUI, and hand stay live.

### 3.23 `grass` — plant vertex wind

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `plantsWaveEnabled` | `enabled` | bool | `false` | |
| `plantsWaveStrength` | `strength` | double | `1.0` | `0..2` |
| `plantsWaveSpeed` | `speed` | double | `1.0` | `0..3` |
| `plantsWaveGustStrength` | `gustStrength` | double | `0.65` | `0..2` |
| `plantsWaveDistance` | `distance` | int | `64` | `16..192` |
| `plantsWaveGrassStrength` | `grassStrength` | double | `1.0` | `0..2` |
| `plantsWaveLeavesStrength` | `leavesStrength` | double | `0.55` | `0..2` |
| `plantsWaveAquaticStrength` | `aquaticStrength` | double | `0.65` | `0..2` |
| `plantsWaveBendStrength` | `bendStrength` | double | `1.0` | `0..2` |

### 3.24 `opaqueIce`

| Field | Key | Type | Default |
|---|---|---|---|
| `opaqueIceEnabled` | `enabled` | bool | `true` |

Moves only `minecraft:ice` from TRANSLUCENT to SOLID. Does not affect frosted/packed/blue ice, water, or glass.

### 3.25 `debugHud`

| Field | Key | Type | Default | Range |
|---|---|---|---|---|
| `debugHudEnabled` | `enabled` | bool | `false` | |
| `debugHudFps` | `fps` | bool | `true` | |
| `debugHudMemory` | `memory` | bool | `true` | |
| `debugHudAllocated` | `allocated` | bool | `false` | |
| `debugHudPosition` | `position` | enum | `TOP_RIGHT` | |
| `debugHudOffsetX` | `offsetX` | int | `4` | `0..200` |
| `debugHudOffsetY` | `offsetY` | int | `4` | `0..200` |
| `debugHudTextScale` | `textScale` | double | `1.0` | `0.1..5.0` |
| `debugHudUpdateSeconds` | `updateSeconds` | double | `0.5` | `0.1..2.0` |

### 3.26 `ui`

| Field | Key | Type | Default |
|---|---|---|---|
| `alphaWarningShown` | `alphaWarningShown` | bool | `false` |

---

## 4. UI Architecture

### 4.1 Entry points

- `LuxiumConfigScreen(Screen parent)` — full custom screen, 816 lines, immediate-mode via `GuiGraphics`.
- `VideoSettingsHubScreen` — integrates Luxium into the vanilla video settings screen.
- `AlphaWarningScreen` — first-run alpha disclaimer, gated by `alphaWarningShown`.
- `GlassPanelRenderer` (112 lines) + `LuxiumMenuBackgroundRenderer` — backdrop blur/dim.

### 4.2 Layout

| Constant | Value |
|---|---|
| `ROW_HEIGHT` | `32` |
| `HEADER_HEIGHT` | `38` |
| `SIDEBAR_FOOTER_HEIGHT` | `72` |
| `BACKGROUND_TRANSITION_NANOS` | `400000000` (0.4 s) |

Palette: `PANEL`, `HOVER`, `BORDER`, `ACCENT`, `TEXT`, `MUTED`. Scroll is smoothed independently for content
(`scroll` → `targetScroll`) and sidebar (`sidebarScroll` → `sidebarTargetScroll`). Blur and dimming each animate
toward their target on the background transition timer. A `compactLayout` mode drops the preview to a tooltip
(`renderCompactPreviewTooltip`). Preview side-by-side/after is user-switchable (`drawPreviewModeButton`).

### 4.3 Data model

`ConfigScreenModel` builds `Folder` → `Category` → `Row` (`Row` is either a header or an option).

```
record Folder(String id, Component title, Component description, List<Category> categories)
record Category(String id, Component title, Component description, List<Row> rows)
record Row(Component header, ConfigOption<?> option)   // isHeader() = header != null
```

`ConfigScreenModel.createFolders()` partitions 24 categories into two folders using a hardcoded id set:

- **`main`** (blank title): everything not listed below.
- **`not_ready_to_play_dev`** (folder.luxium.not_ready_to_play_dev.title): `general`, `reflection`,
  `vanilla_gpu`, `realistic_shadows`, `sky`, `clouds`, `ssr`, `rain_puddles`, `wet`, `grass`,
  `kawase_bloom`, `testing`, `block_light_test`.

`reset(category)` / `resetAll(categories)` reset options to `ConfigValue.getDefault()`.

`ConfigOption<T>` factories: `bool`, `integer`, `decimal`, `enumeration`, `color`.
`Type` enum: `BOOLEAN, INTEGER, DOUBLE, ENUM, COLOR`. Decorators: `onChanged(Consumer<T>)`,
`enabledWhen(BooleanSupplier)`, `reversedSlider()`. A `revision` counter increments per `set()` and drives
preview animation. `formatDecimal` picks `%.0f` / `%.1f` / `%.2f` from the step.

`ConfigWidgets` widgets: `Toggle`, `Slider<T extends Number>` (with `normalize` / `configNormalized` /
`setValueFromCursor` / `applyNormalizedValue`), `EnumCycle<E>`, `ActionButton`, plus a static
`colorEditor(int x, int y, int width, ConfigOption<Integer>)` — a seven-character `#RRGGBB` edit box.

### 4.4 Category order and folder membership

| # | id | Title | Folder |
|---|---|---|---|
| 1 | `general` | NeoVanillaLighting | dev |
| 2 | `block_light_test` | BlockLightTest | dev |
| 3 | `reflection` | Reflections | dev |
| 4 | `vanilla_gpu` | Vanilla GPU | dev |
| 5 | `realistic_shadows` | Realistic Shadows | dev |
| 6 | `neoskycelestia` | NeoSkyCelestia | main |
| 7 | `sky` | Sky | dev |
| 8 | `clouds` | Clouds | dev |
| 9 | `lsr` | LSR | main |
| 10 | `tfr` | TFR | main |
| 11 | `tonemap` | Tonemap | main |
| 12 | `posteffects` | PostEffects Quality | main |
| 13 | `kawase_bloom` | Kawase Bloom | dev |
| 14 | `godrays` | God Rays | main |
| 15 | `volumetric_rays` | Volumetric Rays | main |
| 16 | `fog` | Fog | main |
| 17 | `camera` | Camera | main |
| 18 | `water` | Water READ DC | main |
| 19 | `ssr` | SSR | dev |
| 20 | `rain_puddles` | Rain Puddles | dev |
| 21 | `wet` | Wet | dev |
| 22 | `grass` | Grass | dev |
| 23 | `opaque_ice` | Opaque Ice | main |
| 24 | `debug` | Debug | main |

### 4.5 In-screen headers

`header.luxium.flood_rt`, `header.luxium.celestial_light`, `header.luxium.celestial_occlusion`,
`header.luxium.celestial_entity_shadows`, `header.luxium.clouds_general`, `header.luxium.cloud_layer_1/2/3`,
`header.luxium.water_surface`, `header.luxium.water_ssr`, `header.luxium.rain_puddles_surface`,
`header.luxium.rain_puddles_motion`, `header.luxium.rain_puddles_ssr`, `header.luxium.wet_surface`,
`header.luxium.wet_motion`, `header.luxium.wet_ssr`, `header.luxium.wet_lighting`.

### 4.6 Side-effect callbacks on change

| Trigger | Action |
|---|---|
| any sky option | `Sky.onConfigChanged()` |
| LSR options | `LsrSystem.onConfigChanged()` |
| TFR options | `TemporalFrameSystem.onConfigChanged()` |
| fog options | `fog.onConfigChanged()` |
| `neoGpuVanillaEnabled` | `NeoGpuVanilla.onConfigChanged()` |
| `neoGpuVanillaCaptureResolution` | `NeoGpuVanilla.requestRebuild()` |
| `neoGpuVanillaDistance`, `MaxSources`, `FastShadows*` | `NeoGpuVanilla.requestRebake()` |
| `gpuHardShadowBakeEnabled` | forces `gpuLocalShadowMode=CUBEMAP_HARD` **and** `entityShadowsEnabled=false`, then `GpuNeoShadows.onConfigChanged()` |
| any god-ray slider | forces `skyGodRaysVisualPreset=CUSTOM` |

### 4.7 Conditional enablement (dependency rules)

Load-bearing rules to reproduce verbatim:

- All `rtx.*` and most `realisticShadows.*` options: disabled when `neoGpuVanillaEnabled`.
- `gpuLightingMode` / `gpuShadowMode` / `gpuHardShadowBakeEnabled` / `gpuLocalLightDistance` /
  `gpuFastSpreadMaxLights`: additionally require `gpuShadowsEnabled && !neoCpuShadowsEnabled`.
- `gpuShadowMode`, `gpuHardShadowCaptureBudget`: additionally require `gpuLocalLightingMode != NEOFLOOD_ONLY && !gpuHardShadowBakeEnabled`.
- `entityShadowsEnabled`: disabled when `gpuHardShadowBakeEnabled`.
- `entityShadowUpdateFps`: requires `entityShadowsEnabled` **and** one of realistic / NeoCPU / GPU shadows.
- `realistic_shadows_render_scale`: `neoCpuShadowsEnabled || (realisticShadowsEnabled && !gpuShadowsEnabled)`.
- `sky_light_colors_enabled` and all celestial color/strength rows: require `skyLightEnabled`.
- `volumetric_rays` rows: require `skyVolumetricGodRaysEnabled`.
- `sky_volumetric_god_rays_full_adaptation_skylight`: also requires `autoAdaptation`; slider reversed.
- `fog_sky_tint`: requires `fogEnabled && !dynamicCelestialColor`.
- `fog_celestial_color_blend`: requires `fogEnabled && dynamicCelestialColor`.
- `fog_scattering_strength`: requires `fogEnabled || fogCelestialScatteringEnabled`.
- cloud layer rows: require `cloudsEnabled` and the layer's own `enabled`.
- `water_ssr_*` rows: require `waterEnabled && waterSsrEnabled`.
- `wet_ssr_*` rows: require `wetEnabled && wetSsrEnabled`.
- `water_detail_strength`: requires `waterEnabled && waterDetailWaves`.
- `kawase_bloom_depth_tolerance`: requires `kawaseBloomEnabled && depthOcclusion`.
- `plants_wave_*` rows: require `plantsWaveEnabled`.

### 4.8 Preview images

`ConfigPreviewImages.hasPreview(id)` / `.image(id, after)` — 14 off/on pairs:

| Option id | off / on |
|---|---|
| `lens_flare_enabled` | `cameralensoff/on` |
| `entity_shadows_enabled` | `enitirealisticshadowsoff/on` |
| `sky_volumetric_god_rays_entity_occlusion` | `entityperekrivaeoff/on` |
| `sky_entity_shadows_enabled` | `entitytinioff/tinion` |
| `fog_enabled` | `fogoff/fogon` |
| `sky_cloud_shadows_enabled` | `hmaritinioff/hmaritinion` |
| `gpu_shadows_enabled` | `realisticshadowsoff/on` |
| `sky_godrays_enabled` | `skygodraysoff/on` |
| `sky_light_colors_enabled` | `svitloskyoff/on` |
| `sky_light_enabled` | `tiniskyoff/tiniskyon` |
| `tonemap_enabled` | `tonemapoff/on` |
| `sky_volumetric_god_rays_enabled` | `volumetricskyoff/on` |
| `water_enabled` | `wateroff/on` |
| `water_ssr_enabled` | `waterssroff/waterssron` |

Filenames are misspelled in the source (e.g. `enitirealistic…`, `tiniskyon`, `hmaritini…`). Preserve or
correct them consistently in the port. `syncPreviewWithConfig` auto-picks the `after` variant when a boolean
option is on; `previewAfter` lets the user override.

---

## 5. Feature Areas

Complexity: **L** low · **M** medium · **H** high.

### 5.1 NeoFlood RTX — H
Replaces vanilla block-light propagation with a background-solved visibility volume. Exact worker-thread count,
radius cap with cubic cost, sub-voxel penumbra back-projection, boundary corner sealing, and per-batch source
budgets. Also carries a budgeted isolated lava `BlockLightEngine` for lava outside the Nether.
**Deps:** `rtx` config group, `LightEngine` mixins, light storage accessors.
**Keys:** `rtx.*`

### 5.2 NeoGpuVanilla — H
Keeps vanilla propagation but bakes a camera-centred RGBA8 receiver volume plus one-time point-light depth
cubemaps into a shared 4096² atlas; runtime does one R8 lookup per pixel. Optional CUTOUT prepass, optional
baked Fast GPU Shadows (six cached 3D lookups regardless of source count), geometry-change invalidation, and
GL timer-query diagnostics.
**Deps:** `vanillaGpu` group, `r1`/`r2` accessor mixins, `ShaderChunkRenderer`, `EmbeddiumTerrainPasses`.
**Keys:** `vanillaGpu.*`

### 5.3 GPU Local Lighting (`GpuNeoShadows`) — H
Direct point lights inside terrain and entity shaders. Three shadow modes (Fast Spread legacy-migrated,
Cubemap Hard, Cubemap Soft/PCSS), optional bake into six directional RGB receiver textures with fixed 1–3
lookups, light-grid texture upload, baked hard-shadow volume, per-light metadata encoding in the quad views.
**Deps:** `realisticShadows` group, `GpuShadowFrameState`, material bit-packing, `EmbeddiumBlockRenderer`.
**Keys:** `realisticShadows.*`, `rtx.entityShadowsEnabled`

### 5.4 NeoCPU Shadows — H
Source-local CPU shadow engine using cached chunk-section `VoxelShape` geometry; takes over entity/block-entity
shadows from the GPU path when enabled. `SOURCE_SEARCH_RADIUS=66`, `MAX_SOURCES=256`.
**Deps:** `realisticShadows.neoCpuShadowsEnabled`, `LightTexture` extension.
**Keys:** `realisticShadows.neoCpuShadowsEnabled`, `realisticShadows.renderScale`

### 5.5 Realistic Shadows — M
Occluder faces projected onto visible receiver faces, cutting RTX block light. Shadowless scene capture at
`renderScale`. Shadow temperature model (opposite-hue shift with strength and bias).
**Deps:** `realisticShadows.enabled`, `ExposedFaceService`.
**Keys:** `realisticShadows.enabled`, `realisticShadows.renderScale`, `skyShadows.realisticShadowTemperature*`

### 5.6 BlockLightTest — M
Independent experiment: vanilla propagation plus a lightweight colored block-light renderer, 12-slot near
emitter array, 2 section captures/tick, 2048 dirty blocks/tick, 64 async commits/tick. Mutually exclusive with
RTX, GPU shadows, NeoGpuVanilla, and NeoCPU.
**Deps:** `BlockLightTest` package.
**Keys:** `blockLightTest.enabled`

### 5.7 NeoSkyCelestia — H
Directional sun/moon shadows via near+far cascades plus a separate frequently-updated entity shadow map.
Own depth framebuffers with hardware PCF comparison sampling, a 32×112 float celestial light LUT, per-frame
cached lighting state, terrain normal material variants, and an Embeddium render-list bridge.
Cascade radii/resolutions/refresh intervals are all user-tunable. Sun/moon/sunset/ambient colors and strengths
feed blocks and entities; colors can be disabled for neutral white.
**Deps:** `skyShadows` group, `CelestialPath`, `NeoSkyCelestiaFrameState`, `LightTexture` extension.
**Keys:** `skyShadows.*`

### 5.8 Sky Volumetric God Rays — H
World-space shafts raymarched through the existing GPU sky-shadow cascades. Sparse sampling with jitter,
forward-scattering anisotropy, artistic side-visibility and uniformity lifts, and a haze-suppression term that
removes the resulting blanket tint without killing intensity. Auto-adaptation raises uniformity and side
visibility as skylight falls (2-tick sampling, ~0.5 s smoothing).
**Deps:** `skyLightEnabled` mandatory, `volumetricRays` group, sky-shadow cascades.
**Keys:** `volumetricRays.*`

### 5.9 Sky God Rays — M
Screen-space sun/moon shafts with separate halo and disc layers, four hex colors, weather influence, and center
suppression. Preset system over seven sliders.
**Deps:** `skyGodRays` group, `CelestialScreenPos`, `Sky.onConfigChanged`.
**Keys:** `skyGodRays.*`

### 5.10 Lens Flare — M
Anamorphic horizontal streak + circular ghosts + RGB chromatic split, independent of god rays.
**Deps:** `camera` group, shader configured with width/height/depth texture.
**Keys:** `camera.*`

### 5.11 Filmic Tonemap — L
Exposure, contrast, highlight compression, shadow toe, saturation, vibrance, gamma, blend strength — folded
into the Sky God Rays composite pass at full resolution.
**Deps:** `tonemap` group, `ShaderManager.getTonemapShader()`.
**Keys:** `tonemap.*`

### 5.12 Mie Fog — M
Height-bounded atmospheric fog with squared extinction, capped luminance to avoid a white wall, near-density
boost, distance curve, and celestial-colored tint shared with NeoSkyCelestia. Sun/moon scattering works
independently of `fogEnabled`.
**Deps:** `fog` group, `FOG_HEIGHT` constant, `fog.onConfigChanged()`.
**Keys:** `fog.*`

### 5.13 Kawase Bloom — M
Dual-Kawase pyramid bloom applied only to blocks whose current state emits vanilla block light, from a
camera-centred source registry with chunk-indexed snapshots and optional depth occlusion.
**Deps:** `kawaseBloom` group, `KawaseSourceRegistry`, `SharedPostResources`.
**Keys:** `kawaseBloom.*`

### 5.14 Planar Reflections — M
Player-centred mirrored plane rendering for up to 4 planes, only for iron/quartz/polished andesite, with
CPU mirror-scan at a configurable interval.
**Deps:** `reflections` group, `ReflectionMaterialRegistry`.
**Keys:** `reflections.*`

### 5.15 Three-Layer Vanilla Clouds — M
Replaces the vanilla cloud draw with three independently configured vanilla-style layers reusing the vanilla
`clouds.png` mask (256², UV scale 1/256). Per-layer height, wind, scale, opacity, thickness, brightness,
coverage, edge softness, variation, weather influence, storm darkening, render radius.
**Deps:** `clouds` group, `LevelRendererMixin`, requires vanilla Clouds ≠ Clouds-off.
**Keys:** `clouds.*`

### 5.16 Cinematic Physical Sky — M
Physically rendered sky into a 1024²×6 cubemap cache with configurable smooth tiled baking. Controls the vanilla
sun/moon disc sizes.
**Deps:** `sky` group, `client/sunmoonapi/CelestialPath`.
**Keys:** `sky.*`

### 5.17 Procedural Water — H
Screen-space-refracted water with Beer-Lambert absorption, depth-aware refraction rejection, Fresnel, micro and
detail wave bands, celestial microfacet glint, and optional SSR. Geometry stays flat.
**Deps:** `water` group, `WaterTerrainPass`, `WaterSurfaceRenderer`, `EmbeddiumFluidRendererWaterMixin`,
`EmbeddiumWaterShaderCompileContextMixin`.
**Keys:** `water.*`, `ssr.quality`

### 5.18 Shared SSR — M
One screen-space reflection implementation with three consumers (water, puddles, wet) differing only by
`SsrSettings` — coarse steps, refinement steps, max distance, thickness, edge fade, strength.
**Deps:** `ssr` group, `SsrHitBuffer`.
**Keys:** `ssr.quality`, `water.ssr*`, `puddle.ssr*`, `wet.ssr*`

### 5.19 Rain Puddles — M
Command-placed virtual water at the block surface with grayscale depth map, wet darkening fringe, ripple rings,
refraction, and optional SSR.
**Deps:** `rainPuddles` group, `RainPuddleInstance`, `RainPuddlePatch`, `/rainnew`.
**Keys:** `rainPuddles.*`

### 5.20 Wet Surfaces — H
Screen-space wet-film material on upward-facing SOLID terrain, including analytic sky/environment fallback,
dielectric sheen floor, celestial specular, and rain-ring lighting contrast. 18 params, no CPU block scan.
**Deps:** `wet` group, `WetSurfaceRenderer`, `water_micro_normal.png`.
**Keys:** `wet.*`, `ssr.quality`

### 5.21 Plant Vertex Wind — M
Material bit-packing of face normal (3 bits) and wave profile (2 bits) per material/direction/axis combination
across SOLID, CUTOUT, CUTOUT_MIPPED, and TRANSLUCENT — four parallel material tables.
**Deps:** `grass` group, `PlantWaveRegistry`, `PlantWaveMaterialEncoder`.
**Keys:** `grass.*`

### 5.22 Opaque Ice — L
Moves `minecraft:ice` to the SOLID chunk layer, removing translucent sorting/blending/overdraw in frozen biomes.
**Deps:** `ItemBlockRenderTypesMixin`.
**Keys:** `opaqueIce.enabled`

### 5.23 LSR — M
Reduced-resolution world render with edge-adaptive reconstruction before the live hand and GUI.
**Deps:** `lsr` group, `LsrSystem`, `RenderTarget` accessors.
**Keys:** `lsr.*`

### 5.24 TFR — H
Fixed real/synthetic cadence with depth-based reprojection, history stabilization, and camera-cut detection by
both distance and angle. ~1.5× presented FPS in world-render-limited scenes.
**Deps:** `tfr` group, `TemporalFrameSystem`, three render targets (scene, prepared history, motion).
**Keys:** `tfr.*`

### 5.25 Debug HUD — L
Independent of F3: FPS, used/max RAM, allocated RAM, corner placement, offsets, text scale, update interval.
**Deps:** `debugHud` group, `LuxiumDebugHud`.
**Keys:** `debugHud.*`

### 5.26 F3 Integration — L
Injects a red `Luxium v<version>` line after the third blank line of the F3 right column.
**Deps:** `LuxiumF3Info`, `CustomizeGuiOverlayEvent.DebugText`.
**Keys:** none

---

## 6. Subsystem Map

| Package | Purpose | Complexity |
|---|---|---|
| `client` | Top-level orchestrators: `Sky`, `ReflectionSystem`, `GodRaySystem`, `LightAtlas`, `ShaderManager` | M |
| `client/clouds` | Three-layer vanilla cloud renderer | M |
| `client/f3` | F3 version line + debug HUD | L |
| `client/guiscreen` | Alpha warning screen, video settings hub, menu backdrop | M |
| `client/kawase` | Dual-Kawase bloom renderer + chunk-indexed source registry | M |
| `client/neocpu` | NeoCPU source-local shadow engine | H |
| `client/plantswave` | Plant profile resolution + material bit encoder | M |
| `client/posteffects` | Post-effect pipeline, fog, god rays, volumetric rays, lens flare, tonemap | H |
| `client/postprocess` | `PostProcessSwapChain`, `SharedPostResources` | M |
| `client/rainpuddles` | Puddle instances, patches, `/rainnew` command | M |
| `client/reflections` | Planar reflection materials and registry | M |
| `client/shaders` | `LuxiumGpuShaderFeatures` shader feature-mask compiler | M |
| `client/shadows` | `GpuNeoShadows` + `GpuShadowFrameState` + atlas/grid/volume | H |
| `client/shadows/embeddium` | Bridge exposing Embeddium locals to the GPU shadow path | H |
| `client/shadows/neoskycelestia` | Cascade shadows, entity shadow map, light LUT, Embeddium bridge | H |
| `client/ssr` | `ScreenSpaceReflectionSystem`, `SsrSettings`, hit buffer | M |
| `client/sunmoonapi` | `CelestialPath` sun/moon orbit sampling, orbit tilt −40° | L |
| `client/tfrpluslsr` | `TemporalFrameSystem`, `LsrSystem` | H |
| `client/water` | `WaterTerrainPass`, `WaterSurfaceRenderer`, texture resources | H |
| `client/wet` | Global wet-film material pass | H |
| `client/BlockLightTest` | Independent vanilla-propagation block light experiment | M |
| `client/ConfigScreen` | Config screen: model, options, widgets, previews, glass panel | M |
| `rtx` | NeoFlood solver, torch state, colored light, section skip, entity shadows, lava engine, light math | H |
| `rtx/neogpuvanilla` | Baked receiver volume (48×32×48 voxels), cutout prepass | H |
| `rtx/soasnottointerfere` | Exposed-face capture service; keeps probes from disturbing vanilla light | H |
| `mixin` | 35 top-level mixins (vanilla hooks) | H |
| `mixin/sky` | 9 sky/shader mixins | H |
| `Testing` | Dev-only commands, effects, explosion renderer, master keybind | M |

`rtx/soasnottointerfere` is worth calling out: `ExposedFaceService` captures exposed solid faces in a
separate pass (2 section captures/tick, 2048 dirty blocks/tick, 64 async commits/tick) so that optimistic
visibility-volume rebuilds never alter vanilla light results. Without it, NeoFlood and vanilla disagree.

### 6.1 `LuxiumGpuShaderFeatures`

Recomputes a 5-bit feature mask every frame, consumed by all injected Luxium shaders:

| Bit | Mask | Condition |
|---|---|---|
| 0 | `1` | `NeoGpuVanilla.isConfiguredEnabled()` |
| 1 | `2` | `!neoGpuVanilla && gpuShadowsEnabled && !neoCpuShadowsEnabled && gpuLocalLightingMode != NEOFLOOD_ONLY` |
| 2 | `4` | `skyLightEnabled` |
| 3 | `8` | `neoGpuVanilla \|\| localReceiver` |
| 4 | `16` | `neoGpuVanilla \|\| localReceiver \|\| neoSky` |

Accessors: `neoGpuVanillaReceiverEnabled()`, `localReceiverEnabled()`, `neoSkyCelestiaEnabled()`,
`receiverShadersEnabled()`, `receiverBindingEnabled()`. `skyReceiverEnabled()` is hardcoded `false`.
Any port needs this same mask, or every Luxium shader variant must be split differently.

---

## 7. Rendering API Hook Map

### 7.1 Vanilla mixins (30)

| Mixin | Target | Role |
|---|---|---|
| `RenderTargetAccessor` | `RenderTarget` | depth/texture access for post effects |
| `ItemBlockRenderTypesMixin` | `ItemBlockRenderTypes` | opaque ice layer move |
| `LayerLightSectionStorageMixin` | `LayerLightSectionStorage` | raw light level read/write, inconsistency marking, section map swap |
| `LightEngineMixin` | `LightEngine` | budgeted light updates |
| `BlockLightEngineMixin` | `BlockLightEngine` | lava-only isolated engine, emission hook |
| `MinecraftAccessor` | `Minecraft` | main render target access |
| `ClientChunkCacheMixin` | `ClientChunkCache` | chunk replace/drop/view-radius invalidation |
| `ClientLevelMixin` | `ClientLevel` | tick/unload invalidation |
| `LevelBlockStateMixin` | `Level` | block-state geometry capture for shadows |
| `ChunkRenderDispatcherRenderChunkMixin` | `ChunkRenderDispatcher$RenderChunk` | rebuild task injection |
| `ChunkRenderDispatcherRebuildTaskMixin` | `ChunkRenderDispatcher$RenderChunk$RebuildTask` | `SectionCaptureAccess` |
| `BlockEntityRenderDispatcherMixin` | `BlockEntityRenderDispatcher` | block-entity shadows |
| `LevelRendererAccessor` | `LevelRenderer` | cloud/sky passes |
| `LevelRendererMixin` | `LevelRenderer` (priority 1100) | sky, clouds, celestial passes |
| `EntityRendererMixin` | `EntityRenderer` | hybrid GPU local lighting on entities |
| `EntityRenderDispatcherAccessor` | `EntityRenderDispatcher` | caster iteration |
| `GameRendererAccessor` | `GameRenderer` | projection/inverse-projection matrices |
| `GameRendererMixin` | `GameRenderer` | post-effect chain, LSR/TFR frame hooks |
| `GuiMixin` | `Gui` | overlay-space effects |
| `ForgeGuiMixin` | `Gui` | Forge GUI pipeline |
| `LightTextureMixin` | `LightTexture` | celestial lightmap upload |
| `LightTextureAccessor` | `LightTexture` | block-only light texture id, revision |
| `ModelBlockRendererMixin` | `ModelBlockRenderer` | per-quad light metadata |
| `ForgeModelBlockRendererMixin` | `ForgeModelBlockRenderer` | Forge model pipeline |
| `MultiPlayerGameModeMixin` | `MultiPlayerGameMode` | destroy/place invalidation |
| `OptionsScreenMixin` | `OptionsScreen` | Luxium settings button |
| `CameraMixin` | `Camera` | detachment/position hooks |
| `CameraAccessor` | `Camera` | raw camera state |
| `GlStateManagerMixin` | `GlStateManager` | state leak prevention across passes |

### 7.2 Embeddium mixins (17)

Two families coexist — legacy `me.jellysquid.mods.sodium.*` and modern `org.embeddedt.embeddium.impl.*`.
This dual targeting is the single biggest porting hazard.

| Mixin | Target | Role |
|---|---|---|
| `EmbeddiumTerrainPassesMixin` | `DefaultTerrainRenderPasses` | register Luxium water/wet/puddle passes |
| `EmbeddiumFluidRendererWaterMixin` | `FluidRenderer` | replace vanilla water material |
| `EmbeddiumBlockRendererMixin` | `me.jellysquid…BlockRenderer` | per-block light metadata |
| `EmbeddiumRenderSectionLifecycleMixin` | `RenderSection` | section lifecycle hooks |
| `EmbeddiumRenderSectionAccessor` | `org.embeddedt…RenderSection` | `getOriginX` invoker |
| `EmbeddiumChunkBuilderMeshingTaskAccessor` | `org.embeddedt…ChunkBuilderMeshingTask` | `render` accessor |
| `EmbeddiumChunkBuilderMeshingTaskMixin` | `org.embeddedt…ChunkBuilderMeshingTask` | meshing injection |
| `EmbeddiumBlockOcclusionCacheMixin` | `me.jellysquid…BlockOcclusionCache` | visibility injection |
| `sky.SodiumWorldRendererAccessor` | `SodiumWorldRenderer` | renderer instance/nullability |
| `sky.RenderSectionManagerAccessor` | `RenderSectionManager` | section manager internals |
| `sky.SodiumWorldRendererReloadMixin` | `SodiumWorldRenderer` | shader reload lifecycle |
| `sky.EmbeddiumShaderLoaderMixin` | `ShaderLoader` | Luxium shader program injection |
| `sky.EmbeddiumChunkShaderInterfaceMixin` | `ChunkShaderInterface` | chunk shader uniform injection |
| `sky.EmbeddiumNeoGpuCutoutPrepassMixin` | `ShaderChunkRenderer` | CUTOUT prepass |
| `sky.EmbeddiumWaterShaderCompileContextMixin` | `ShaderChunkRenderer` | water shader defines |
| `sky.ShaderInstanceSkyShadowMixin` | `ShaderInstance` | celestial shadow uniforms |
| `sky.MinecraftSkyShadowLifecycleMixin` | `Minecraft` | shadow cascade lifecycle |

### 7.3 Custom accessor interfaces

Implemented by mixins, consumed as plain interfaces:

| Interface | Methods |
|---|---|
| `SectionCaptureAccess` | `luxium$setSectionOrigin(BlockPos)`, `luxium$getSectionOrigin()` |
| `NeoSkyLightTextureExtension` | `neosky$getBlockOnlyLightTextureId()`, `neosky$getMainLightPixel(int,int)`, `neosky$getBlockOnlyLightPixel(int,int)`, `neosky$getLightmapRevision()` |
| `LuxiumLightEngineExtension` | `luxium$runLightUpdatesBudgeted(int)` |
| `LuxiumBlockLightEngineExtension` | `luxium$setLavaOnly(boolean)`, `luxium$isLavaOnly()` |
| `LuxiumLayerLightStorageExtension` | `luxium$getStoredLevel(long)`, `luxium$setStoredLevel(long,int)`, `luxium$markNewInconsistencies(LightEngine)`, `luxium$swapSectionMap()` |

### 7.4 Key Embeddium/Sodium types used

`RenderDevice`, `ChunkRenderMatrices`, `ChunkBuildBuffers`, `BlockRenderContext`, `FluidRenderer`,
`BuiltSectionInfo`, `RenderSection`, `RenderSectionManager`, `DefaultTerrainRenderPasses`, `DefaultMaterials`,
`Material`, `AlphaCutoffParameter`, `TerrainRenderPass`, `ChunkVertexEncoder`, `ShaderChunkRenderer`,
`ShaderLoader`, `ChunkShaderInterface`, `SodiumWorldRenderer`, `WorldRendererExtended`.

---

## 8. Commands, Keybinds, Debug Surface

### 8.1 Keybind

`LuxiumMasterKeyMapping.TOGGLE` — `key.luxium.toggle_master` ("Toggle Luxium"), category
`key.categories.luxium` ("Luxium"), `Type.KEYSYM`, default keycode **75** (`K`).
`LuxiumMasterKeyHandler.onClientTick` flips `luxiumEnabled` and saves.

### 8.2 Commands (`Testing/ModCommands`, client-only)

| Command | Args | Default |
|---|---|---|
| `/skylighttest` | — | |
| `/flashlight` | `off` \| `on [power 1..15] [angle 1..180]` | power 15, angle 180 |
| `/grass` | `off` \| `on [radius 1..100]` | current render radius |
| `/rtxcolor` | `spectre <speed 1..100>` \| `police` \| `police2` \| `off` \| `reset` \| `red <0..255> green <0..255> blue <0..255>` | |
| `/rain` | `[size 1..10]` | 1 |
| `/blackhole` | `[size 1..10]` \| `delete` | 1 |
| `/airdistortion` | `[radius 1..10]` \| `delete` \| `explosion <pos> <power 1..40> <speed 1..300>` | 1 |
| `/explosionxul` | `<pos> <power 1..100>` | |

`RainPuddleCommands` registers a second command: `/rainnew [size 1..10]`, which places a puddle at the
looked-at block. Both `/rain` and `/rainnew` coexist.

### 8.3 Dev-only effect classes (no config surface)

`AirDistortion`, `BlackHole`, `Grass`, `ShockWaveTest`, `SphereWarpEffect`, `TestFlashLight`,
`ExplosionCollisionCache`, `ExplosionFluidGrid`, `ExplosionParticleSystem`, `ExplosionProfile`,
`ExplosionRenderer`, `ExplosionRenderSnapshot`, `ExplosionShader`, `ExplosionWorld`.

---

## 9. Orphaned Assets — Do Not Implement

| Key / value | Reason |
|---|---|
| `category.luxium.testing` | no `Category("testing", …)` is ever created |
| `category.luxium.testing.folder` | same; `testing` appears in `developmentIds` but matches nothing |
| `option.luxium.realistic_shadows_enabled` + tooltip | field is live internally but never surfaced |
| `option.luxium.neo_cpu_shadows_enabled` + tooltip | field is live internally but never surfaced |

`realisticShadowsEnabled` and `neoCpuShadowsEnabled` are only read in other options' `enabledWhen` predicates —
so both engines are reachable by config file but unreachable from the GUI. Decide deliberately whether your
port exposes them.

The `value.luxium.*` keys appear "unreferenced" to a literal grep but are built by string concatenation
(`"value.luxium.gpu_local_lighting_mode." + name.toLowerCase(Locale.ROOT)`). All 11 are live.

---

## 10. Reimplementation Order

Recommended build sequence, cheapest-risk-first:

1. **Config scaffolding + UI framework** — 277 values, 24 categories, 2 folders, 5 widget types, previews,
   conditional enablement. Reuse the `ConfigScreenModel` structure verbatim; it is cleanly factored.
2. **Opaque Ice** — one mixin, proves the Embeddium mixin harness works. (L)
3. **Plant Wind** — material bit-packing pattern, reused later by shadow normals. (M)
4. **Tonemap + Lens Flare** — pure post-processing, no scene dependency. (L/M)
5. **Fog + Sky God Rays + Volumetric God Rays** — post chain; volumetric requires NeoSkyCelestia cascades. (M/H)
6. **NeoSkyCelestia** — cascades, entity shadow map, light LUT. Unlocks volumetric rays. (H)
7. **Kawase Bloom** — first real scene-depth dependency. (M)
8. **Shared SSR + Water + Wet + Puddles** — one SSR core, three consumers. (H)
9. **LSR + TFR** — frame-level resolution/reprojection; do after the post chain is stable. (M/H)
10. **Local lighting** — GPU shadows → NeoCPU → BlockLightTest → NeoFlood RTX. Highest risk; each depends on
    the previous being understood. (H)
11. **Planar reflections, cinematic sky, three-layer clouds** — independent of the lighting stack. (M)

**Hardest single problem:** NeoFlood RTX + `rtx/soasnottointerfere`. Getting optimistic visibility rebuilds to
agree with vanilla light propagation is the entire point of `ExposedFaceService`. Prototype that first if the
RTX path matters to you; otherwise drop `rtx.*` and ship GPU local lighting alone.

**Do not port:** `GodRaySystem` (`ENABLED = false` constant), the two dead config keys, the orphan lang keys,
and the explosion/black-hole/air-distortion dev commands unless you want a debug playground.

---

## 11. Open Questions

- Target codebase renderer is unstated. If it is not Embeddium `0.3.x`, sections 7.1/7.2 need re-derivation
  and the local-lighting features (5.2, 5.3, 5.4) are effectively rewrites rather than ports.
- Minecraft version mapping is unstated; obfuscated member names in this build (`m_*`) will not match.
- Whether the `not_ready_to_play_dev` folder split should be preserved is a product decision — it is a
  deliberate quality signal, not a technical requirement.