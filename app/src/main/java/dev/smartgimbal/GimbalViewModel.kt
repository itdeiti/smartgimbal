package dev.smartgimbal

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartgimbal.ble.GimbalBleClient
import dev.smartgimbal.ble.LogEntry
import dev.smartgimbal.protocol.FeiyuProtocol
import dev.smartgimbal.protocol.Frame
import dev.smartgimbal.protocol.FrameAssembler
import dev.smartgimbal.protocol.Telemetry
import dev.smartgimbal.protocol.parseHex
import dev.smartgimbal.protocol.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GimbalViewModel(app: Application) : AndroidViewModel(app) {

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    val client = GimbalBleClient(app, ::appendLog, ::onRx)

    /** Latest telemetry frame from the gimbal. */
    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()

    /** Reference for the delta columns, set with [zeroTelemetry]. */
    private val _telemetryZero = MutableStateFlow<Telemetry?>(null)
    val telemetryZero: StateFlow<Telemetry?> = _telemetryZero.asStateFlow()

    private val _telemetryCount = MutableStateFlow(0)
    val telemetryCount: StateFlow<Int> = _telemetryCount.asStateFlow()

    /** Telemetry arrives about 2.4 times per second; hidden from the log by default. */
    private val _logTelemetry = MutableStateFlow(false)
    val logTelemetry: StateFlow<Boolean> = _logTelemetry.asStateFlow()

    /** Magnitude sent for X/Y while a direction is held. Useful range not known yet. */
    private val _strength = MutableStateFlow(DEFAULT_STRENGTH)
    val strength: StateFlow<Int> = _strength.asStateFlow()

    /** Joystick frames go out at 20 Hz and would flood the log, so they are hidden by default. */
    private val _logJoystick = MutableStateFlow(false)
    val logJoystick: StateFlow<Boolean> = _logJoystick.asStateFlow()

    private val assembler = FrameAssembler()

    /** Held direction as (right, up), each -1, 0 or 1. */
    private val direction = MutableStateFlow(0 to 0)

    init {
        viewModelScope.launch { joystickLoop() }
    }

    /** [right] and [up] are -1, 0 or 1 in operator terms, see [FeiyuProtocol.move]. */
    fun press(right: Int, up: Int) {
        direction.value = right to up
        val s = _strength.value
        appendLog(LogEntry.info("Taste ${arrowFor(right, up)} gedrückt (panRight=${right * s}, tiltUp=${up * s})"))
    }

    fun release() {
        direction.value = 0 to 0
        appendLog(LogEntry.info("Taste losgelassen"))
    }

    fun zeroTelemetry() {
        _telemetryZero.value = _telemetry.value
        appendLog(LogEntry.info("Nullpunkt gesetzt: ${_telemetry.value}"))
    }

    fun setLogTelemetry(enabled: Boolean) {
        _logTelemetry.value = enabled
    }

    /** Called on a binder thread for every RX notification; frames may span notifications. */
    private fun onRx(chunk: ByteArray) {
        val pieces = synchronized(assembler) { assembler.feed(chunk) }
        pieces.forEach(::handlePiece)
    }

    private fun handlePiece(piece: FrameAssembler.Piece) {
        val bytes = piece.bytes
        if (piece is FrameAssembler.Piece.Garbage) {
            appendLog(LogEntry.of(LogEntry.Kind.RX, "${bytes.toHex()}  [verworfen]"))
            return
        }
        val frame = Frame.decode(bytes)
        if (frame == null) {
            appendLog(LogEntry.of(LogEntry.Kind.RX, "${bytes.toHex()}  [Länge passt nicht]"))
            return
        }
        val telemetry = Telemetry.fromFrame(frame)
        if (telemetry != null) {
            _telemetry.value = telemetry
            _telemetryCount.update { it + 1 }
            // Always in logcat so test runs can be analysed; in the UI log only on request.
            Log.d(
                LOG_TAG,
                "TELEM pan=${telemetry.pan} tilt=${telemetry.tilt} roll=${telemetry.roll} d=${telemetry.d} s=${telemetry.status}",
            )
            if (_logTelemetry.value) appendLog(LogEntry.of(LogEntry.Kind.RX, bytes.toHex()), mirror = false)
            return
        }
        // Anything that is not telemetry is rare and always worth seeing.
        appendLog(LogEntry.of(LogEntry.Kind.RX, "${bytes.toHex()}  [$frame]"))
    }

    private fun arrowFor(right: Int, up: Int) = when {
        up > 0 -> "▲"
        up < 0 -> "▼"
        right > 0 -> "▶"
        else -> "◀"
    }

    fun setStrength(value: Int) {
        _strength.value = value
    }

    fun setLogJoystick(enabled: Boolean) {
        _logJoystick.value = enabled
    }

    fun home() {
        viewModelScope.launch { client.send(FeiyuProtocol.home()) }
    }

    /**
     * Sends a hand-written frame, for exploring unknown commands.
     * With [appendCrc] the input must start with `A5 5A` and leave out the CRC.
     */
    fun sendRaw(hex: String, appendCrc: Boolean) {
        val frame = try {
            val bytes = parseHex(hex)
            if (appendCrc) FeiyuProtocol.withCrc(bytes) else bytes
        } catch (e: IllegalArgumentException) {
            appendLog(LogEntry.error(e.message ?: "Ungültige Eingabe"))
            return
        }
        if (frame.isEmpty()) return
        viewModelScope.launch {
            if (!client.send(frame)) appendLog(LogEntry.error("Nicht gesendet (nicht verbunden?)"))
        }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    /**
     * Sends the held direction every [SEND_INTERVAL_MS] ms. On release it sends a zero frame twice
     * so the gimbal stops even if one frame is lost.
     */
    private suspend fun joystickLoop() {
        var wasMoving = false
        while (viewModelScope.isActive) {
            val (dx, dy) = direction.value
            val moving = dx != 0 || dy != 0
            if (moving) {
                val s = _strength.value
                client.send(FeiyuProtocol.move(dx * s, dy * s), _logJoystick.value)
                wasMoving = true
            } else if (wasMoving) {
                repeat(2) { client.send(FeiyuProtocol.joystick(0, 0), logIt = true) }
                wasMoving = false
            }
            delay(SEND_INTERVAL_MS)
        }
    }

    private fun appendLog(entry: LogEntry, mirror: Boolean = true) {
        // Mirror to logcat so test sessions can be read with `adb logcat -s SmartGimbal`.
        if (mirror) Log.d(LOG_TAG, "${entry.kind.name.padEnd(5)} ${entry.text}")
        _log.update { (listOf(entry) + it).take(MAX_LOG_ENTRIES) }
    }

    override fun onCleared() {
        client.close()
    }

    companion object {
        /** 20 Hz, within the 15 to 25 Hz the spec allows. */
        const val SEND_INTERVAL_MS = 50L
        const val DEFAULT_STRENGTH = 200
        const val MAX_STRENGTH = 1000
        private const val MAX_LOG_ENTRIES = 500
        private const val LOG_TAG = "SmartGimbal"
    }
}
