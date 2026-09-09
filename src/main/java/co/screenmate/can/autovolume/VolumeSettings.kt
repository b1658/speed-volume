package co.screenmate.can.autovolume

import android.content.Context
import android.content.SharedPreferences

/** How the app actually moves the volume. */
enum class WriteBackend {
    /** Steering-wheel LEFT scroll over CAN TX (native Tesla volume; needs the injector + TX armed). */
    SCROLL,

    /** Android AudioManager media stream (works only if the MCU honours it; no root/TX needed). */
    ANDROID_AUDIO,
}

/**
 * Display unit for speed values in the UI. Storage stays canonical km/h (what DI_VEHICLE_SPEED and
 * [SpeedVolumeMapper] use), so switching units only re-labels/converts the sliders and readouts.
 */
enum class SpeedUnit(val label: String, val perKmh: Float) {
    KMH("km/h", 1f),
    MPH("mph", 1f / 1.609344f);

    /** Canonical km/h -> value shown in this unit (rounded to a whole number for sliders). */
    fun fromKmh(kmh: Int): Int = Math.round(kmh * perKmh)
    /** Canonical km/h -> value shown in this unit (float, for the live readout). */
    fun fromKmh(kmh: Float): Float = kmh * perKmh
    /** Value shown in this unit -> canonical km/h (rounded). */
    fun toKmh(shown: Int): Int = Math.round(shown / perKmh)
}

/**
 * User-tunable configuration for speed-dependent volume, persisted in [SharedPreferences].
 *
 * The model mirrors BMW's "Speed-Volume Control", but because the LEFT scroll wheel is a *relative*
 * input with no volume read-back, the app never sets an absolute level: it adds a speed/noise-
 * proportional OFFSET (in scroll detents) on top of the volume YOU set, and removes it as you slow.
 * That is why there is no "base volume" here — your own volume is the base. See [SpeedVolumeMapper].
 *
 * Speeds are in the unit reported by DI_VEHICLE_SPEED (km/h on these vehicles). Defaults are gentle.
 */
