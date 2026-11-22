package dev.smartgimbal.voice

import android.content.Context
import dev.smartgimbal.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * Vosk speech recognition on the app's own microphone stream ([MicCapture.Consumer]).
 *
 * The model is unpacked from the assets on first use and then loaded; [state] tells when it is
 * ready. Recognition is restricted to a fixed grammar, so anything else comes back as "[unk]".
 * Results (Vosk JSON) are handed to [onResult] on the capture thread.
 */
class VoiceEngine(
    private val context: Context,
    private val grammar: List<String>,
    private val onResult: (json: String) -> Unit,
) : MicCapture.Consumer {

    enum class State { IDLE, LOADING, READY, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Guards the native objects: audio arrives on the capture thread, [close] runs on the main thread. */
    private val lock = Any()
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var closed = false

    fun load() {
        if (_state.value != State.IDLE) return
        _state.value = State.LOADING
        val start = System.currentTimeMillis()
        StorageService.unpack(
            context, ASSET_DIR, TARGET_DIR,
            { loaded ->
                synchronized(lock) {
                    if (closed) {
                        // Closed while the model was still unpacking: nobody would free it later.
                        loaded.close()
                        return@unpack
                    }
                    model = loaded
                    recognizer = Recognizer(loaded, MicCapture.SAMPLE_RATE.toFloat(), grammarJson()).apply { setWords(true) }
                }
                _state.value = State.READY
                AppLog.d { "VOICE model ready in ${System.currentTimeMillis() - start} ms, grammar=$grammar" }
            },
            { error ->
                _state.value = State.FAILED
                AppLog.e("VOICE model failed: ${error.message}", error)
            },
        )
    }

    override fun onAudio(samples: ShortArray, count: Int) {
        // Recognise under the lock so close() cannot free the recogniser mid-call.
        val json = synchronized(lock) {
            val r = recognizer ?: return
            if (r.acceptWaveForm(samples, count)) r.result else null
        }
        json?.let(onResult)
    }

    fun close() = synchronized(lock) {
        closed = true
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    private fun grammarJson(): String = (grammar + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }

    private companion object {
        const val ASSET_DIR = "model-de"
        const val TARGET_DIR = "model"
    }
}
