package dev.smartgimbal.voice

import dev.smartgimbal.camera.ZoomLevel

/** Spoken commands; the recognised text must match one of the [phrases] exactly. */
enum class VoiceCommand(val label: String, vararg val phrases: String) {
    START_RECORDING("Aufnahme starten", "aufnahme starten"),

    // "Aufnahme stoppen" must be in the grammar: without it Vosk heard the closest phrase,
    // "starten" (voice test 2026-11-22). "stoppen" / "abbrechen" alone cancel the self-timer
    // naturally; a lone "stoppen" was ignored in the photo test.
    STOP("Stopp", "stopp", "aufnahme stopp", "aufnahme stoppen", "stoppen", "abbrechen"),
    TRACKING_ON("Tracking an", "tracking an"),
    TRACKING_OFF("Tracking aus", "tracking aus"),

    // "gimbal" is not in the German model's vocabulary, hence "kamera zurück" for Home.
    HOME("Kamera zurück", "kamera zurück"),

    // "cheese" is not in the vocabulary either; a spoken "cheese" lands on "käse" (confirmed).
    PHOTO("Käse – Foto in 3 s", "käse"),
    WIDE_ON("Weitwinkel an", "weitwinkel an"),
    WIDE_OFF("Weitwinkel aus", "weitwinkel aus"),
    ZOOM_2("Zoom 2×", "zoom zwei"),
    ZOOM_4("Zoom 4×", "zoom vier"),
    ZOOM_OFF("Zoom aus", "zoom aus", "zoom normal"),
    ;

    companion object {
        val GRAMMAR: List<String> = entries.flatMap { it.phrases.toList() }
    }
}

/** App state the commands are checked against. */
data class VoiceContext(
    val recording: Boolean,
    val gimbalReady: Boolean,
    val trackingOn: Boolean,
    val photoCountdown: Boolean = false,
    val zoom: ZoomLevel = ZoomLevel.NORMAL,
    val frontCamera: Boolean = false,
    /** Highest zoom ratio the active camera supports. */
    val maxZoom: Float = 8f,
)

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
        VoiceCommand.START_RECORDING -> when {
            c.recording -> "Aufnahme läuft schon"
            c.photoCountdown -> "Selbstauslöser läuft schon"
            else -> null
        }
        // Stop ends a recording or cancels the photo self-timer.
        VoiceCommand.STOP -> if (!c.recording && !c.photoCountdown) "keine Aufnahme" else null
        VoiceCommand.TRACKING_ON -> when {
            !c.gimbalReady -> "kein Gimbal"
            c.trackingOn -> "Tracking ist schon an"
            else -> null
        }
        VoiceCommand.TRACKING_OFF -> if (!c.trackingOn) "Tracking ist schon aus" else null
        VoiceCommand.HOME -> if (!c.gimbalReady) "kein Gimbal" else null
        VoiceCommand.PHOTO -> when {
            c.recording -> "Foto nur ohne Aufnahme"
            c.photoCountdown -> "Selbstauslöser läuft schon"
            else -> null
        }
        VoiceCommand.WIDE_ON -> when {
            c.frontCamera -> "Frontkamera hat kein Weitwinkel"
            c.zoom == ZoomLevel.WIDE -> "Weitwinkel ist schon an"
            else -> null
        }
        VoiceCommand.WIDE_OFF -> if (c.zoom != ZoomLevel.WIDE) "Weitwinkel ist schon aus" else null
        VoiceCommand.ZOOM_2 -> zoomCheck(ZoomLevel.X2, c)
        VoiceCommand.ZOOM_4 -> zoomCheck(ZoomLevel.X4, c)
        VoiceCommand.ZOOM_OFF -> if (c.zoom == ZoomLevel.NORMAL) "Zoom ist schon aus" else null
    }

    private fun zoomCheck(level: ZoomLevel, c: VoiceContext): String? = when {
        c.zoom == level -> "Zoom ist schon ${level.label}"
        (level.ratio ?: 1f) > c.maxZoom -> "Kamera kann kein ${level.label}"
        else -> null
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 1_500L
        val TEXT = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"")
        val CONF = Regex("\"conf\"\\s*:\\s*([0-9.eE+-]+)")
    }
}
