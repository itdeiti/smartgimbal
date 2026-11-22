package dev.smartgimbal.tracking

/**
 * Decides which face the gimbal follows.
 *
 * Stays on the current target, matched by tracking ID or, for backends without IDs, by box
 * overlap with its last position. If the target disappears, it waits [lostTimeoutMillis] for it to
 * come back (another person walking through must not steal the gimbal) and then switches to the
 * largest face in view. Not thread-safe; call from the analysis thread.
 */
class TargetSelector(
    private val lostTimeoutMillis: Long = 1_000,
    private val minIou: Float = 0.3f,
) {
    private class Target(val trackingId: Int?, val box: NormBox, val lastSeenMillis: Long)

    private var target: Target? = null

    /** Returns the face to follow in this frame, or null when the target is not visible. */
    fun update(faces: List<DetectedFace>, nowMillis: Long): DetectedFace? {
        val current = target
        val match = current?.let { findMatch(it, faces) }
        val chosen = when {
            match != null -> match
            current == null || nowMillis - current.lastSeenMillis > lostTimeoutMillis ->
                faces.maxByOrNull { it.box.area }
            else -> null // target briefly lost, keep waiting for it
        }
        if (chosen != null) target = Target(chosen.trackingId, chosen.box, nowMillis)
        return chosen
    }

    fun reset() {
        target = null
    }

    private fun findMatch(current: Target, faces: List<DetectedFace>): DetectedFace? {
        current.trackingId?.let { id ->
            faces.firstOrNull { it.trackingId == id }?.let { return it }
        }
        return faces
            .map { it to it.box.iou(current.box) }
            .filter { it.second >= minIou }
            .maxByOrNull { it.second }
            ?.first
    }
}
