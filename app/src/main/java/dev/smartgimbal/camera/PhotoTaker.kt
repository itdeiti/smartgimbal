package dev.smartgimbal.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.VibrationEffect
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import dev.smartgimbal.AppLog
import dev.smartgimbal.vibrate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PhotoState(
    /** Seconds left on the self-timer (… 2, 1, then 0 while shooting), null when idle. */
    val countdown: Int? = null,
    /** Short note after a photo, e.g. where it was saved. */
    val message: String? = null,
)

/**
 * Photo with a self-timer, without touching the video setup.
 *
 * The camera runs preview + video (4K needs that pair alone). For a photo the controller switches
 * to image capture at the start of the countdown, so the rebind is hidden in the countdown, takes
 * the picture at 0 and switches back to video afterwards. Without a countdown the picture simply
 * waits for the rebind. Main thread only; never used while a
 * recording runs (the voice parser and the UI prevent it).
 */
class PhotoTaker(private val context: Context) {

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    private var job: Job? = null

    val isCountingDown: Boolean get() = job?.isActive == true

    /**
     * Takes a photo after [countdownSeconds] (0 = at once). [zoomRatio] is re-applied after each
     * mode switch, because the controller rebinds the camera.
     */
    fun start(scope: CoroutineScope, controller: CameraController, countdownSeconds: Int, zoomRatio: () -> Float) {
        if (isCountingDown) return
        job = scope.launch {
            try {
                switchTo(CameraController.IMAGE_CAPTURE, controller, zoomRatio)
                for (s in countdownSeconds downTo 1) {
                    _state.value = PhotoState(countdown = s)
                    vibrate(if (s == 1) LONG_TICK_MS else SHORT_TICK_MS)
                    delay(1_000)
                }
                _state.value = PhotoState(countdown = 0)
                val uri = takePicture(controller)
                _state.value = PhotoState(message = describe(uri))
            } catch (e: CancellationException) {
                _state.value = PhotoState(message = "Selbstauslöser abgebrochen")
                AppLog.d { "PHOTO cancelled" }
                throw e
            } catch (e: Exception) {
                _state.value = PhotoState(message = "Foto fehlgeschlagen: ${e.message}")
                AppLog.w("PHOTO failed", e)
            } finally {
                switchTo(CameraController.VIDEO_CAPTURE, controller, zoomRatio)
                _state.update { it.copy(countdown = null) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** The controller rebinds the camera on a mode switch, which resets the zoom. */
    private fun switchTo(useCases: Int, controller: CameraController, zoomRatio: () -> Float) {
        controller.setEnabledUseCases(useCases)
        controller.setZoomRatio(zoomRatio())
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private suspend fun takePicture(controller: CameraController): Uri = suspendCancellableCoroutine { cont ->
        val values = mediaValues(newMediaName(), "image/jpeg", "Pictures/SmartGimbal")
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

    /** Reads the saved photo's size (file access, so off the main thread) and logs it. */
    private suspend fun describe(uri: Uri): String = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        AppLog.d { "PHOTO done ${options.outWidth}x${options.outHeight} $uri" }
        "Foto gespeichert: ${options.outWidth}×${options.outHeight}"
    }

    private fun vibrate(millis: Long) =
        context.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))

    private companion object {
        const val SHORT_TICK_MS = 40L
        const val LONG_TICK_MS = 120L
    }
}
