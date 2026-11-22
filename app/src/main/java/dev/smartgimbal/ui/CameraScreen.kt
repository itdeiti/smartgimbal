package dev.smartgimbal.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartgimbal.ble.LinkState
import dev.smartgimbal.gimbal.CommandSource
import dev.smartgimbal.gimbal.GimbalSession

/** Main screen: full-screen camera preview with gimbal status and controls on top. */
@Composable
fun CameraScreen(
    session: GimbalSession,
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
        CameraContent(session, useFrontCamera, onToggleCamera, onOpenDebug)
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
    session: GimbalSession,
    useFront: Boolean,
    onToggleCamera: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Preview only for now; analysis (step 3) and video capture (step 5) are enabled later.
    val controller = remember { LifecycleCameraController(context).apply { setEnabledUseCases(0) } }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        // Leaving the screen (e.g. to debug) releases the camera.
        onDispose { controller.unbind() }
    }
    LaunchedEffect(useFront) {
        controller.cameraSelector =
            if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().systemBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GimbalChip(session, onOpenDebug)
            Spacer(Modifier.weight(1f))
            OverlayButton("Debug", onClick = onOpenDebug)
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayButton(if (useFront) "Hauptkamera" else "Frontkamera", onClick = onToggleCamera)
            RecordButton(enabled = false, onClick = {}) // step 5
            OverlayButton("Tracking", enabled = false, onClick = {}) // step 4
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
private fun OverlayButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RecordButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(Color(0xFFE53935))
            .clickable(enabled = enabled, onClick = onClick),
    )
}
