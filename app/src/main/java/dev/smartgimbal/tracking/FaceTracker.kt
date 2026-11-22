package dev.smartgimbal.tracking

import dev.smartgimbal.gimbal.MoveCommand
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
        val tiltUnits = limitTowardsEndStop(tilt.update(tiltError, dt, params), tiltDeg, params)
        return TrackingOutput(MoveCommand(panUnits.roundToInt(), tiltUnits.roundToInt()), panError, tiltError)
    }

    /**
     * Soft end stop: within [SOFT_ZONE_DEG] of a tilt limit the speed towards it shrinks with the
     * remaining room. A hard cut at the limit would overrun by several degrees because of the
     * loop latency, and telemetry only arrives about 2.4 times per second.
     */
    private fun limitTowardsEndStop(units: Double, tiltDeg: Double?, params: TrackingParams): Double {
        if (tiltDeg == null || units == 0.0) return units
        val room = if (units > 0) params.tiltUpLimitDeg - tiltDeg else tiltDeg - params.tiltDownLimitDeg
        if (room <= 0) return 0.0
        val cap = params.maxUnits * (room / SOFT_ZONE_DEG).coerceAtMost(1.0)
        return units.coerceIn(-cap, cap)
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
    }
}
