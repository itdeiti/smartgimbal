package dev.smartgimbal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartgimbal.ble.LinkState
import android.content.Context
import dev.smartgimbal.camera.PhotoTimer
import dev.smartgimbal.camera.ZoomLevel
import dev.smartgimbal.gimbal.GimbalSession
import dev.smartgimbal.protocol.ButtonPress
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
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Activity-scoped owner of the long-lived parts: the [GimbalSession] (BLE link survives screen
 * switches), the [FacePipeline] (detector is not rebuilt each time the camera screen opens) and
 * the face tracker that connects the two.
 */
class GimbalViewModel(app: Application) : AndroidViewModel(app) {

    val session = GimbalSession(app, viewModelScope)
    val faces = FacePipeline()
    val trackingSettings = TrackingSettings(app)

    /** Self-timer for photos (off, 3 s, 10 s), kept across restarts. */
    val photoTimer = PersistedParams(
        prefs = app.getSharedPreferences("camera", Context.MODE_PRIVATE),
        load = { prefs -> PhotoTimer.entries.firstOrNull { it.name == prefs.getString(KEY_PHOTO_TIMER, null) } ?: PhotoTimer.S3 },
        save = { timer -> putString(KEY_PHOTO_TIMER, timer.name) },
    )

    private val _trackingEnabled = MutableStateFlow(false)
    val trackingEnabled: StateFlow<Boolean> = _trackingEnabled.asStateFlow()

    /** Latest tracker output for the overlay, null while tracking is off. */
    private val _trackingOutput = MutableStateFlow<TrackingOutput?>(null)
    val trackingOutput: StateFlow<TrackingOutput?> = _trackingOutput.asStateFlow()

    private class CameraSetup(val front: Boolean, val fov: Fov)

    @Volatile private var camera = CameraSetup(front = false, fov = Fov.DEFAULT)
    /** Set from the main thread, consumed on the analysis thread; atomic so no request is lost. */
    private val resetTracker = AtomicBoolean(false)
    private val tracker = FaceTracker()
    private var lastLearnedLimits: Pair<Double?, Double?> = null to null

    /** Analysis thread only. */
    private var pausedByTrigger = false

    @Volatile private var recordingActive = false
    @Volatile private var photoCountdownActive = false

    /** Requested zoom step (0.7x ultrawide, 1x, 2x, 4x); the camera screen applies it. */
    private val _zoomLevel = MutableStateFlow(ZoomLevel.NORMAL)
    val zoomLevel: StateFlow<ZoomLevel> = _zoomLevel.asStateFlow()

    /** Zoom ratio actually applied by the camera screen; scales the tracker's field of view. */
    @Volatile private var zoomRatio = 1.0
    @Volatile private var maxZoom = 8f

    /** Camera commands (record, stop, photo) from voice or the gimbal's button go to the camera screen, which owns the camera. */
    private val _cameraCommands = MutableSharedFlow<VoiceCommand>(extraBufferCapacity = 4)
    val cameraCommands: SharedFlow<VoiceCommand> = _cameraCommands.asSharedFlow()

    /** The gimbal's thumb button asked to switch between front and rear camera. */
    private val _cameraToggles = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cameraToggles: SharedFlow<Unit> = _cameraToggles.asSharedFlow()

    val voice = VoiceControl(
        context = app,
        scope = viewModelScope,
        contextProvider = {
            VoiceContext(
                recording = recordingActive,
                gimbalReady = session.client.state.value == LinkState.READY,
                trackingOn = _trackingEnabled.value,
                photoCountdown = photoCountdownActive,
                zoom = _zoomLevel.value,
                frontCamera = camera.front,
                maxZoom = maxZoom,
            )
        },
        onCommand = ::onVoiceCommand,
    )

    init {
        faces.frameListener = ::onFaceFrame
        viewModelScope.launch { session.buttonPresses.collect(::onGimbalButton) }
    }

    /**
     * Thumb button on the gimbal: short = photo, long = start recording, double = switch camera.
     * While a recording or the photo countdown runs, a short or long press stops it and a double
     * press does nothing (no camera switch while the camera is busy, as on screen).
     * The camera screen carries the actions out, so nothing happens while another screen is open.
     */
    private fun onGimbalButton(press: ButtonPress) {
        val busy = recordingActive || photoCountdownActive
        AppLog.d { "BUTTON $press busy=$busy" }
        when (press) {
            ButtonPress.SHORT -> _cameraCommands.tryEmit(if (busy) VoiceCommand.STOP else VoiceCommand.PHOTO)
            ButtonPress.LONG -> _cameraCommands.tryEmit(if (busy) VoiceCommand.STOP else VoiceCommand.START_RECORDING)
            ButtonPress.DOUBLE -> if (!busy) _cameraToggles.tryEmit(Unit)
        }
    }

