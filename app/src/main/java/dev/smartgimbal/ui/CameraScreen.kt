package dev.smartgimbal.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import android.os.PowerManager
import android.util.Log
import android.util.Range
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartgimbal.ble.LinkState
import dev.smartgimbal.gimbal.CommandSource
import dev.smartgimbal.GimbalViewModel
import dev.smartgimbal.camera.PhotoTaker
import dev.smartgimbal.camera.PreviewFrameGrabber
import dev.smartgimbal.camera.RecordingState
import dev.smartgimbal.camera.VideoRecorder
import dev.smartgimbal.gimbal.GimbalSession
import dev.smartgimbal.voice.VoiceCommand
import dev.smartgimbal.voice.VoiceEngine

/** Main screen: full-screen camera preview with gimbal status and controls on top. */
@Composable
fun CameraScreen(
    vm: GimbalViewModel,
    useFrontCamera: Boolean,
    onToggleCamera: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasAll(REQUIRED_PERMISSIONS)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = context.hasAll(REQUIRED_PERMISSIONS) }

    if (granted) {
        CameraContent(vm, useFrontCamera, onToggleCamera, onOpenDebug)
    } else {
        PermissionGate(onRequest = { launcher.launch(REQUIRED_PERMISSIONS + OPTIONAL_PERMISSIONS) })
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(32.dp)) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Kamera und Bluetooth werden für Vorschau und Gimbal gebraucht, das Mikrofon für den Ton der Aufnahmen.",
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRequest) { Text("Berechtigungen erteilen") }
        }
    }
}

