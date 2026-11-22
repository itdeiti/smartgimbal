package dev.smartgimbal.tracking

import dev.smartgimbal.gimbal.MoveCommand
import kotlin.math.abs
import kotlin.math.roundToInt

/** Output of one tracker step; the errors are kept for logging and the overlay. */
data class TrackingOutput(
    val command: MoveCommand,
    /** Target offset in operator terms: positive = right of / above the centre. */
    val panErrorDeg: Double,
    val tiltErrorDeg: Double,
)

/**
 * Face position in the camera frame to gimbal speed command.
 *
 * Direction signs (defaults in [TrackingParams], adjustable in the debug screen):
 * - The analysis frame is not mirrored, x to the right, y down.
 * - Operator terms (see FeiyuProtocol.move) are seen from the display side, i.e. facing the
 *   front camera. Pan right therefore turns both cameras towards their own left, so a face right
 *   of centre needs pan left on both cameras.
 * - Tilt up lifts the display side: the front camera points up, the rear camera down. So the tilt
 *   sign is +1 for the front camera and -1 for the rear camera.
 *
 * Not thread-safe; call from the analysis thread.
 */
class FaceTracker {

    private val pan = AxisController()
    private val tilt = AxisController()
    private var lastMillis = 0L
    private var lastSeenMillis = 0L

    // Tilt stall detection: learned end stops survive reset(), they belong to the gimbal.
    private var learnedUpLimit: Double? = null
    private var learnedDownLimit: Double? = null
    private var stallDirection = 0
    private var stallRefTilt = 0.0
    private var stallRefMillis = 0L

    /** Tilt end stops found by stall detection, for logging. */
    val learnedTiltLimits: Pair<Double?, Double?> get() = learnedUpLimit to learnedDownLimit

    /**
     * @param target face box in the upright analysis frame, null if the target is not visible
     * @param tiltDeg current gimbal tilt from telemetry (operator terms), null if unknown
     */
    fun update(
        target: NormBox?,
        imageWidth: Int,
        imageHeight: Int,
        fov: Fov,
        frontCamera: Boolean,
        tiltDeg: Double?,
        nowMillis: Long,
        params: TrackingParams,
    ): TrackingOutput {
        val dt = if (lastMillis == 0L) NOMINAL_DT else ((nowMillis - lastMillis) / 1000.0).coerceIn(MIN_DT, MAX_DT)
        if (target == null) {
            if (lastSeenMillis != 0L && nowMillis - lastSeenMillis <= LOST_GRACE_MS) {
                // Short dropout (the detector misses single frames): coast instead of stopping hard.
                lastMillis = nowMillis
                val panUnits = pan.coast(dt, params)
                val tiltUnits = limitTowardsEndStop(tilt.coast(dt, params), tiltDeg, params)
                return TrackingOutput(MoveCommand(panUnits.roundToInt(), tiltUnits.roundToInt()), 0.0, 0.0)
            }
            // Gone for longer: hold still and start fresh when the face comes back.
            reset()
            return TrackingOutput(MoveCommand.STOP, 0.0, 0.0)
        }
        lastMillis = nowMillis
        lastSeenMillis = nowMillis

        val (hFov, vFov) = fov.forFrame(imageWidth, imageHeight)
        val rightDeg = Fov.angleFromCenterDeg((target.centerX - 0.5) * 2.0, hFov)
        val upDeg = -Fov.angleFromCenterDeg((target.centerY - 0.5) * 2.0, vFov)

        val panSign = if (frontCamera) params.frontPanSign else params.rearPanSign
        val tiltSign = if (frontCamera) params.frontTiltSign else params.rearTiltSign
        val panError = rightDeg * panSign
        val tiltError = upDeg * tiltSign

        val panUnits = pan.update(panError, dt, params)
        val rawTilt = tilt.update(tiltError, dt, params)
        if (tiltDeg != null) detectTiltStall(rawTilt, tiltDeg, nowMillis)
        val tiltUnits = limitTowardsEndStop(rawTilt, tiltDeg, params)
        return TrackingOutput(MoveCommand(panUnits.roundToInt(), tiltUnits.roundToInt()), panError, tiltError)
    }

    /**
     * Soft end stop: within [SOFT_ZONE_DEG] of a tilt limit the speed towards it shrinks with the
     * remaining room. A hard cut at the limit would overrun by several degrees because of the
     * loop latency, and telemetry only arrives about 2.4 times per second.
     */
    private fun limitTowardsEndStop(units: Double, tiltDeg: Double?, params: TrackingParams): Double {
        if (tiltDeg == null || units == 0.0) return units
        val up = minOf(params.tiltUpLimitDeg.toDouble(), learnedUpLimit ?: Double.MAX_VALUE)
        val down = maxOf(params.tiltDownLimitDeg.toDouble(), learnedDownLimit ?: -Double.MAX_VALUE)
        val room = if (units > 0) up - tiltDeg else tiltDeg - down
        if (room <= 0) return 0.0
        val cap = params.maxUnits * (room / SOFT_ZONE_DEG).coerceAtMost(1.0)
        return units.coerceIn(-cap, cap)
    }

    /**
     * If tilt is driven in one direction for [STALL_MILLIS] without the telemetry moving, the
     * gimbal sits at its end stop: remember that angle (minus a margin) as the limit. A learned
     * limit is dropped again once the tilt is seen beyond it (e.g. after moving the gimbal by hand).
     */
    private fun detectTiltStall(units: Double, tiltDeg: Double, nowMillis: Long) {
        learnedUpLimit?.let { if (tiltDeg > it + STALL_MARGIN_DEG * 2) learnedUpLimit = null }
        learnedDownLimit?.let { if (tiltDeg < it - STALL_MARGIN_DEG * 2) learnedDownLimit = null }

        val direction = when {
            units > STALL_MIN_UNITS -> 1
            units < -STALL_MIN_UNITS -> -1
            else -> 0
        }
        if (direction == 0 || direction != stallDirection || abs(tiltDeg - stallRefTilt) > STALL_MOVE_DEG) {
            stallDirection = direction
            stallRefTilt = tiltDeg
            stallRefMillis = nowMillis
            return
        }
        if (nowMillis - stallRefMillis >= STALL_MILLIS) {
            if (direction > 0) learnedUpLimit = tiltDeg - STALL_MARGIN_DEG else learnedDownLimit = tiltDeg + STALL_MARGIN_DEG
            stallRefMillis = nowMillis
        }
    }

    fun reset() {
        pan.reset()
        tilt.reset()
        lastMillis = 0L
        lastSeenMillis = 0L
    }

    private companion object {
        const val NOMINAL_DT = 1.0 / 30.0
        const val MIN_DT = 0.005
        const val MAX_DT = 0.2
        const val SOFT_ZONE_DEG = 8.0
        const val LOST_GRACE_MS = 250L
        const val STALL_MILLIS = 1_500L
        const val STALL_MIN_UNITS = 40.0
        const val STALL_MOVE_DEG = 0.5
        const val STALL_MARGIN_DEG = 0.5
    }
}