class VolumeSettings(private val prefs: SharedPreferences) {

    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, true)
        set(v) = prefs.edit().putBoolean(K_ENABLED, v).apply()

    var backend: WriteBackend
        get() = runCatching { WriteBackend.valueOf(prefs.getString(K_BACKEND, DEF_BACKEND.name)!!) }
            .getOrDefault(DEF_BACKEND)
        set(v) = prefs.edit().putString(K_BACKEND, v.name).apply()

    /** Display unit for speed in the UI (km/h or mph). Storage stays km/h. */
    var speedUnit: SpeedUnit
        get() = runCatching { SpeedUnit.valueOf(prefs.getString(K_SPEED_UNIT, DEF_SPEED_UNIT.name)!!) }
            .getOrDefault(DEF_SPEED_UNIT)
        set(v) = prefs.edit().putString(K_SPEED_UNIT, v.name).apply()

    /** Speed at/below which no boost is added (you hear exactly your own volume). */
    var minSpeedKmh: Int
        get() = prefs.getInt(K_MIN_SPEED, DEF_MIN_SPEED)
        set(v) = prefs.edit().putInt(K_MIN_SPEED, v).apply()

    /** Speed at/above which the boost is capped at [maxBoostSteps]. */
    var maxSpeedKmh: Int
        get() = prefs.getInt(K_MAX_SPEED, DEF_MAX_SPEED)
        set(v) = prefs.edit().putInt(K_MAX_SPEED, v).apply()

    /** Volume steps (scroll detents) added at/above [maxSpeedKmh]. The "how much louder" ceiling. */
    var maxBoostSteps: Int
        get() = prefs.getInt(K_MAX_BOOST, DEF_MAX_BOOST)
        set(v) = prefs.edit().putInt(K_MAX_BOOST, v.coerceIn(0, 30)).apply()

    /** Only add boost while media is actually playing. */
    var onlyWhilePlaying: Boolean
        get() = prefs.getBoolean(K_ONLY_PLAYING, true)
        set(v) = prefs.edit().putBoolean(K_ONLY_PLAYING, v).apply()

    /** Start the background service automatically after a reboot / car power-on. */
    var startOnBoot: Boolean
        get() = prefs.getBoolean(K_START_ON_BOOT, true)
        set(v) = prefs.edit().putBoolean(K_START_ON_BOOT, v).apply()

    // ---- Drive-state gating ----

    /** Only add boost while the car is in Drive; hold zero offset otherwise. */
    var onlyInDrive: Boolean
        get() = prefs.getBoolean(K_ONLY_DRIVE, true)
        set(v) = prefs.edit().putBoolean(K_ONLY_DRIVE, v).apply()

    /** On shifting out of Drive, wind the added boost back off so your own volume returns. */
    var restoreOnPark: Boolean
        get() = prefs.getBoolean(K_RESTORE_PARK, true)
        set(v) = prefs.edit().putBoolean(K_RESTORE_PARK, v).apply()

    // ---- Curve shape + hysteresis ----

    var curve: SpeedVolumeMapper.Curve
        get() = runCatching {
            SpeedVolumeMapper.Curve.valueOf(prefs.getString(K_CURVE, DEF_CURVE.name)!!)
        }.getOrDefault(DEF_CURVE)
        set(v) = prefs.edit().putString(K_CURVE, v.name).apply()

    /** Seconds to slew across the full boost range; larger = smoother, slower. */
    var rampSeconds: Int
        get() = prefs.getInt(K_RAMP_SECS, DEF_RAMP_SECS)
        set(v) = prefs.edit().putInt(K_RAMP_SECS, v.coerceIn(1, 30)).apply()

    /** Deadband in steps: ignore offset changes smaller than this, so it never hunts / over-scrolls. */
    var deadbandSteps: Int
        get() = prefs.getInt(K_DEADBAND, DEF_DEADBAND)
        set(v) = prefs.edit().putInt(K_DEADBAND, v.coerceIn(0, 10)).apply()

    // ---- Noise-aware boost ----

    var noiseAwareEnabled: Boolean
        get() = prefs.getBoolean(K_NOISE, true)
        set(v) = prefs.edit().putBoolean(K_NOISE, v).apply()

    /** Extra steps added at full cabin-fan speed. */
    var fanBoostSteps: Int
        get() = prefs.getInt(K_FAN_BOOST, DEF_FAN_BOOST)
        set(v) = prefs.edit().putInt(K_FAN_BOOST, v.coerceIn(0, 15)).apply()

    /** Extra steps added with windows fully open. */
    var windowBoostSteps: Int
        get() = prefs.getInt(K_WIN_BOOST, DEF_WIN_BOOST)
        set(v) = prefs.edit().putInt(K_WIN_BOOST, v.coerceIn(0, 15)).apply()

    companion object {
        const val DEF_MIN_SPEED = 20
        const val DEF_MAX_SPEED = 120
        const val DEF_MAX_BOOST = 6
        const val DEF_RAMP_SECS = 4
        const val DEF_DEADBAND = 1
        const val DEF_FAN_BOOST = 2
        const val DEF_WIN_BOOST = 3
        val DEF_CURVE = SpeedVolumeMapper.Curve.PERCEPTUAL
        val DEF_BACKEND = WriteBackend.SCROLL
        val DEF_SPEED_UNIT = SpeedUnit.KMH

        private const val K_ENABLED = "enabled"
        private const val K_BACKEND = "backend"
        private const val K_SPEED_UNIT = "speed_unit"
        private const val K_MIN_SPEED = "min_speed"
        private const val K_MAX_SPEED = "max_speed"
        private const val K_MAX_BOOST = "max_boost"
        private const val K_ONLY_PLAYING = "only_playing"
        private const val K_START_ON_BOOT = "start_on_boot"
        private const val K_ONLY_DRIVE = "only_drive"
        private const val K_RESTORE_PARK = "restore_park"
        private const val K_CURVE = "curve"
        private const val K_RAMP_SECS = "ramp_secs"
        private const val K_DEADBAND = "deadband"
        private const val K_NOISE = "noise_aware"
        private const val K_FAN_BOOST = "fan_boost"
        private const val K_WIN_BOOST = "win_boost"

        fun from(context: Context): VolumeSettings =
            VolumeSettings(context.getSharedPreferences("speed_volume", Context.MODE_PRIVATE))
    }
}
