package dev.smartgimbal.tracking

/**
 * Tuning of the face tracker. Defaults come from the phase 1 measurements, see
 * docs/phase-2-plan.md; all of them can be changed in the debug screen.
 */
data class TrackingParams(
    /** Proportional gain: commanded speed in deg/s per degree of error. */
    val kp: Float = 2.0f,
    /** Derivative gain in deg/s per deg/s of error change; 0 = pure P. */
    val kd: Float = 0.0f,
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
    /** Stop pushing towards the tilt end stops (operator terms, from telemetry). */
    val tiltUpLimitDeg: Float = 30f,
    val tiltDownLimitDeg: Float = -25f,
)

/** Measured gimbal response: about 0.22 deg/s per joystick unit in the linear range. */
const val DEG_PER_SEC_PER_UNIT = 0.22
