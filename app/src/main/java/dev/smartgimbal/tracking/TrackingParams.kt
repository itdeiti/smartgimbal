package dev.smartgimbal.tracking

/**
 * Tuning of the face tracker; all values can be changed in the debug screen.
 *
 * Kp/Kd from the tuning run on 2026-11-22: with Kp 2 / Kd 0 the gimbal overshot steps by 30-40 %,
 * because it smooths speed changes itself (tau ~0.25 s) and the preview-copy analysis adds ~140 ms.
 * A simulation with that plant gives 0 % overshoot (12 % worst case) and 1.3 s settling for
 * Kp 1.4 / Kd 0.3, at the cost of ~15 deg lag behind a face walking at 20 deg/s.
 */
data class TrackingParams(
    /** Proportional gain: commanded speed in deg/s per degree of error. */
    val kp: Float = 1.4f,
    /** Derivative gain in deg/s per deg/s of error change; brakes before the slow gimbal overshoots. */
    val kd: Float = 0.3f,
    /** No correction while the face is this close to the centre (degrees). */
    val deadzoneDeg: Float = 3.0f,
    /** Output limit in joystick units; ~250 is the end of the gimbal's linear range. */
    val maxUnits: Int = 250,
    /** Ramp limit in units per second against jerky starts and stops. */
    val maxAccelUnitsPerSec: Float = 1500f,
    /** EMA factor on the measured error, 1 = no smoothing. */
    val smoothing: Float = 0.5f,
    /**
     * Direction signs per camera, see [FaceTracker] for the geometry behind the defaults.
     * Confirmed in the first live test (2026-11-22): the opposite signs ran away on both cameras.
     */
    val rearPanSign: Int = -1,
    val rearTiltSign: Int = -1,
    val frontPanSign: Int = -1,
    val frontTiltSign: Int = 1,
    /**
     * Stop pushing towards the tilt end stops (operator terms, from telemetry). The real stop can be
     * lower (26.9 deg up in the tuning run); [FaceTracker] detects a blocked tilt and learns it.
     */
    val tiltUpLimitDeg: Float = 30f,
    val tiltDownLimitDeg: Float = -25f,
) {
    /**
     * Parameters for a zoom ratio: when zoomed in, the deadzone shrinks with the field of view,
     * otherwise 3 deg would be a third of the frame at 4x. Wide angle keeps the base deadzone.
     */
    fun forZoom(ratio: Double): TrackingParams =
        if (ratio <= 1.0) this else copy(deadzoneDeg = (deadzoneDeg / ratio).toFloat())
}

/** Measured gimbal response: about 0.22 deg/s per joystick unit in the linear range. */
const val DEG_PER_SEC_PER_UNIT = 0.22
