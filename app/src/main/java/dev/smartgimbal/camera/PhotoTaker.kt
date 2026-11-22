package dev.smartgimbal.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import dev.smartgimbal.gimbal.GimbalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PhotoState(
    /** Seconds left on the self-timer (3, 2, 1, then 0 while shooting), null when idle. */
    val countdown: Int? = null,
    /** Short note after a photo, e.g. where it was saved. */
    val message: String? = null,
)

/**
 * Photo with a self-timer, without touching the video setup.
 *
 * The camera runs preview + video (4K needs that pair alone). For a photo the controller switches
 * to image capture at the start of the countdown, so the rebind is hidden in the 3 seconds, takes
 * the picture at 0 and switches back to video afterwards. Main thread only; never used while a
 * recording runs (the voice parser and the UI prevent it).
 */
class PhotoTaker(private val context: Context) {

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    private var job: Job? = null

    val isCountingDown: Boolean get() = job?.isActive == true

    /**
     * Starts the self-timer. [zoomRatio] is re-applied after each mode switch, because the
     * controller rebinds the camera.
     */
    fun start(scope: CoroutineScope, controller: CameraController, zoomRatio: () -> Float) {
        if (isCountingDown) return
        job = scope.launch {
            try {
                controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                controller.setZoomRatio(zoomRatio())
                for (s in COUNTDOWN_SECONDS downTo 1) {
                    _state.value = PhotoState(countdown = s)
                    vibrate(if (s == 1) LONG_TICK_MS else SHORT_TICK_MS)
                    delay(1_000)
                }
                _state.value = PhotoState(countdown = 0)
                val uri = takePicture(controller)
                _state.value = PhotoState(message = describe(uri))
            } catch (e: CancellationException) {
                _state.value = PhotoState(message = "Selbstauslöser abgebrochen")
                Log.d(GimbalSession.LOG_TAG, "PHOTO cancelled")
                throw e
            } catch (e: Exception) {
                _state.value = PhotoState(message = "Foto fehlgeschlagen: ${e.message}")
                Log.w(GimbalSession.LOG_TAG, "PHOTO failed", e)
            } finally {
                controller.setEnabledUseCases(CameraController.VIDEO_CAPTURE)
                controller.setZoomRatio(zoomRatio())
                _state.update { it.copy(countdown = null) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private suspend fun takePicture(controller: CameraController): Uri = suspendCancellableCoroutine { cont ->
        val name = "SmartGimbal_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartGimbal")
        }
        val output = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .build()
        controller.takePicture(
            output,
            context.mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val uri = result.savedUri
                    if (uri != null) cont.resume(uri) else cont.resumeWithException(IllegalStateException("kein Speicherort"))
                }

                override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
            },
        )
    }

    /** Reads the saved photo's size and logs it, like the video check. */
    private fun describe(uri: Uri): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        Log.d(GimbalSession.LOG_TAG, "PHOTO done ${options.outWidth}x${options.outHeight} $uri")
        return "Foto gespeichert: ${options.outWidth}×${options.outHeight}"
    }

    private fun vibrate(millis: Long) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val COUNTDOWN_SECONDS = 3
        const val SHORT_TICK_MS = 40L
        const val LONG_TICK_MS = 120L
    }
}
