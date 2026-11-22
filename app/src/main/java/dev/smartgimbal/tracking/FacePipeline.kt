package dev.smartgimbal.tracking

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import dev.smartgimbal.gimbal.GimbalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Result of one analysed camera frame. */
class FaceFrame(
    val faces: List<DetectedFace>,
    /** The face the gimbal should follow, null if the target is not visible. */
    val target: DetectedFace?,
    /** Upright analysis frame size in pixels; [DetectedFace.box] is normalised to it. */
    val imageWidth: Int,
    val imageHeight: Int,
    /** Maps upright analysis pixels to other CameraX outputs, e.g. the preview (overlay). */
    val sourceTransform: OutputTransform,
    val timestampMillis: Long,
)

/** Rolling performance numbers, shown on screen to decide whether ML Kit is fast enough. */
data class AnalysisStats(val detectorName: String, val avgMillis: Float, val fps: Float)

/**
 * Camera frames to faces to target. Owns the detector and its thread; [analyzer] is handed to
 * the camera controller. Lives in the ViewModel so the detector is not rebuilt on screen changes.
 */
class FacePipeline(private val detector: FaceDetector = MlKitFaceDetector()) {

    val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "face-analysis") }

    private val selector = TargetSelector()
    private val transformFactory = ImageProxyTransformFactory().apply { isUsingRotationDegrees = true }

    private val _frame = MutableStateFlow<FaceFrame?>(null)
    val frame: StateFlow<FaceFrame?> = _frame.asStateFlow()

    private val _stats = MutableStateFlow(AnalysisStats(detector.name, 0f, 0f))
    val stats: StateFlow<AnalysisStats> = _stats.asStateFlow()

    /** Called on the analysis thread after every frame; the tracker hooks in here for low latency. */
    @Volatile var frameListener: ((FaceFrame) -> Unit)? = null

    @Volatile private var resetRequested = false
    private var avgMillis = 0f
    private var avgIntervalMillis = 0f
    private var lastFrameMillis = 0L
    private var frameCount = 0L

    val analyzer = ImageAnalysis.Analyzer { image -> image.use(::analyze) }

    /** Forget the target, e.g. after switching cameras (tracking IDs are per camera). */
    fun reset() {
        resetRequested = true
        _frame.value = null
    }

    private fun analyze(image: ImageProxy) {
        if (resetRequested) {
            selector.reset()
            resetRequested = false
        }
        val start = SystemClock.elapsedRealtime()
        val faces = try {
            detector.detect(image)
        } catch (e: Exception) {
            Log.w(GimbalSession.LOG_TAG, "Face detection failed", e)
            emptyList()
        }
        val now = SystemClock.elapsedRealtime()
        val target = selector.update(faces, now)

        val rotation = image.imageInfo.rotationDegrees
        val result = FaceFrame(
            faces = faces,
            target = target,
            imageWidth = if (rotation % 180 == 0) image.width else image.height,
            imageHeight = if (rotation % 180 == 0) image.height else image.width,
            sourceTransform = transformFactory.getOutputTransform(image),
            timestampMillis = now,
        )
        _frame.value = result
        frameListener?.invoke(result)
        updateStats(analysisMillis = now - start, now = now)
        logFrame(result, now - start)
    }

    private fun updateStats(analysisMillis: Long, now: Long) {
        avgMillis = if (frameCount == 0L) analysisMillis.toFloat() else avgMillis * 0.9f + analysisMillis * 0.1f
        if (lastFrameMillis != 0L) {
            val interval = (now - lastFrameMillis).toFloat()
            avgIntervalMillis = if (avgIntervalMillis == 0f) interval else avgIntervalMillis * 0.9f + interval * 0.1f
        }
        lastFrameMillis = now
        frameCount++
        if (frameCount % STATS_EVERY_N_FRAMES == 0L) {
            val fps = if (avgIntervalMillis > 0f) 1000f / avgIntervalMillis else 0f
            _stats.value = AnalysisStats(detector.name, avgMillis, fps)
        }
    }

    /** One line per frame in logcat, for tuning from recorded data. */
    private fun logFrame(frame: FaceFrame, analysisMillis: Long) {
        val t = frame.target
        val targetText = if (t == null) "-" else
            "id=%s cx=%.3f cy=%.3f w=%.3f".format(Locale.ROOT, t.trackingId, t.box.centerX, t.box.centerY, t.box.width)
        Log.d(
            GimbalSession.LOG_TAG,
            "FACE n=${frame.faces.size} ms=$analysisMillis ${frame.imageWidth}x${frame.imageHeight} target=$targetText",
        )
    }

    fun close() {
        // Queued behind a frame that may still be in analysis, so the detector is never used after close.
        executor.execute { detector.close() }
        executor.shutdown()
    }

    private companion object {
        const val STATS_EVERY_N_FRAMES = 10L
    }
}
