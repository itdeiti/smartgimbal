package dev.smartgimbal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import android.os.PowerManager
import android.os.SystemClock
import android.util.Range
import androidx.camera.core.CameraInfo
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartgimbal.ble.LinkState
import dev.smartgimbal.gimbal.CommandSource
import dev.smartgimbal.AppLog
import dev.smartgimbal.GimbalViewModel
import dev.smartgimbal.OPTIONAL_PERMISSIONS
import dev.smartgimbal.REQUIRED_PERMISSIONS
import dev.smartgimbal.hasAll
import dev.smartgimbal.tracking.FacePipeline
import dev.smartgimbal.camera.FeedbackSounds
import dev.smartgimbal.camera.PhotoTaker
import dev.smartgimbal.camera.PhotoTimer
import dev.smartgimbal.camera.ZoomLevel
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
    onOpenHelp: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasAll(REQUIRED_PERMISSIONS)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = context.hasAll(REQUIRED_PERMISSIONS) }

    if (granted) {
        CameraContent(vm, useFrontCamera, onToggleCamera, onOpenDebug, onOpenHelp)
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
    onOpenHelp: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = vm.session
    val faces = vm.faces
    val trackingEnabled by vm.trackingEnabled.collectAsStateWithLifecycle()
    val voiceFeedback by vm.voice.feedback.collectAsStateWithLifecycle()
    val gimbalState by session.client.state.collectAsStateWithLifecycle()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val recorder = remember { VideoRecorder(context) }
    val rec by recorder.state.collectAsStateWithLifecycle()
    val sounds = remember { FeedbackSounds() }
    val photo = remember { PhotoTaker(context, sounds) }
    val photoState by photo.state.collectAsStateWithLifecycle()
    val zoomLevel by vm.zoomLevel.collectAsStateWithLifecycle()
    val photoTimer by vm.photoTimer.params.collectAsStateWithLifecycle()
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
    val startPhoto = { if (!busy) photo.start(scope, controller, photoTimer.seconds) { appliedZoom } }
    // Voice and gimbal button reach the photo through a long-lived collector; it must see the
    // current timer and busy state, not the ones from when it started.
    val currentStartPhoto by rememberUpdatedState(startPhoto)

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        // Leaving the screen (e.g. to debug) releases the camera.
        onDispose {
            photo.cancel()
            recorder.stop()
            controller.unbind()
            grabber.release()
            sounds.release()
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
                VoiceCommand.PHOTO -> currentStartPhoto()
                else -> Unit
            }
        }
    }
    val currentOnToggleCamera by rememberUpdatedState(onToggleCamera)
    LaunchedEffect(Unit) { vm.cameraToggles.collect { currentOnToggleCamera() } }
    // Wide angle = the logical camera's minimum zoom ratio (0.67 on the Pixel 7 = ultrawide lens).
    val soundedZoom = remember { arrayOfNulls<ZoomLevel>(1) }
    LaunchedEffect(zoomLevel, useFront) {
        // A blip when the step changes (not on start or a camera switch), and never into a
        // running recording's sound track.
        if (soundedZoom[0] != null && soundedZoom[0] != zoomLevel && !recorder.isRecording) sounds.zoomChanged()
        soundedZoom[0] = zoomLevel
        val (ratio, max) = applyZoom(controller, zoomLevel)
        appliedZoom = ratio
        vm.setZoomRatio(ratio.toDouble(), max)
    }
    // Feeds face detection with preview copies; skips frames while the detector is busy.
    // Only while resumed: in the background there is no fresh preview to analyse.
    LaunchedEffect(previewView, useFront, lifecycleOwner) {
        val view = previewView ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(GRAB_INTERVAL_MS)
                // The preview of the front camera is mirrored; the tracker un-mirrors it.
                grabber.grab(view, mirrored = useFront)
            }
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

        FaceLayer(faces, showCenter = trackingEnabled)
        photoState.countdown?.let { CountdownOverlay(it) }
      }

        StatsLabel(
            faces,
            Modifier.align(Alignment.BottomStart).systemBarsPadding().padding(start = 16.dp, bottom = 128.dp),
        )

        OverlayButton(
            text = "⏱ ${photoTimer.label}",
            active = photoTimer != PhotoTimer.S3,
            enabled = !busy,
            onClick = { vm.photoTimer.update { it.next() } },
            modifier = Modifier.align(Alignment.BottomEnd).systemBarsPadding().padding(end = 16.dp, bottom = 128.dp),
        )

        // Leaving the screen would release the camera and cut the recording or the self-timer.
        val openDebug = { if (!busy) onOpenDebug() }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().systemBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GimbalChip(session, trackingEnabled, openDebug)
            Spacer(Modifier.weight(1f))
            if (rec.recording) RecordingBadge(rec)
            Spacer(Modifier.weight(1f))
            OverlayButton(
                text = zoomLevel.label,
                active = zoomLevel != ZoomLevel.NORMAL,
                onClick = { vm.setZoomLevel(zoomLevel.next(frontCamera = useFront)) },
            )
            Spacer(Modifier.width(8.dp))
            OverlayButton("?", enabled = !busy, onClick = { if (!busy) onOpenHelp() })
            Spacer(Modifier.width(8.dp))
            OverlayButton("Debug", enabled = !busy, onClick = openDebug)
        }

        voiceFeedback?.let { note ->
            TimedNote(
                text = note,
                visibleMillis = 2_500,
                onTimeout = vm.voice::clearFeedback,
                modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 72.dp),
                style = MaterialTheme.typography.titleMedium,
                background = Color(0xFF1565C0).copy(alpha = 0.85f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        photoState.message?.let { message ->
            TimedNote(
                text = message,
                visibleMillis = 3_000,
                onTimeout = photo::clearMessage,
                modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 168.dp),
            )
        }

        rec.message?.let { message ->
            TimedNote(
                text = message,
                visibleMillis = 4_000,
                onTimeout = recorder::clearMessage,
                modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 128.dp),
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
 * Gimbal status that doubles as connect button: tapping it while disconnected scans and connects,
 * tapping it while connecting cancels.
 */
@Composable
private fun GimbalChip(session: GimbalSession, trackingEnabled: Boolean, onOpenDebug: () -> Unit) {
    val state by session.client.state.collectAsStateWithLifecycle()
    val name by session.client.connectedName.collectAsStateWithLifecycle()
    val source by session.activeSource.collectAsStateWithLifecycle()
    val battery by session.battery.collectAsStateWithLifecycle()
    val triggerHeld by session.triggerHeld.collectAsStateWithLifecycle()
    val trackingPaused = triggerHeld && trackingEnabled
    val scanAndConnect = rememberScanAction(session.client, autoConnect = true)

    val label = when (state) {
        LinkState.IDLE -> "Gimbal verbinden"
        LinkState.SCANNING -> "Suche Gimbal …"
        LinkState.CONNECTING, LinkState.DISCOVERING -> "Verbinde … ✕"
        LinkState.READY -> buildString {
            append("● ").append(name ?: "Gimbal")
            battery?.let { append(" · ").append(it).append(" %") }
            if (triggerHeld) append(if (trackingPaused) " · Trigger, Tracking pausiert" else " · Trigger")
            when (source) {
                CommandSource.MANUAL -> append(" · manuell")
                CommandSource.TRACKER -> append(" · Tracking")
                null -> Unit
            }
        }
    }
    val onClick: () -> Unit = when (state) {
        LinkState.IDLE -> scanAndConnect
        LinkState.SCANNING -> session.client::stopScan
        LinkState.READY -> onOpenDebug
        // Tapping while connecting cancels the attempt.
        LinkState.CONNECTING, LinkState.DISCOVERING -> session.client::disconnect
    }
    // The chip changes its meaning as soon as it is tapped (connect, then cancel), so the second
    // tap of an accidental double tap must not count.
    val lastTap = remember { longArrayOf(0L) }
    val guardedClick = {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTap[0] >= CHIP_TAP_GUARD_MS) {
            lastTap[0] = now
            onClick()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OverlayButton(label, onClick = guardedClick)
        if (state == LinkState.READY) {
            Spacer(Modifier.width(8.dp))
            OverlayButton("Home", onClick = session::home)
        }
    }
}

@Composable
private fun OverlayButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
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

private const val CHIP_TAP_GUARD_MS = 800L

/** Face boxes over the preview. Reads the frame here, so a new frame redraws only this layer. */
@Composable
private fun FaceLayer(faces: FacePipeline, showCenter: Boolean) {
    val frame by faces.frame.collectAsStateWithLifecycle()
    FaceOverlay(frame = frame, showCenter = showCenter, modifier = Modifier.fillMaxSize())
}

/** Face count and detector timing; reads the per-frame state itself, like [FaceLayer]. */
@Composable
private fun StatsLabel(faces: FacePipeline, modifier: Modifier = Modifier) {
    val frame by faces.frame.collectAsStateWithLifecycle()
    val stats by faces.stats.collectAsStateWithLifecycle()
    val count = frame?.faces?.size ?: 0
    Text(
        text = "%d %s · %s %.0f ms · %.0f fps".format(
            count,
            if (count == 1) "Gesicht" else "Gesichter",
            stats.detectorName,
            stats.avgMillis,
            stats.fps,
        ),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Short note in a pill that asks to be cleared after [visibleMillis]. */
@Composable
private fun TimedNote(
    text: String,
    visibleMillis: Long,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    background: Color = Color.Black.copy(alpha = 0.7f),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
) {
    LaunchedEffect(text) {
        delay(visibleMillis)
        onTimeout()
    }
    Text(
        text,
        color = Color.White,
        style = style,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(contentPadding),
    )
}

/** The controller's camera info, which only exists once the camera is bound; null after 3 s. */
private suspend fun LifecycleCameraController.awaitCameraInfo(): CameraInfo? {
    repeat(30) {
        cameraInfo?.let { return it }
        delay(100)
    }
    return null
}

/** Logs which video qualities the bound camera offers, to see why 4K may fall back. */
private suspend fun logVideoCapabilities(controller: LifecycleCameraController) {
    val info = controller.awaitCameraInfo() ?: return
    val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
    AppLog.d { "VIDEO supported=$qualities" }
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

/**
 * Sets the zoom once the camera is bound, clamped to what the camera supports.
 * Returns the ratio actually used and the camera's maximum.
 */
private suspend fun applyZoom(controller: LifecycleCameraController, level: ZoomLevel): Pair<Float, Float> {
    val zoom = controller.awaitCameraInfo()?.zoomState?.value ?: return 1f to 1f
    val ratio = (level.ratio ?: zoom.minZoomRatio).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
    controller.setZoomRatio(ratio)
    return ratio to zoom.maxZoomRatio
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
