package dev.smartgimbal.tracking

import androidx.camera.core.ImageProxy
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/**
 * Axis-aligned box in the upright analysis frame, normalised to 0..1 (x to the right, y down).
 * Plain Kotlin instead of android.graphics.RectF so the tracking logic runs in unit tests.
 */
data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Intersection over union, 0 when the boxes do not overlap. */
    fun iou(other: NormBox): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (area + other.area - inter)
    }
}

/** One detected face. [trackingId] is null for backends without tracking (e.g. MediaPipe). */
data class DetectedFace(val trackingId: Int?, val box: NormBox)

/**
 * Face detection backend. Implementations: ML Kit (CPU, default); MediaPipe with GPU delegate
 * is the planned fallback if CPU load or heat becomes a problem (see docs/phase-2-plan.md).
 */
interface FaceDetector : Closeable {
    val name: String

    /**
     * Detects faces in [image], blocking. Called on the analysis thread; the caller closes the
     * image. Boxes are in the upright frame (image rotation applied), normalised to 0..1.
     */
    fun detect(image: ImageProxy): List<DetectedFace>
}
