package dev.smartgimbal.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import dev.smartgimbal.gimbal.GimbalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Voice control: microphone, Vosk, command decisions and feedback.
 *
 * Listens only while the camera screen is in the foreground ([onScreenResumed]) and voice is
 * enabled. Executed commands vibrate, show a short note and go to [onCommand]; [contextProvider]
 * supplies the app state the commands are checked against.
 */
class VoiceControl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val contextProvider: () -> VoiceContext,
    private val onCommand: (VoiceCommand) -> Unit,
) {
    val settings = VoiceSettings(context)
    private val mic = MicCapture()
    private val engine = VoiceEngine(context, VoiceCommand.GRAMMAR, ::onResult)
    private val parser = VoiceCommandParser()

    val engineState: StateFlow<VoiceEngine.State> = engine.state

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** Short note for the camera screen, e.g. "Sprachbefehl: Stopp"; cleared by the screen. */
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    /** Last decisions, newest first, for tuning in the debug screen. */
    private val _history = MutableStateFlow<List<VoiceDecision>>(emptyList())
    val history: StateFlow<List<VoiceDecision>> = _history.asStateFlow()

    private val _level = MutableStateFlow(MicCapture.SILENCE_DB)
    val level: StateFlow<Double> = _level.asStateFlow()

    private var screenActive = false
    private var levelJob: Job? = null

    init {
        mic.addConsumer(engine)
        engine.load()
    }

    val hasMicPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun onScreenResumed() {
        screenActive = true
        updateListening()
    }

    fun onScreenPaused() {
        screenActive = false
        updateListening()
    }

    fun setEnabled(enabled: Boolean) {
        settings.update { it.copy(enabled = enabled) }
        updateListening()
    }

    fun setThreshold(value: Float) = settings.update { it.copy(threshold = value) }

    fun clearFeedback() {
        _feedback.value = null
    }

    @SuppressLint("MissingPermission") // hasMicPermission is checked
    private fun updateListening() {
        val shouldListen = screenActive && settings.params.value.enabled && hasMicPermission
        if (shouldListen == mic.isRunning) return
        if (shouldListen) {
            mic.start()
            levelJob = scope.launch {
                var n = 0
                while (isActive) {
                    delay(LEVEL_INTERVAL_MS)
                    _level.value = mic.lastRmsDb
                    if (++n % LEVEL_LOG_EVERY == 0) Log.d(GimbalSession.LOG_TAG, "VOICE rms=%.1f dB".format(Locale.ROOT, mic.lastRmsDb))
                }
            }
        } else {
            levelJob?.cancel()
            mic.stop()
            _level.value = MicCapture.SILENCE_DB
        }
        _listening.value = shouldListen
        Log.d(GimbalSession.LOG_TAG, "VOICE ${if (shouldListen) "listening" else "stopped"}")
    }

    /** Called on the capture thread for every finished utterance. */
    private fun onResult(json: String) {
        val decision = parser.decide(json, contextProvider(), settings.params.value.threshold.toDouble(), SystemClock.elapsedRealtime())
        if (decision.text.isEmpty()) return
        _history.update { (listOf(decision) + it).take(HISTORY_SIZE) }
        when (decision) {
            is VoiceDecision.Execute -> {
                Log.d(GimbalSession.LOG_TAG, "VOICE exec ${decision.command} text='${decision.text}' conf=%.2f".format(Locale.ROOT, decision.confidence))
                vibrate()
                _feedback.value = "Sprachbefehl: ${decision.command.label}"
                onCommand(decision.command)
            }
            is VoiceDecision.Ignore -> {
                Log.d(GimbalSession.LOG_TAG, "VOICE ignore '${decision.text}' conf=%.2f reason=${decision.reason}".format(Locale.ROOT, decision.confidence))
                // Tell the user when a real command does not fit the current state.
                val stateReason = decision.reason in STATE_REASONS ||
                    decision.reason.startsWith("Zoom ist schon") || decision.reason.startsWith("Kamera kann kein")
                if (stateReason) _feedback.value = "Sprachbefehl ignoriert: ${decision.reason}"
            }
        }
    }

    private fun vibrate() {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    }

    fun close() {
        levelJob?.cancel()
        mic.stop()
        engine.close()
    }

    private companion object {
        const val HISTORY_SIZE = 12
        const val LEVEL_INTERVAL_MS = 500L
        const val LEVEL_LOG_EVERY = 10
        val STATE_REASONS = setOf(
            "Aufnahme läuft schon", "keine Aufnahme", "kein Gimbal", "Tracking ist schon an", "Tracking ist schon aus",
            "Foto nur ohne Aufnahme", "Selbstauslöser läuft schon", "Frontkamera hat kein Weitwinkel",
            "Weitwinkel ist schon an", "Weitwinkel ist schon aus", "Zoom ist schon aus",
        )
    }
}
