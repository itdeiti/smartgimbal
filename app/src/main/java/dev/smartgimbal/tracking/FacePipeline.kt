package dev.smartgimbal.tracking

import android.graphics.Bitmap
import android.os.SystemClock
import dev.smartgimbal.AppLog
import dev.smartgimbal.RollingAverage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Result of one analysed frame. */
class FaceFrame(
    /** Faces in display coordinates: normalised over the visible preview, as the user sees it. */
    val faces: List<DetectedFace>,
    /** The face the gimbal should follow, null if the target is not visible. */
    val target: DetectedFace?,
    /** Analysed frame size in pixels (portrait or landscape like the preview). */
    val imageWidth: Int,
    val imageHeight: Int,
    /** True if the preview is mirrored (front camera); the tracker un-mirrors x. */
    val mirrored: Boolean,
    val timestampMillis: Long,
)

/** Rolling performance numbers, shown on screen to decide whether ML Kit is fast enough. */
data class AnalysisStats(val detectorName: String, val avgMillis: Float, val fps: Float)

/**
 * Preview copies to faces to target.
 *
 * Frames come from [dev.smartgimbal.camera.PreviewFrameGrabber] instead of a CameraX
 * ImageAnalysis stream: on the Pixel 7 a third camera stream next to preview and 4K video is not
 * a supported combination, so the analysis taps the preview that is already there.
 *
 * Owns the detector, its thread and a small bitmap pool. Lives in the ViewModel so the detector
 * is not rebuilt on screen changes.
 */
class FacePipeline(private val detector: FaceDetector = MlKitFaceDetector()) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "face-analysis") }
    private val busy = AtomicBoolean(false)
    private val pool = ArrayBlockingQueue<Bitmap>(POOL_SIZE)

    private val selector = TargetSelector()

    private val _frame = MutableStateFlow<FaceFrame?>(null)
    val frame: StateFlow<FaceFrame?> = _frame.asStateFlow()

    private val _stats = MutableStateFlow(AnalysisStats(detector.name, 0f, 0f))
    val stats: StateFlow<AnalysisStats> = _stats.asStateFlow()

    /** Called on the analysis thread after every frame; the tracker hooks in here for low latency. */
    @Volatile var frameListener: ((FaceFrame) -> Unit)? = null

    @Volatile private var resetRequested = false
    private val avgMillis = RollingAverage()
    private val avgIntervalMillis = RollingAverage()
    private var lastFrameMillis = 0L
    private var frameCount = 0L

    /** True while a frame is being analysed; the grabber skips copying then. */
    val isBusy: Boolean get() = busy.get()

    /** A bitmap of the given size to copy the next frame into. */
    fun obtainBitmap(width: Int, height: Int): Bitmap {
        val pooled = pool.poll()
        if (pooled != null && pooled.width == width && pooled.height == height) return pooled
        pooled?.recycle() // size changed, e.g. after rotation
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Hands a frame to the analysis thread. Returns false (and takes the bitmap back into the pool)
     * if the previous frame is still being analysed.
     */
    fun submit(bitmap: Bitmap, mirrored: Boolean): Boolean {
        if (!busy.compareAndSet(false, true)) {
            release(bitmap)
            return false
        }
        executor.execute {
            try {
                analyze(bitmap, mirrored)
            } finally {
                release(bitmap)
                busy.set(false)
            }
        }
        return true
    }

    /** Forget the target, e.g. after switching cameras (tracking IDs are per camera). */
    fun reset() {
        resetRequested = true
        _frame.value = null
    }

    private fun release(bitmap: Bitmap) {
        if (!pool.offer(bitmap)) bitmap.recycle()
    }

    private fun analyze(bitmap: Bitmap, mirrored: Boolean) {
        if (resetRequested) {
            selector.reset()
            resetRequested = false
        }
        val start = SystemClock.elapsedRealtime()
        val faces = try {
            detector.detect(bitmap)
        } catch (e: Exception) {
            AppLog.w("Face detection failed", e)
            emptyList()
        }
        val now = SystemClock.elapsedRealtime()
        val result = FaceFrame(
            faces = faces,
            target = selector.update(faces, now),
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            mirrored = mirrored,
            timestampMillis = now,
        )
        _frame.value = result
        frameListener?.invoke(result)
        updateStats(analysisMillis = now - start, now = now)
        logFrame(result, now - start)
    }

    private fun updateStats(analysisMillis: Long, now: Long) {
        avgMillis.add(analysisMillis.toFloat())
        if (lastFrameMillis != 0L) avgIntervalMillis.add((now - lastFrameMillis).toFloat())
        lastFrameMillis = now
        frameCount++
        if (frameCount % STATS_EVERY_N_FRAMES == 0L) {
            val fps = if (avgIntervalMillis.value > 0f) 1000f / avgIntervalMillis.value else 0f
            _stats.value = AnalysisStats(detector.name, avgMillis.value, fps)
        }
    }

    /** One line per frame in logcat, for tuning from recorded data. */
    private fun logFrame(frame: FaceFrame, analysisMillis: Long) {
        AppLog.d {
            val t = frame.target
            val targetText = if (t == null) "-" else
                "id=%s cx=%.3f cy=%.3f w=%.3f".format(Locale.ROOT, t.trackingId, t.box.centerX, t.box.centerY, t.box.width)
            "FACE n=${frame.faces.size} ms=$analysisMillis ${frame.imageWidth}x${frame.imageHeight} target=$targetText"
        }
    }

    fun close() {
        // Queued behind a frame that may still be in analysis, so the detector is never used after close.
        executor.execute { detector.close() }
        executor.shutdown()
    }

    private companion object {
        const val STATS_EVERY_N_FRAMES = 10L
        const val POOL_SIZE = 3
    }
}