@Composable
private fun CameraContent(
    vm: GimbalViewModel,
    useFront: Boolean,
    onToggleCamera: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = vm.session
    val faces = vm.faces
    val trackingEnabled by vm.trackingEnabled.collectAsStateWithLifecycle()
    val voiceFeedback by vm.voice.feedback.collectAsStateWithLifecycle()
    val gimbalState by session.client.state.collectAsStateWithLifecycle()

    val frame by faces.frame.collectAsStateWithLifecycle()
    val stats by faces.stats.collectAsStateWithLifecycle()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val recorder = remember { VideoRecorder(context) }
    val rec by recorder.state.collectAsStateWithLifecycle()
    val photo = remember { PhotoTaker(context) }
    val photoState by photo.state.collectAsStateWithLifecycle()
    val wideAngle by vm.wideAngle.collectAsStateWithLifecycle()
    var appliedZoom by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val countingDown = photoState.countdown != null
    // Camera busy: switching camera or screen would cut a recording or the self-timer.
    val busy = rec.recording || countingDown

    // Only preview + video: that pair runs in 4K/30 on the Pixel 7. A third (analysis) stream would
    // force 1080p, so face detection reads copies of the preview instead (PreviewFrameGrabber).
    val grabber = remember { PreviewFrameGrabber(faces) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.VIDEO_CAPTURE)
            videoCaptureQualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
            )
            videoCaptureTargetFrameRate = Range(30, 30)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
    }
    val startPhoto = { if (!busy) photo.start(scope, controller) { appliedZoom } }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        // Leaving the screen (e.g. to debug) releases the camera.
        onDispose {
            photo.cancel()
            recorder.stop()
            controller.unbind()
            grabber.release()
        }
    }
    // Voice: listen only while the camera screen is in the foreground.
    LifecycleResumeEffect(Unit) {
        vm.voice.onScreenResumed()
        onPauseOrDispose { vm.voice.onScreenPaused() }
    }
    LaunchedEffect(rec.recording) { vm.setRecording(rec.recording) }
    LaunchedEffect(countingDown) { vm.setPhotoCountdown(countingDown) }
    LaunchedEffect(controller) {
        vm.cameraCommands.collect { command ->
            when (command) {
                VoiceCommand.START_RECORDING -> if (!photo.isCountingDown) recorder.start(controller)
                // Stop cancels the self-timer if one is running, otherwise it ends the recording.
                VoiceCommand.STOP -> if (photo.isCountingDown) photo.cancel() else recorder.stop()
                VoiceCommand.PHOTO -> startPhoto()
                else -> Unit
            }
        }
    }
    // Wide angle = the logical camera's minimum zoom ratio (0.67 on the Pixel 7 = ultrawide lens).
    LaunchedEffect(wideAngle, useFront) {
        val ratio = applyZoom(controller, wide = wideAngle && !useFront)
        appliedZoom = ratio
        vm.setZoomRatio(ratio.toDouble())
    }
    // Feeds face detection with preview copies; skips frames while the detector is busy.
    LaunchedEffect(previewView, useFront) {
        val view = previewView ?: return@LaunchedEffect
        while (isActive) {
            delay(GRAB_INTERVAL_MS)
            // The preview of the front camera is mirrored; the tracker un-mirrors it.
            grabber.grab(view, mirrored = useFront)
        }
    }
    LaunchedEffect(useFront) {
        controller.cameraSelector =
            if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        faces.reset()
        vm.setCamera(useFront)
        logVideoCapabilities(controller)
    }

    // The controller crops every output to the preview's aspect ratio. A 16:9 preview (letterboxed
    // like in camera apps) keeps the recording at full 16:9 and shows exactly what is recorded.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      Box(Modifier.align(Alignment.Center).aspectRatio(if (landscape) 16f / 9f else 9f / 16f)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // TextureView (COMPATIBLE) is needed for the cheap preview copies.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    this.controller = controller
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        FaceOverlay(frame = frame, showCenter = trackingEnabled, modifier = Modifier.fillMaxSize())
        photoState.countdown?.let { CountdownOverlay(it) }
      }

        Text(
            text = "%d %s · %s %.0f ms · %.0f fps".format(
                frame?.faces?.size ?: 0,
                if ((frame?.faces?.size ?: 0) == 1) "Gesicht" else "Gesichter",
                stats.detectorName,
                stats.avgMillis,
                stats.fps,
            ),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .systemBarsPadding()
                .padding(start = 16.dp, bottom = 128.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        // Leaving the screen would release the camera and cut the recording or the self-timer.
        val openDebug = { if (!busy) onOpenDebug() }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().systemBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GimbalChip(session, openDebug)
            Spacer(Modifier.weight(1f))
            if (rec.recording) RecordingBadge(rec)
            Spacer(Modifier.weight(1f))
            OverlayButton(
                text = if (wideAngle) "0,7×" else "1×",
                active = wideAngle,
                enabled = !useFront,
                onClick = { vm.setWideAngle(!wideAngle) },
            )
            Spacer(Modifier.width(8.dp))
            OverlayButton("Debug", enabled = !busy, onClick = openDebug)
        }

        voiceFeedback?.let { note ->
            LaunchedEffect(note) {
                delay(2_500)
                vm.voice.clearFeedback()
            }
            Text(
                note,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1565C0).copy(alpha = 0.85f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        photoState.message?.let { message ->
            LaunchedEffect(message) {
                delay(3_000)
                photo.clearMessage()
            }
            Text(
                message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(bottom = 168.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        rec.message?.let { message ->
            LaunchedEffect(message) {
                delay(4_000)
                recorder.clearMessage()
            }
            Text(
                message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(bottom = 128.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayButton(
                if (useFront) "Haupt" else "Front",
                enabled = !busy,
                onClick = onToggleCamera,
            )
            OverlayButton(
                text = if (countingDown) "Abbrechen" else "Foto",
                enabled = !rec.recording,
                onClick = { if (countingDown) photo.cancel() else startPhoto() },
            )
            RecordButton(recording = rec.recording, enabled = !countingDown, onClick = { recorder.toggle(controller) })
            VoiceButton(vm)
            OverlayButton(
                text = "Tracking",
                active = trackingEnabled,
                // Switching on needs a gimbal; switching off must always work.
                enabled = trackingEnabled || gimbalState == LinkState.READY,
                onClick = { vm.setTrackingEnabled(!trackingEnabled) },
            )
        }
    }
}

/**
 * Gimbal status that doubles as connect button: tapping it while disconnected scans and connects
 * to the first gimbal found.
 */
@Composable
private fun GimbalChip(session: GimbalSession, onOpenDebug: () -> Unit) {
    val state by session.client.state.collectAsStateWithLifecycle()
    val name by session.client.connectedName.collectAsStateWithLifecycle()
    val source by session.activeSource.collectAsStateWithLifecycle()
    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { if (session.client.isBluetoothEnabled) session.client.startScan(autoConnect = true) }

    val label = when (state) {
        LinkState.IDLE -> "Gimbal verbinden"
        LinkState.SCANNING -> "Suche Gimbal …"
        LinkState.CONNECTING, LinkState.DISCOVERING -> "Verbinde …"
        LinkState.READY -> buildString {
            append("● ").append(name ?: "Gimbal")
            when (source) {
                CommandSource.MANUAL -> append(" · manuell")
                CommandSource.TRACKER -> append(" · Tracking")
                null -> Unit
            }
        }
    }
    val onClick: () -> Unit = when (state) {
        LinkState.IDLE -> {
            {
                if (session.client.isBluetoothEnabled) {
                    session.client.startScan(autoConnect = true)
                } else {
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
        }
        LinkState.SCANNING -> session.client::stopScan
        LinkState.READY -> onOpenDebug
        else -> { {} }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OverlayButton(label, onClick = onClick)
        if (state == LinkState.READY) {
            Spacer(Modifier.width(8.dp))
            OverlayButton("Home", onClick = session::home)
        }
    }
}

@Composable
private fun OverlayButton(text: String, onClick: () -> Unit, enabled: Boolean = true, active: Boolean = false) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xFF2E7D32) else Color.Black.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** Up to 30 copies per second (skipped while the detector is busy); each costs ~3 ms on the main thread. */
private const val GRAB_INTERVAL_MS = 33L

/** Logs which video qualities the bound camera offers, to see why 4K may fall back. */
private suspend fun logVideoCapabilities(controller: LifecycleCameraController) {
    // cameraInfo is only available once the controller has bound the camera.
    repeat(20) {
        val info = controller.cameraInfo
        if (info != null) {
            val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
            Log.d(GimbalSession.LOG_TAG, "VIDEO supported=$qualities")
            return
        }
        delay(100)
    }
}

/** Voice control on/off, with the model and permission state in the label. */
@Composable
private fun VoiceButton(vm: GimbalViewModel) {
    val params by vm.voice.settings.params.collectAsStateWithLifecycle()
    val engine by vm.voice.engineState.collectAsStateWithLifecycle()
    val listening by vm.voice.listening.collectAsStateWithLifecycle()
    val (text, enabled) = when {
        !vm.voice.hasMicPermission -> "Sprache: kein Mikro" to false
        engine == VoiceEngine.State.LOADING || engine == VoiceEngine.State.IDLE -> "Sprache lädt …" to false
        engine == VoiceEngine.State.FAILED -> "Sprache: Fehler" to false
        else -> "Sprache" to true
    }
    OverlayButton(
        text = text,
        active = params.enabled && listening,
        enabled = enabled,
        onClick = { vm.voice.setEnabled(!params.enabled) },
    )
}

/** Big seconds of the photo self-timer; a white flash at 0. */
@Composable
private fun BoxScope.CountdownOverlay(seconds: Int) {
    if (seconds == 0) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))
        return
    }
    Text(
        seconds.toString(),
        color = Color.White,
        fontSize = 140.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(Alignment.Center)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 48.dp),
    )
}

/** Sets the zoom ratio once the camera is bound; returns the ratio actually used. */
private suspend fun applyZoom(controller: LifecycleCameraController, wide: Boolean): Float {
    repeat(30) {
        val zoom = controller.cameraInfo?.zoomState?.value
        if (zoom != null) {
            val ratio = if (wide) zoom.minZoomRatio else 1f
            controller.setZoomRatio(ratio)
            return ratio
        }
        delay(100)
    }
    return 1f
}

/** Red dot to start; while recording a smaller red square to stop. */
@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (recording) 30.dp else 60.dp)
                .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                .background(Color(0xFFE53935)),
        )
    }
}

@Composable
private fun RecordingBadge(rec: RecordingState) {
    val seconds = rec.durationMillis / 1000
    val hot = rec.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    val text = "● %02d:%02d".format(seconds / 60, seconds % 60) + if (hot) " · Gerät heiß" else ""
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFE53935).copy(alpha = if (hot) 1f else 0.85f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
