package co.screenmate.can.autovolume

/**
 * Pure speed(+noise) -> **volume-boost** mapping. No Android dependencies so it can be unit-tested.
 *
 * The app controls volume by the steering-wheel LEFT scroll, which is a *relative* input with no CAN
 * read-back — so instead of an absolute target it computes an OFFSET, in scroll detents (≈ one UI
 * volume step each), to add ON TOP of whatever volume the driver has set. Below [minSpeed] the offset
 * is 0 (you hear exactly your own setting); between [minSpeed] and [maxSpeed] it grows (shaped by
 * [curve]) to [maxBoostSteps]; at/above [maxSpeed] it caps there. Cabin-noise adds a little more:
 * up to [fanBoostSteps] (scaled by [fanRatio]) and [windowBoostSteps] (scaled by [windowRatio]).
 * Result is clamped to a non-negative integer.
 */
object SpeedVolumeMapper {

    enum class Curve {
        /** Boost rises in direct proportion to speed. */
        LINEAR,

        /**
         * Ease-in/ease-out (smoothstep). Gentler near a stop and near top speed, steeper through the
         * middle — perceptually smoother, since road/wind noise is itself non-linear in speed.
         */
        PERCEPTUAL,
    }

    /** @return the volume offset to hold, in scroll detents (>= 0), for [speed]. */
    fun targetBoostSteps(
        speed: Float,
        minSpeed: Int,
        maxSpeed: Int,
        maxBoostSteps: Int,
        curve: Curve = Curve.LINEAR,
        fanRatio: Float = 0f,
        fanBoostSteps: Int = 0,
        windowRatio: Float = 0f,
        windowBoostSteps: Int = 0,
    ): Int {
        val top = maxBoostSteps.coerceAtLeast(0)
        val t = when {
            speed <= minSpeed -> 0f
            speed >= maxSpeed -> 1f
            maxSpeed <= minSpeed -> 1f
            else -> (speed - minSpeed) / (maxSpeed - minSpeed).toFloat()
        }
        val shaped = when (curve) {
            Curve.LINEAR -> t
            Curve.PERCEPTUAL -> t * t * (3f - 2f * t) // smoothstep
        }
        val speedSteps = top * shaped
        val boost = fanBoostSteps * fanRatio.coerceIn(0f, 1f) +
            windowBoostSteps * windowRatio.coerceIn(0f, 1f)
        return Math.round(speedSteps + boost).coerceAtLeast(0)
    }
}
