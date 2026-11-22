package dev.smartgimbal.voice

/** Spoken commands; the recognised text must match one of the [phrases] exactly. */
enum class VoiceCommand(val label: String, vararg val phrases: String) {
    START_RECORDING("Aufnahme starten", "aufnahme starten"),

    // "Aufnahme stoppen" must be in the grammar: without it Vosk heard the closest phrase,
    // "starten" (voice test 2026-11-22).
    STOP("Stopp", "stopp", "aufnahme stopp", "aufnahme stoppen"),
    TRACKING_ON("Tracking an", "tracking an"),
    TRACKING_OFF("Tracking aus", "tracking aus"),

    // "gimbal" is not in the German model's vocabulary, hence "kamera zurück" for Home.
    HOME("Kamera zurück", "kamera zurück"),
    ;

    companion object {
        val GRAMMAR: List<String> = entries.flatMap { it.phrases.toList() }
    }
}

/** App state the commands are checked against. */
data class VoiceContext(val recording: Boolean, val gimbalReady: Boolean, val trackingOn: Boolean)

sealed interface VoiceDecision {
    val text: String
    val confidence: Double

    data class Execute(val command: VoiceCommand, override val text: String, override val confidence: Double) : VoiceDecision

    data class Ignore(val reason: String, override val text: String, override val confidence: Double) : VoiceDecision
}

/**
 * Turns a Vosk result into a command decision.
 *
 * Only whole commands count: Vosk's grammar model also returns single words such as "starten".
 * Every word must reach [threshold]; the same command is not executed twice within
 * [debounceMillis]; and commands that make no sense in the current state are ignored (e.g.
 * "stopp" without a running recording). Plain Kotlin with regex parsing, so it runs in unit tests.
 */
class VoiceCommandParser(private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS) {

    private var lastCommand: VoiceCommand? = null
    private var lastMillis = 0L

    fun decide(json: String, context: VoiceContext, threshold: Double, nowMillis: Long): VoiceDecision {
        val text = TEXT.find(json)?.groupValues?.get(1)?.trim().orEmpty()
        val confidences = CONF.findAll(json).map { it.groupValues[1].toDouble() }.toList()
        val confidence = confidences.minOrNull() ?: 0.0

        if (text.isEmpty()) return VoiceDecision.Ignore("leer", text, confidence)
        val command = VoiceCommand.entries.firstOrNull { text in it.phrases }
            ?: return VoiceDecision.Ignore("kein ganzer Befehl", text, confidence)
        if (confidence < threshold) return VoiceDecision.Ignore("Konfidenz zu niedrig", text, confidence)
        if (command == lastCommand && nowMillis - lastMillis < debounceMillis) {
            return VoiceDecision.Ignore("Wiederholung", text, confidence)
        }
        notApplicable(command, context)?.let { return VoiceDecision.Ignore(it, text, confidence) }

        lastCommand = command
        lastMillis = nowMillis
        return VoiceDecision.Execute(command, text, confidence)
    }

    private fun notApplicable(command: VoiceCommand, c: VoiceContext): String? = when (command) {
        VoiceCommand.START_RECORDING -> if (c.recording) "Aufnahme läuft schon" else null
        VoiceCommand.STOP -> if (!c.recording) "keine Aufnahme" else null
        VoiceCommand.TRACKING_ON -> when {
            !c.gimbalReady -> "kein Gimbal"
            c.trackingOn -> "Tracking ist schon an"
            else -> null
        }
        VoiceCommand.TRACKING_OFF -> if (!c.trackingOn) "Tracking ist schon aus" else null
        VoiceCommand.HOME -> if (!c.gimbalReady) "kein Gimbal" else null
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 1_500L
        val TEXT = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"")
        val CONF = Regex("\"conf\"\\s*:\\s*([0-9.eE+-]+)")
    }
}
