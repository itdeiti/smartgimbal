package dev.smartgimbal

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartgimbal.ble.LinkState
import dev.smartgimbal.gimbal.GimbalSession
import dev.smartgimbal.tracking.CameraFovReader
import dev.smartgimbal.tracking.FaceFrame
import dev.smartgimbal.tracking.FacePipeline
import dev.smartgimbal.tracking.FaceTracker
import dev.smartgimbal.tracking.Fov
import dev.smartgimbal.tracking.NormBox
import dev.smartgimbal.tracking.TrackingOutput
import dev.smartgimbal.tracking.TrackingSettings
import dev.smartgimbal.voice.VoiceCommand
import dev.smartgimbal.voice.VoiceContext
import dev.smartgimbal.voice.VoiceControl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Activity-scoped owner of the long-lived parts: the [GimbalSession] (BLE link survives screen
 * switches), the [FacePipeline] (detector is not rebuilt each time the camera screen opens) and
 * the face tracker that connects the two.
 */
class GimbalViewModel(app: Application) : AndroidViewModel(app) {

    val session = GimbalSession(app, viewModelScope)
    val faces = FacePipeline()
    val trackingSettings = TrackingSettings(app)

    private val _trackingEnabled = MutableStateFlow(false)
    val trackingEnabled: StateFlow<Boolean> = _trackingEnabled.asStateFlow()

    /** Latest tracker output for the overlay, null while tracking is off. */
    private val _trackingOutput = MutableStateFlow<TrackingOutput?>(null)
    val trackingOutput: StateFlow<TrackingOutput?> = _trackingOutput.asStateFlow()

    private class CameraSetup(val front: Boolean, val fov: Fov)

    @Volatile private var camera = CameraSetup(front = false, fov = Fov.DEFAULT)
    @Volatile private var resetTracker = false
    private val tracker = FaceTracker()
    private var lastLearnedLimits: Pair<Double?, Double?> = null to null

    @Volatile private var recordingActive = false
    @Volatile private var photoCountdownActive = false

    /** Wide angle (ultrawide lens via zoom ratio < 1) requested; the camera screen applies it. */
    private val _wideAngle = MutableStateFlow(false)
    val wideAngle: StateFlow<Boolean> = _wideAngle.asStateFlow()

    /** Zoom ratio actually applied by the camera screen; scales the tracker's field of view. */
    @Volatile private var zoomRatio = 1.0

    /** Camera commands (record, stop, photo) go to the camera screen, which owns the camera. */
    private val _cameraCommands = MutableSharedFlow<VoiceCommand>(extraBufferCapacity = 4)
    val cameraCommands: SharedFlow<VoiceCommand> = _cameraCommands.asSharedFlow()

    val voice = VoiceControl(
        context = app,
        scope = viewModelScope,
        contextProvider = {
            VoiceContext(
                recording = recordingActive,
                gimbalReady = session.client.state.value == LinkState.READY,
                trackingOn = _trackingEnabled.value,
                photoCountdown = photoCountdownActive,
                wideAngle = _wideAngle.value,
                frontCamera = camera.front,
            )
        },
        onCommand = ::onVoiceCommand,
    )

    init {
        faces.frameListener = ::onFaceFrame
    }

    /** The camera screen reports whether a recording is running (voice commands depend on it). */
    fun setRecording(active: Boolean) {
        recordingActive = active
    }

    /** The camera screen reports whether the photo self-timer is counting down. */
    fun setPhotoCountdown(active: Boolean) {
        photoCountdownActive = active
    }

    fun setWideAngle(enabled: Boolean) {
        if (enabled && camera.front) return
        _wideAngle.value = enabled
        Log.d(GimbalSession.LOG_TAG, "ZOOM wide=$enabled")
    }

    /** Called by the camera screen after it applied a zoom ratio. */
    fun setZoomRatio(ratio: Double) {
        zoomRatio = ratio
        resetTracker = true
        Log.d(GimbalSession.LOG_TAG, "ZOOM ratio=%.2f".format(Locale.ROOT, ratio))
    }

    private fun onVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.START_RECORDING, VoiceCommand.STOP, VoiceCommand.PHOTO -> _cameraCommands.tryEmit(command)
            VoiceCommand.TRACKING_ON -> setTrackingEnabled(true)
            VoiceCommand.TRACKING_OFF -> setTrackingEnabled(false)
            VoiceCommand.HOME -> session.home()
            VoiceCommand.WIDE_ON -> setWideAngle(true)
            VoiceCommand.WIDE_OFF -> setWideAngle(false)
        }
    }

    /** Called by the camera screen whenever the active camera changes. */
    fun setCamera(front: Boolean) {
        val fov = CameraFovReader.read(getApplication(), front) ?: Fov.DEFAULT
        camera = CameraSetup(front, fov)
        if (front) _wideAngle.value = false // the front camera has no ultrawide lens
        resetTracker = true
        Log.d(GimbalSession.LOG_TAG, "CAMERA front=$front fov=%.1f/%.1f".format(Locale.ROOT, fov.longDeg, fov.shortDeg))
    }

    fun setTrackingEnabled(enabled: Boolean) {
        _trackingEnabled.value = enabled
        resetTracker = true
        if (!enabled) {
            session.setTrackerCommand(null)
            _trackingOutput.value = null
        }
        Log.d(GimbalSession.LOG_TAG, "TRACKING ${if (enabled) "an" else "aus"}")
    }

    /** Runs on the analysis thread for every frame. */
    private fun onFaceFrame(frame: FaceFrame) {
        if (resetTracker) {
            tracker.reset()
            resetTracker = false
        }
        if (!_trackingEnabled.value || session.client.state.value != LinkState.READY) return

        val setup = camera
        val tiltDeg = session.telemetry.value?.let { it.tilt / 100.0 }
        // Faces arrive in display coordinates; the front preview is mirrored, the tracker's
        // geometry is not.
        val box = frame.target?.box?.let { if (frame.mirrored) NormBox(1 - it.right, it.top, 1 - it.left, it.bottom) else it }
        val output = tracker.update(
            target = box,
            imageWidth = frame.imageWidth,
            imageHeight = frame.imageHeight,
            fov = setup.fov.zoomed(zoomRatio),
            frontCamera = setup.front,
            tiltDeg = tiltDeg,
            nowMillis = frame.timestampMillis,
            params = trackingSettings.params.value,
        )
        session.setTrackerCommand(output.command)
        _trackingOutput.value = output
        if (tracker.learnedTiltLimits != lastLearnedLimits) {
            lastLearnedLimits = tracker.learnedTiltLimits
            Log.d(GimbalSession.LOG_TAG, "TILT end stops learned up=${lastLearnedLimits.first} down=${lastLearnedLimits.second}")
        }
        Log.d(
            GimbalSession.LOG_TAG,
            "TRACK errPan=%.2f errTilt=%.2f cmdPan=%d cmdTilt=%d tilt=%s".format(
                Locale.ROOT, output.panErrorDeg, output.tiltErrorDeg,
                output.command.panRight, output.command.tiltUp,
                tiltDeg?.let { "%.1f".format(Locale.ROOT, it) } ?: "-",
            ),
        )
    }

    override fun onCleared() {
        voice.close()
        faces.frameListener = null
        session.close()
        faces.close()
    }
}
