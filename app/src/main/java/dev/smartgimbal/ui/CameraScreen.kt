package dev.smartgimbal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartgimbal.ble.LinkState
import dev.smartgimbal.gimbal.CommandSource
import dev.smartgimbal.gimbal.GimbalSession

/**
 * Main screen. Step 1 of phase 2 only shows the gimbal status; the camera preview, face
 * tracking and recording follow in the next steps.
 */
@Composable
fun CameraScreen(session: GimbalSession, onOpenDebug: () -> Unit) {
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            GimbalStatusBar(
                session = session,
                onOpenDebug = onOpenDebug,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),
            )
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Kamera folgt in Schritt 2", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Verbinden und manuelle Steuerung laufen weiter über den Debug-Screen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Compact gimbal status; tapping it opens the debug screen to connect. */
@Composable
fun GimbalStatusBar(session: GimbalSession, onOpenDebug: () -> Unit, modifier: Modifier = Modifier) {
    val state by session.client.state.collectAsStateWithLifecycle()
    val name by session.client.connectedName.collectAsStateWithLifecycle()
    val source by session.activeSource.collectAsStateWithLifecycle()

    val label = when (state) {
        LinkState.READY -> buildString {
            append("Gimbal: ").append(name ?: "verbunden")
            when (source) {
                CommandSource.MANUAL -> append(" · manuell")
                CommandSource.TRACKER -> append(" · Tracking")
                null -> Unit
            }
        }
        LinkState.IDLE -> "Gimbal nicht verbunden"
        else -> "Gimbal verbindet …"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = onOpenDebug, label = { Text(label) }, modifier = Modifier.weight(1f, fill = false))
        Box(Modifier.weight(1f))
        TextButton(onClick = onOpenDebug) { Text("Debug") }
    }
}
