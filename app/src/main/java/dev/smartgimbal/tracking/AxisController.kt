package dev.smartgimbal.tracking

import kotlin.math.abs

/**
 * Turns the angular error of one axis into a joystick speed.
 *
 * Steps: EMA smoothing, deadzone with hysteresis (starts moving above the deadzone, stops below
 * half of it, so the gimbal does not twitch at the edge), PD law in deg/s, conversion to units,
 * output limit and ramp limit.
 */
class AxisController {

    private var filtered = 0.0
    private var previousFiltered = 0.0
    private var hasValue = false
    private var holding = true
    private var output = 0.0

    /**
     * @param errorDeg how far the target is off-centre in the positive axis direction
     * @param dtSec time since the previous update
     * @return speed in joystick units, positive = towards the target in the positive direction
     */
    fun update(errorDeg: Double, dtSec: Double, params: TrackingParams): Double {
        val alpha = params.smoothing.toDouble().coerceIn(0.05, 1.0)
        filtered = if (hasValue) filtered + alpha * (errorDeg - filtered) else errorDeg
        val derivative = if (hasValue && dtSec > 0) (filtered - previousFiltered) / dtSec else 0.0
        previousFiltered = filtered
        hasValue = true

        val magnitude = abs(filtered)
        if (holding && magnitude > params.deadzoneDeg) {
            holding = false
        } else if (!holding && magnitude < params.deadzoneDeg * HYSTERESIS) {
            holding = true
        }

        val max = params.maxUnits.toDouble()
        val target = if (holding) {
            0.0
        } else {
            ((params.kp * filtered + params.kd * derivative) / DEG_PER_SEC_PER_UNIT).coerceIn(-max, max)
        }
        val step = params.maxAccelUnitsPerSec * dtSec
        output += (target - output).coerceIn(-step, step)
        return output
    }

    /**
     * No measurement this frame (e.g. a detector dropout): ramp the output towards zero but keep
     * the filter state, so tracking continues smoothly when the face is found again.
     */
    fun coast(dtSec: Double, params: TrackingParams): Double {
        val step = params.maxAccelUnitsPerSec * dtSec
        output -= output.coerceIn(-step, step)
        return output
    }

    fun reset() {
        hasValue = false
        holding = true
        output = 0.0
        filtered = 0.0
        previousFiltered = 0.0
    }

    private companion object {
        const val HYSTERESIS = 0.5
    }
}
