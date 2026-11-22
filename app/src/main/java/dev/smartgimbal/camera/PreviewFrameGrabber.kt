package dev.smartgimbal.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.view.TextureView
import androidx.camera.view.PreviewView
import dev.smartgimbal.AppLog
import dev.smartgimbal.RollingAverage
import dev.smartgimbal.tracking.FacePipeline

/**
 * Copies the visible preview into small bitmaps for face detection.
 *
 * Why: on the Pixel 7, preview + 4K video + a YUV analysis stream is not a supported camera
 * stream combination (CameraX falls back to 1080p, and forcing UHD with stream sharing leaves the
 * camera black). Preview + 4K video alone works, so the analysis reads the preview instead.
 *
 * How: PreviewView in COMPATIBLE mode renders into a TextureView. [TextureView.getBitmap] at a
 * quarter of the size costs about 3 ms on the main thread (measured; PreviewView.getBitmap at full
 * size took 50-90 ms). At this size ML Kit needs ~15 ms, so ~23 frames per second get analysed. The raw copy is in the
 * TextureView's own coordinates; PreviewView fits it into place with view properties (scale,
 * rotation, translation), so the same transform is applied here. The result matches what the
 * user sees, including the front camera mirroring.
 *
 * Main thread only.
 */
class PreviewFrameGrabber(private val pipeline: FacePipeline) {

    private var raw: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val copyMillisAvg = RollingAverage()
    private var count = 0L

    /** Grabs one frame and submits it; skips if the analysis is still busy or the view is not ready. */
    fun grab(previewView: PreviewView, mirrored: Boolean) {
        if (pipeline.isBusy) return
        val texture = findTextureView(previewView) ?: return
        // Without a surface getBitmap() leaves the bitmap untouched: the previous frame would be
        // analysed again and the tracker would keep steering towards a frozen picture.
        if (!texture.isAvailable) return
        if (texture.width == 0 || texture.height == 0 || previewView.width == 0 || previewView.height == 0) return
        val start = SystemClock.elapsedRealtimeNanos()

        val rawW = texture.width / SCALE_DOWN
        val rawH = texture.height / SCALE_DOWN
        val rawBitmap = raw?.takeIf { it.width == rawW && it.height == rawH }
            ?: Bitmap.createBitmap(rawW, rawH, Bitmap.Config.ARGB_8888).also { raw?.recycle(); raw = it }
        texture.getBitmap(rawBitmap)

        val outW = previewView.width / SCALE_DOWN
        val outH = previewView.height / SCALE_DOWN
        val out = pipeline.obtainBitmap(outW, outH)
        val k = rawW.toFloat() / texture.width
        matrix.reset()
        matrix.postScale(1 / k, 1 / k) // raw pixels -> TextureView local
        matrix.postConcat(texture.getTransform(null)) // texture transform inside the view
        matrix.postConcat(texture.matrix) // view scale / rotation / translation
        matrix.postTranslate(texture.left.toFloat(), texture.top.toFloat()) // -> PreviewView
        matrix.postScale(outW.toFloat() / previewView.width, outH.toFloat() / previewView.height)
        out.eraseColor(0)
        Canvas(out).drawBitmap(rawBitmap, matrix, paint)

        pipeline.submit(out, mirrored)
        logCost((SystemClock.elapsedRealtimeNanos() - start) / 1e6f)
    }

    fun release() {
        raw?.recycle()
        raw = null
    }

    private fun findTextureView(previewView: PreviewView): TextureView? {
        for (i in 0 until previewView.childCount) (previewView.getChildAt(i) as? TextureView)?.let { return it }
        return null
    }

    private fun logCost(millis: Float) {
        val avg = copyMillisAvg.add(millis)
        if (++count % LOG_EVERY_N == 0L) AppLog.d { "GRAB avg=%.1fms".format(java.util.Locale.ROOT, avg) }
    }

    private companion object {
        const val SCALE_DOWN = 4
        const val LOG_EVERY_N = 60L
    }
}
