# speed-volume

An Android app that adjusts the car's media volume from live vehicle speed (and cabin noise),
BMW-style: the faster you go, the more it boosts — so you don't keep reaching for the volume.

## How it works

- Reads live signals through the **privileged-client** SDK, narrowed to just what it needs:
  `DI_VEHICLE_SPEED` (the primary driver), `DI_GEAR` (drive-state gating), `VCLEFT_HVAC_BLOWER_RPM`
  (fan noise), and the four `UI_WINDOW_REQUESTED_*` positions (road/wind noise).
- **`SpeedVolumeMapper`** is a pure, unit-tested function mapping speed (+noise) to a volume
  **offset in scroll detents**. Because volume is actuated via the steering-wheel LEFT scroll — a
  *relative* input with no CAN read-back — it computes an offset to add on top of whatever the
  driver has set, rather than an absolute target. Below `minSpeed` the offset is 0; between
  `minSpeed` and `maxSpeed` it grows (LINEAR or PERCEPTUAL/smoothstep curve) up to `maxBoostSteps`;
  it caps there above `maxSpeed`. Fan and window noise add a little more on top.
- **`VolumeControlService`** is a `connectedDevice` foreground service that owns the CAN client and
  drives the volume on each accepted batch. It actuates via the steering-wheel left scroll over the
  root TX transport, with an `AudioManager` fallback.
- **`SettingsActivity`** (curve, speed range, boost steps, noise ratios), a **Quick Settings tile**
  to toggle the feature, and a **boot receiver** to re-arm after a car power cycle.

## Requirements

- `minSdk`/`targetSdk` 34, Java/Kotlin 17. Android app module (`com.android.application`).
- Holds the injector-defined `co.screenmate.can.permission.SIGNALS` (install the injector first),
  plus `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `MODIFY_AUDIO_SETTINGS`, and `INTERNET`
  (loopback only — the TX transport reaches the box's own root adbd at `127.0.0.1:5555`).

## Note

Extracted from a larger monorepo. `build.gradle.kts` depends on sibling modules `:privileged-client`
(the CAN SDK) and `:tx-client` (`CanTx`, the left-scroll volume transport); wire those to build.
