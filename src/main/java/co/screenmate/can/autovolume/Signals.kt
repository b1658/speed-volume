package co.screenmate.can.autovolume

import co.screenmate.can.privileged.VendorSignals

/**
 * The vendor VHAL prop ids this app reads through the privileged broadcast client, resolved from the
 * SDK's [VendorSignals] catalog. Keeping them here (a) documents exactly what we subscribe to and
 * (b) gives one array to hand the client's `requestedSignals`, so the agent broadcasts just these.
 */
object Signals {
    private fun id(name: String) = VendorSignals.byName.getValue(name).propertyId

    /** km/h, float. The primary driver of the volume curve. */
    val SPEED = id("DI_VEHICLE_SPEED")

    /** Drive-state gating. Tesla DI_gear enum: 1=P, 2=R, 3=N, 4=D. */
    val GEAR = id("DI_GEAR")
    const val GEAR_DRIVE = 4

    /** Noise-aware boost: actual cabin blower RPM (physical fan-noise proxy). */
    val FAN_RPM = id("VCLEFT_HVAC_BLOWER_RPM_ACTUAL")

    /** Noise-aware boost: the four door windows' positions (road/wind noise proxy). */
    val WINDOW_FL = id("UI_WINDOW_REQUESTED_FL")
    val WINDOW_FR = id("UI_WINDOW_REQUESTED_FR")
    val WINDOW_RL = id("UI_WINDOW_REQUESTED_RL")
    val WINDOW_RR = id("UI_WINDOW_REQUESTED_RR")

    /** Everything to request from the agent in one shot. */
    val ALL = intArrayOf(SPEED, GEAR, FAN_RPM, WINDOW_FL, WINDOW_FR, WINDOW_RL, WINDOW_RR)

    val WINDOWS = intArrayOf(WINDOW_FL, WINDOW_FR, WINDOW_RL, WINDOW_RR)

    // Approximate normalization ceilings for the raw noise signals. These only scale a comfort
    // boost, never anything safety-relevant, so an imperfect ceiling just makes the boost saturate
    // or under-read a little; both are clamped to [0,1]. Tune once observed on-car.
    const val FAN_RPM_MAX = 3200f
    const val WINDOW_RAW_MAX = 100f
}
