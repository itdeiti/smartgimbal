package dev.smartgimbal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartgimbal.voice.VoiceControl
import dev.smartgimbal.voice.VoiceDecision
import java.util.Locale

/** Voice settings and the last recognition results, for tuning the threshold. */
@Composable
fun VoiceCard(voice: VoiceControl) {
    val params by voice.settings.params.collectAsStateWithLifecycle()
    val engine by voice.engineState.collectAsStateWithLifecycle()
    val listening by voice.listening.collectAsStateWithLifecycle()
    val level by voice.level.collectAsStateWithLifecycle()
    val history by voice.history.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sprache", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = params.enabled, onCheckedChange = voice::setEnabled)
            }
            Text(
                "Modell: $engine · ${if (listening) "hört zu (nur im Kamera-Screen)" else "hört nicht zu"}" +
                    if (listening) " · Pegel %.0f dB".format(Locale.GERMANY, level) else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Text("Schwellwert Konfidenz", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("%.2f".format(Locale.GERMANY, params.threshold), style = MaterialTheme.typography.bodyMedium)
            }
            Slider(value = params.threshold, onValueChange = voice::setThreshold, valueRange = 0.3f..1f)

            Text("Letzte Ergebnisse", style = MaterialTheme.typography.labelMedium)
            if (history.isEmpty()) Text("–", style = MaterialTheme.typography.bodySmall)
            history.forEach { d ->
                val outcome = when (d) {
                    is VoiceDecision.Execute -> "✓ ausgeführt"
                    is VoiceDecision.Ignore -> "✗ ${d.reason}"
                }
                Text(
                    "\"${d.text}\" %.2f  $outcome".format(Locale.GERMANY, d.confidence),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (d is VoiceDecision.Execute) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
