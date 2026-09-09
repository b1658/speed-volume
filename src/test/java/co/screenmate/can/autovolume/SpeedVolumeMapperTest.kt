package co.screenmate.can.autovolume

import co.screenmate.can.autovolume.SpeedVolumeMapper.Curve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedVolumeMapperTest {

    // defaults: ramp 20..120 km/h, +6 steps at top, linear, no noise boost.
    private fun t(speed: Float) = SpeedVolumeMapper.targetBoostSteps(speed, 20, 120, 6)

    @Test fun belowMinIsZeroBoost() {
        assertEquals(0, t(0f))
        assertEquals(0, t(20f))
    }

    @Test fun atOrAboveMaxIsCapped() {
        assertEquals(6, t(120f))
        assertEquals(6, t(200f))
    }

    @Test fun midpointIsLinear() {
        // 70 km/h is halfway across 20..120 -> half of 6 = 3.
        assertEquals(3, t(70f))
    }

    @Test fun degenerateRangeDoesNotCrash() {
        // maxSpeed <= minSpeed: below/at the threshold stays 0, strictly above snaps to full boost.
        assertEquals(0, SpeedVolumeMapper.targetBoostSteps(50f, 100, 100, 6))
        assertEquals(0, SpeedVolumeMapper.targetBoostSteps(100f, 100, 100, 6))
        assertEquals(6, SpeedVolumeMapper.targetBoostSteps(150f, 100, 100, 6))
    }

    @Test fun perceptualCurveMatchesEndpointsButEasesMiddle() {
        // Smoothstep at t=0.5 equals 0.5, so the exact midpoint coincides with linear.
        assertEquals(t(70f), SpeedVolumeMapper.targetBoostSteps(70f, 20, 120, 6, Curve.PERCEPTUAL))
        // Endpoints identical regardless of curve.
        assertEquals(0, SpeedVolumeMapper.targetBoostSteps(20f, 20, 120, 6, Curve.PERCEPTUAL))
        assertEquals(6, SpeedVolumeMapper.targetBoostSteps(120f, 20, 120, 6, Curve.PERCEPTUAL))
        // Below the midpoint smoothstep sits under the straight line (eases in). Use a big range so
        // rounding doesn't hide it: at 45 km/h, linear=0.25*20=5, perceptual<5.
        val lin = SpeedVolumeMapper.targetBoostSteps(45f, 20, 120, 20, Curve.LINEAR)
        val per = SpeedVolumeMapper.targetBoostSteps(45f, 20, 120, 20, Curve.PERCEPTUAL)
        assertTrue("perceptual should ease in below midpoint ($per !< $lin)", per < lin)
    }

    @Test fun noiseBoostAddsOnTopAndNeverNegative() {
        // Speed term at 70 km/h = 3 steps. Full fan +2, half-open windows +1.5 -> round(6.5)=7.
        val boosted = SpeedVolumeMapper.targetBoostSteps(
            70f, 20, 120, 6, Curve.LINEAR,
            fanRatio = 1f, fanBoostSteps = 2, windowRatio = 0.5f, windowBoostSteps = 3,
        )
        assertEquals(7, boosted)
        // Ratios out of range are clamped; result is never negative.
        val clamped = SpeedVolumeMapper.targetBoostSteps(
            0f, 20, 120, 6, Curve.LINEAR, fanRatio = -5f, fanBoostSteps = 4,
        )
        assertEquals(0, clamped)
    }
}
