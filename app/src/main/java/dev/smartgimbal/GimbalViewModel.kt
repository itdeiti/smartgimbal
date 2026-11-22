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
import kotlinx.coroutines.flow.MutableStateFlow
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

    init {
        faces.frameListener = ::onFaceFrame
    }

    /** Called by the camera screen whenever the active camera changes. */
    fun setCamera(front: Boolean) {
        val fov = CameraFovReader.read(getApplication(), front) ?: Fov.DEFAULT
        camera = CameraSetup(front, fov)
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
            fov = setup.fov,
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
        faces.frameListener = null
        session.close()
        faces.close()
    }
}
