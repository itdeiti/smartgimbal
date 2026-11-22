package dev.smartgimbal.camera

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.video.AudioConfig
import dev.smartgimbal.AppLog
import dev.smartgimbal.hasMicPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RecordingState(
    val recording: Boolean = false,
    val durationMillis: Long = 0,
    /** Short user-facing note after a recording ends, e.g. where it was saved. */
    val message: String? = null,
    /** PowerManager thermal status while recording, see [PowerManager.getCurrentThermalStatus]. */
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
)

/**
 * Records video through the camera controller into `Movies/SmartGimbal`.
 *
 * After each recording the file is checked with MediaMetadataRetriever and the real resolution,
 * duration and frame rate are logged, so it is visible whether 4K/30 was actually achieved next
 * to preview and face analysis. Thermal status is logged during recording.
 */
class VideoRecorder(private val context: Context) {

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var recording: Recording? = null
    private var lastThermalLogMillis = 0L
    private val powerManager = context.getSystemService(PowerManager::class.java)

    val isRecording: Boolean get() = recording != null

    fun toggle(controller: CameraController) {
        if (recording != null) stop() else start(controller)
    }

    @SuppressLint("MissingPermission") // audio is only enabled after the permission check below
    fun start(controller: CameraController) {
        if (recording != null) return
        val name = newMediaName()
        val values = mediaValues(name, "video/mp4", "Movies/SmartGimbal")
        val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        val withAudio = context.hasMicPermission
        recording = try {
            controller.startRecording(output, AudioConfig.create(withAudio), context.mainExecutor, ::onEvent)
        } catch (e: Exception) {
            log("REC start failed: ${e.message}")
            _state.update { it.copy(message = "Aufnahme nicht gestartet: ${e.message}") }
            null
        }
        if (recording != null) log("REC start $name audio=$withAudio")
    }

    fun stop() {
        recording?.stop()
    }

    private fun onEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> _state.value = RecordingState(recording = true)
            is VideoRecordEvent.Status -> {
                val millis = TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos)
                val thermal = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                _state.update { it.copy(durationMillis = millis, thermalStatus = thermal) }
                if (millis - lastThermalLogMillis >= THERMAL_LOG_INTERVAL_MS) {
                    lastThermalLogMillis = millis
                    log("REC t=${millis / 1000}s bytes=${event.recordingStats.numBytesRecorded} thermal=$thermal")
                }
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                lastThermalLogMillis = 0
                val uri = event.outputResults.outputUri
                if (event.hasError()) log("REC error=${event.error} ${event.cause?.message}")
                // Some errors (e.g. the camera closing, SOURCE_INACTIVE) still leave a valid file.
                if (uri != Uri.EMPTY) {
                    _state.value = RecordingState(message = "Gespeichert in Movies/SmartGimbal")
                    inspect(uri)
                } else {
                    _state.value = RecordingState(message = "Aufnahme fehlgeschlagen (Fehler ${event.error})")
                }
            }
            else -> Unit
        }
    }

    /** Reads the finished file's real properties on a background thread and logs them. */
    private fun inspect(uri: Uri) {
        Thread {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                fun meta(key: Int) = retriever.extractMetadata(key)
                val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val frames = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0
                val fps = if (durationMs > 0) frames * 1000.0 / durationMs else 0.0
                val audio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                log("REC done ${width}x$height ${durationMs}ms frames=$frames fps=%.1f audio=$audio".format(Locale.ROOT, fps))
                _state.update { it.copy(message = "Gespeichert: ${width}×$height, %.0f fps".format(Locale.GERMANY, fps)) }
            } catch (e: Exception) {
                log("REC inspect failed: ${e.message}")
            } finally {
                retriever.release()
            }
        }.start()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun log(text: String) = AppLog.d { text }

    private companion object {
        const val THERMAL_LOG_INTERVAL_MS = 5_000L
    }
}
