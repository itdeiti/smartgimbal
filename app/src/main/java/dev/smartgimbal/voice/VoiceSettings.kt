package dev.smartgimbal.voice

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceParams(
    /** Voice control on/off (the mic is only used while the camera screen is visible). */
    val enabled: Boolean = true,
    /** Minimum Vosk word confidence for a command. */
    val threshold: Float = 0.7f,
)

/** [VoiceParams] persisted in SharedPreferences. */
class VoiceSettings(context: Context) {

    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val defaults = VoiceParams()

    private val _params = MutableStateFlow(
        VoiceParams(
            enabled = prefs.getBoolean("enabled", defaults.enabled),
            threshold = prefs.getFloat("threshold", defaults.threshold),
        ),
    )
    val params: StateFlow<VoiceParams> = _params.asStateFlow()

    fun update(transform: (VoiceParams) -> VoiceParams) {
        val next = transform(_params.value)
        _params.value = next
        prefs.edit {
            putBoolean("enabled", next.enabled)
            putFloat("threshold", next.threshold)
        }
    }
}