    /** The camera screen reports whether a recording is running (voice commands depend on it). */
    fun setRecording(active: Boolean) {
        recordingActive = active
    }

    /** The camera screen reports whether the photo self-timer is counting down. */
    fun setPhotoCountdown(active: Boolean) {
        photoCountdownActive = active
    }

    fun setZoomLevel(level: ZoomLevel) {
        if (level == ZoomLevel.WIDE && camera.front) return
        _zoomLevel.value = level
        AppLog.d { "ZOOM level=$level" }
    }

    /** Called by the camera screen after it applied a zoom ratio. */
    fun setZoomRatio(ratio: Double, max: Float) {
        zoomRatio = ratio
        maxZoom = max
        resetTracker.set(true)
        AppLog.d { "ZOOM ratio=%.2f".format(Locale.ROOT, ratio) }
    }

    private fun onVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.START_RECORDING, VoiceCommand.STOP, VoiceCommand.PHOTO -> _cameraCommands.tryEmit(command)
            VoiceCommand.TRACKING_ON -> setTrackingEnabled(true)
            VoiceCommand.TRACKING_OFF -> setTrackingEnabled(false)
            VoiceCommand.HOME -> session.home()
            VoiceCommand.WIDE_ON -> setZoomLevel(ZoomLevel.WIDE)
            VoiceCommand.WIDE_OFF, VoiceCommand.ZOOM_OFF -> setZoomLevel(ZoomLevel.NORMAL)
            VoiceCommand.ZOOM_2 -> setZoomLevel(ZoomLevel.X2)
            VoiceCommand.ZOOM_4 -> setZoomLevel(ZoomLevel.X4)
        }
    }

    /** Called by the camera screen whenever the active camera changes. */
    fun setCamera(front: Boolean) {
        val fov = CameraFovReader.read(getApplication(), front) ?: Fov.DEFAULT
        camera = CameraSetup(front, fov)
        if (front && _zoomLevel.value == ZoomLevel.WIDE) _zoomLevel.value = ZoomLevel.NORMAL // no ultrawide in front
        resetTracker.set(true)
        AppLog.d { "CAMERA front=$front fov=%.1f/%.1f".format(Locale.ROOT, fov.longDeg, fov.shortDeg) }
    }

    fun setTrackingEnabled(enabled: Boolean) {
        _trackingEnabled.value = enabled
        resetTracker.set(true)
        if (!enabled) {
            session.setTrackerCommand(null)
            _trackingOutput.value = null
        }
        AppLog.d { "TRACKING ${if (enabled) "an" else "aus"}" }
    }

    /** Runs on the analysis thread for every frame. */
    private fun onFaceFrame(frame: FaceFrame) {
        if (resetTracker.getAndSet(false)) tracker.reset()
        if (!_trackingEnabled.value || session.client.state.value != LinkState.READY) return

        // Holding the trigger is the user taking over on the handle: stand back instead of
        // steering against it, and start fresh on release.
        if (session.triggerHeld.value) {
            if (!pausedByTrigger) {
                pausedByTrigger = true
                session.setTrackerCommand(null)
                tracker.reset()
                _trackingOutput.value = null
                AppLog.d { "TRACKING paused, trigger held" }
            }
            return
        }
        if (pausedByTrigger) {
            pausedByTrigger = false
            AppLog.d { "TRACKING resumed" }
        }

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
            params = trackingSettings.params.value.forZoom(zoomRatio),
        )
        session.setTrackerCommand(output.command)
        _trackingOutput.value = output
        if (tracker.learnedTiltLimits != lastLearnedLimits) {
            lastLearnedLimits = tracker.learnedTiltLimits
            AppLog.d { "TILT end stops learned up=${lastLearnedLimits.first} down=${lastLearnedLimits.second}" }
        }
        AppLog.d {
            "TRACK errPan=%.2f errTilt=%.2f cmdPan=%d cmdTilt=%d tilt=%s".format(
                Locale.ROOT, output.panErrorDeg, output.tiltErrorDeg,
                output.command.panRight, output.command.tiltUp,
                tiltDeg?.let { "%.1f".format(Locale.ROOT, it) } ?: "-",
            )
        }
    }

    private companion object {
        const val KEY_PHOTO_TIMER = "photoTimer"
    }

    override fun onCleared() {
        voice.close()
        faces.frameListener = null
        session.close()
        faces.close()
    }
}
