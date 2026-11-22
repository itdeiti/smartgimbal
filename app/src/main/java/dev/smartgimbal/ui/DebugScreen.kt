package dev.smartgimbal.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartgimbal.ble.FoundDevice
import dev.smartgimbal.gimbal.GimbalSession
import dev.smartgimbal.tracking.TrackingSettings
import dev.smartgimbal.voice.VoiceControl
import dev.smartgimbal.ble.LinkState
import dev.smartgimbal.ble.LogEntry
import dev.smartgimbal.protocol.Telemetry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private fun hasBlePermissions(context: Context) = BLE_PERMISSIONS.all {
    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}

/** Protocol tools from phase 1: connect, D-pad, telemetry, raw frames, log. */
@Composable
fun DebugScreen(
    session: GimbalSession,
    trackingSettings: TrackingSettings,
    voice: VoiceControl,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by session.client.state.collectAsStateWithLifecycle()
    val devices by session.client.devices.collectAsStateWithLifecycle()
    val connectedName by session.client.connectedName.collectAsStateWithLifecycle()
    val strength by session.manualStrength.collectAsStateWithLifecycle()
    val logJoystick by session.logJoystick.collectAsStateWithLifecycle()
    val log by session.log.collectAsStateWithLifecycle()
    val telemetry by session.telemetry.collectAsStateWithLifecycle()
    val telemetryZero by session.telemetryZero.collectAsStateWithLifecycle()
    val telemetryCount by session.telemetryCount.collectAsStateWithLifecycle()
    val logTelemetry by session.logTelemetry.collectAsStateWithLifecycle()
    val trackingParams by trackingSettings.params.collectAsStateWithLifecycle()

    var hasPermissions by remember { mutableStateOf(hasBlePermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> hasPermissions = result.values.all { it } }
    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { if (session.client.isBluetoothEnabled) session.client.startScan() }

    val onScan = {
        if (session.client.isBluetoothEnabled) {
            session.client.startScan()
        } else {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← Kamera") }
                    Text("Debug · BLE", style = MaterialTheme.typography.headlineSmall)
                }
            }

            if (!hasPermissions) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Bluetooth-Berechtigungen fehlen (Scan + Verbinden).")
                            Button(onClick = { permissionLauncher.launch(BLE_PERMISSIONS) }) {
                                Text("Berechtigungen erteilen")
                            }
                        }
                    }
                }
                return@LazyColumn
            }

            item {
                ConnectionBar(
                    state = state,
                    connectedName = connectedName,
                    onScan = onScan,
                    onStopScan = session.client::stopScan,
                    onDisconnect = session.client::disconnect,
                )
            }

            if (state == LinkState.IDLE || state == LinkState.SCANNING) {
                if (devices.isEmpty()) {
                    item {
                        Text(
                            if (state == LinkState.SCANNING) "Suche FY_PocketV_* …" else "Kein Gimbal gefunden. Gimbal einschalten und scannen.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(devices, key = { it.address }) { device ->
                    DeviceRow(device) { session.client.connect(device) }
                }
            }

            item {
                ControlPanel(
                    enabled = state == LinkState.READY,
                    strength = strength,
                    onStrength = session::setManualStrength,
                    onPress = session::pressManual,
                    onRelease = session::releaseManual,
                    onHome = session::home,
                )
            }

            item {
                TelemetryCard(
                    telemetry = telemetry,
                    zero = telemetryZero,
                    count = telemetryCount,
                    logTelemetry = logTelemetry,
                    onZero = session::zeroTelemetry,
                    onLogTelemetry = session::setLogTelemetry,
                )
            }

            item {
                TuningCard(
                    params = trackingParams,
                    onChange = trackingSettings::update,
                    onReset = trackingSettings::resetToDefaults,
                )
            }

            item { VoiceCard(voice) }

            item {
                RawSender(enabled = state == LinkState.READY, onSend = session::sendRaw)
            }

            item {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("Joystick-TX", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = logJoystick, onCheckedChange = session::setLogJoystick)
                    TextButton(onClick = session::clearLog) { Text("Leeren") }
                }
            }

            items(log, key = { it.id }) { entry -> LogRow(entry) }
        }
    }
}

@Composable
private fun ConnectionBar(
    state: LinkState,
    connectedName: String?,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val label = when (state) {
        LinkState.IDLE -> "Nicht verbunden"
        LinkState.SCANNING -> "Scanne …"
        LinkState.CONNECTING -> "Verbinde mit $connectedName …"
        LinkState.DISCOVERING -> "Lese Services von $connectedName …"
        LinkState.READY -> "Bereit: $connectedName"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        when (state) {
            LinkState.IDLE -> Button(onClick = onScan) { Text("Scannen") }
            LinkState.SCANNING -> OutlinedButton(onClick = onStopScan) { Text("Stopp") }
            else -> OutlinedButton(onClick = onDisconnect) { Text("Trennen") }
        }
    }
}

