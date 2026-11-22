package dev.smartgimbal.voice

import android.content.Context
import dev.smartgimbal.PersistedParams

data class VoiceParams(
    /** Voice control on/off (the mic is only used while the camera screen is visible). */
    val enabled: Boolean = true,
    /** Minimum Vosk word confidence for a command. */
    val threshold: Float = 0.7f,
)

/** [VoiceParams] persisted in SharedPreferences. */
class VoiceSettings(context: Context) : PersistedParams<VoiceParams>(
    prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE),
    load = { prefs ->
        VoiceParams(
            enabled = prefs.getBoolean(ENABLED, DEFAULTS.enabled),
            threshold = prefs.getFloat(THRESHOLD, DEFAULTS.threshold),
        )
    },
    save = { p ->
        putBoolean(ENABLED, p.enabled)
        putFloat(THRESHOLD, p.threshold)
    },
) {
    private companion object {
        val DEFAULTS = VoiceParams()
        const val ENABLED = "enabled"
        const val THRESHOLD = "threshold"
    }
}
