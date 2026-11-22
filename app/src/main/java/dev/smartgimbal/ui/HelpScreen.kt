package dev.smartgimbal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.smartgimbal.voice.VoiceCommand

/** All voice commands (straight from [VoiceCommand], so the list never drifts) plus usage tips. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← Kamera") }
                    Text("Hilfe", style = MaterialTheme.typography.headlineSmall)
                }
            }

            item { SectionTitle("Sprachbefehle") }
            item {
                Text(
                    "Die App hört zu, solange der Kamera-Screen offen ist und der Button „Sprache“ grün ist.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(VoiceCommand.entries) { command -> CommandCard(command) }

            item { SectionTitle("Gut zu wissen") }
            item { TipsCard(TIPS) }

            item { SectionTitle("Bedienelemente") }
            item { TipsCard(CONTROLS) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CommandCard(command: VoiceCommand) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                command.phrases.joinToString("  ·  ") { "„${it.replaceFirstChar(Char::uppercase)}“" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(command.help, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TipsCard(lines: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            lines.forEach { (title, text) ->
                Row {
                    Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private val TIPS = listOf(
    "Einzeln und deutlich sprechen" to
        "Zwischen zwei Befehlen kurz Pause machen. Mehrere Befehle in einem Atemzug („Käse Stopp“) werden ignoriert.",
    "Rückmeldung" to
        "Ein erkannter Befehl vibriert kurz und zeigt oben einen blauen Hinweis. Passt ein Befehl gerade nicht " +
        "(z. B. „Stopp“ ohne Aufnahme), steht der Grund im Hinweis.",
    "Abstand und Lärm" to
        "Bis etwa 2–3 m klappt die Erkennung zuverlässig. In lauter Umgebung näher heran oder im Debug-Screen " +
        "den Schwellwert senken (dort stehen auch die letzten Erkennungen).",
    "Nur deutsche Wörter" to
        "Das Sprachmodell ist deutsch. „Gimbal“ kennt es nicht, deshalb heißt der Befehl „Kamera zurück“. " +
        "„Cheese“ kennt es auch nicht, versteht es aber als „Käse“.",
    "Foto und Video" to
        "Fotos gibt es nur ohne laufende Aufnahme. Während des Countdowns bricht „Stopp“ ab. " +
        "Videos: Hauptkamera 4K/30, Frontkamera höchstens 1080p.",
    "Tracking" to
        "Braucht einen verbundenen Gimbal. Solange die Zeigefinger-Taste am Gimbal gehalten wird, pausiert das " +
        "Tracking. Beim Gehen hängt der Gimbal etwas hinterher, dafür schwingt er nicht " +
        "über. Steht er ruhig, regelt er erst ab einer kleinen Abweichung nach (Totzone).",
    "Anschläge" to
        "Tilt hat mechanische Grenzen (nach oben etwa 25°). Dort bleibt ein Gesicht über der Bildmitte; die App " +
        "erkennt den Anschlag und drückt nicht weiter dagegen.",
    "Weitwinkel und Zoom" to
        "0,7× nutzt die Ultraweitwinkel-Kamera (nur hinten), 2× und 4× sind Zoom. Das Tracking passt sich an.",
    "Dateien" to "Videos landen in Movies/SmartGimbal, Fotos in Pictures/SmartGimbal.",
    "Mikrofon" to
        "Ohne Mikrofon-Berechtigung gibt es keine Sprachsteuerung und keinen Ton in Videos. Die Sprache und die " +
        "Tonspur nutzen das Mikrofon gleichzeitig.",
)

private val CONTROLS = listOf(
    "Gimbal-Chip (oben links)" to
        "Antippen verbindet mit dem Gimbal, nochmal antippen bricht ab. Verbunden: zeigt den Akkustand des " +
        "Gimbals und „Trigger“, solange die Zeigefinger-Taste gehalten wird. Antippen öffnet den Debug-Screen.",
    "Daumentaste am Gimbal" to
        "Kurz: Foto. Lang: Aufnahme starten. Doppelt: Kamera wechseln. Läuft eine Aufnahme oder der " +
        "Countdown, stoppt ein kurzer oder langer Druck. Funktioniert, solange der Kamera-Screen offen ist.",
    "Home" to "Gimbal in die Mittelstellung.",
    "Zoom (oben rechts)" to "Antippen schaltet 0,7× → 1× → 2× → 4×.",
    "Front / Haupt" to "Wechselt die Kamera (nicht während Aufnahme oder Countdown).",
    "Foto" to "Macht ein Foto nach dem eingestellten Selbstauslöser, währenddessen „Abbrechen“.",
    "⏱ (unten rechts)" to "Selbstauslöser: Antippen schaltet 3 s → 10 s → aus.",
    "Roter Knopf" to "Aufnahme starten und stoppen.",
    "Sprache" to "Sprachsteuerung ein/aus (grün = hört zu).",
    "Tracking" to "Face-Tracking ein/aus (grün = an). Das grüne Kreuz markiert die Bildmitte.",
    "Debug" to "Verbindung, Steuerkreuz, Telemetrie, Tracking- und Sprach-Einstellungen, Log.",
)