@Composable
private fun DeviceRow(device: FoundDevice, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ControlPanel(
    enabled: Boolean,
    strength: Int,
    onStrength: (Int) -> Unit,
    onPress: (Int, Int) -> Unit,
    onRelease: () -> Unit,
    onHome: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Operator terms: looking at the gimbal from the clamp/button side. See FeiyuProtocol.move().
            HoldButton("▲", enabled, onPress = { onPress(0, 1) }, onRelease = onRelease)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HoldButton("◀", enabled, onPress = { onPress(-1, 0) }, onRelease = onRelease)
                FilledTonalButton(
                    onClick = onHome,
                    enabled = enabled,
                    modifier = Modifier.size(72.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Home", fontSize = 12.sp)
                }
                HoldButton("▶", enabled, onPress = { onPress(1, 0) }, onRelease = onRelease)
            }
            HoldButton("▼", enabled, onPress = { onPress(0, -1) }, onRelease = onRelease)

            Spacer(Modifier.size(4.dp))
            Text("Stärke: $strength", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = strength.toFloat(),
                onValueChange = { onStrength(it.roundToInt()) },
                valueRange = 10f..GimbalSession.MAX_STRENGTH.toFloat(),
            )
        }
    }
}

/** Button that reports press and release separately, so the gimbal moves only while held. */
@Composable
private fun HoldButton(label: String, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val colors = MaterialTheme.colorScheme
    val background = when {
        !enabled -> colors.surfaceVariant
        pressed -> colors.primary
        else -> colors.primaryContainer
    }
    val foreground = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.4f)
        pressed -> colors.onPrimary
        else -> colors.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    pressed = true
                    currentOnPress()
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressed = false
                        currentOnRelease()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 28.sp, color = foreground)
    }
}

/**
 * Live view of the 0x1E status frame in degrees, with signs matching the arrow buttons.
 * The delta column shows how far each axis moved since the last zero.
 */
@Composable
private fun TelemetryCard(
    telemetry: Telemetry?,
    zero: Telemetry?,
    count: Int,
    logTelemetry: Boolean,
    onZero: () -> Unit,
    onLogTelemetry: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Telemetrie (0x1E)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("$count Frames", style = MaterialTheme.typography.bodySmall)
            }
            if (telemetry == null) {
                Text("Noch keine Daten.", style = MaterialTheme.typography.bodyMedium)
            } else {
                TelemetryRow("Feld", "Wert", "Δ Nullpunkt", header = true)
                TelemetryRow("Pan (→ +)", degrees(telemetry.pan), zero?.let { degrees(telemetry.pan - it.pan) } ?: "–")
                TelemetryRow("Tilt (↑ +)", degrees(telemetry.tilt), zero?.let { degrees(telemetry.tilt - it.tilt) } ?: "–")
                TelemetryRow("Roll", degrees(telemetry.roll), zero?.let { degrees(telemetry.roll - it.roll) } ?: "–")
                TelemetryRow("D", telemetry.d.toString(), zero?.let { (telemetry.d - it.d).toString() } ?: "–")
                Text(
                    "Roh: a=${telemetry.a} b=${telemetry.b} c=${telemetry.c} status=${telemetry.status}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onZero, enabled = telemetry != null) { Text("Nullpunkt") }
                Spacer(Modifier.weight(1f))
                Text("Ins Log", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(4.dp))
                Switch(checked = logTelemetry, onCheckedChange = onLogTelemetry)
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, delta: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = style, modifier = Modifier.weight(1.2f))
        Text(value, style = style, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(delta, style = style, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

private fun degrees(raw: Int): String = "%+.2f°".format(Locale.GERMANY, raw / 100.0)

@Composable
private fun RawSender(enabled: Boolean, onSend: (String, Boolean) -> Unit) {
    var hex by rememberSaveable { mutableStateOf("A5 5A ") }
    var appendCrc by rememberSaveable { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Roh-Frame senden", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = hex,
                onValueChange = { hex = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = appendCrc, onCheckedChange = { appendCrc = it })
                Text("CRC anhängen", modifier = Modifier.weight(1f))
                Button(onClick = { onSend(hex, appendCrc) }, enabled = enabled) { Text("Senden") }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)

@Composable
private fun LogRow(entry: LogEntry) {
    val colors = MaterialTheme.colorScheme
    val color = when (entry.kind) {
        LogEntry.Kind.ERROR -> colors.error
        LogEntry.Kind.TX -> colors.primary
        LogEntry.Kind.RX -> colors.tertiary
        LogEntry.Kind.INFO -> colors.onSurfaceVariant
    }
    Text(
        text = "${timeFormat.format(Date(entry.timeMillis))} ${entry.kind.name.padEnd(5)} ${entry.text}",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = color,
    )
}
