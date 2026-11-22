package dev.smartgimbal.gimbal

/** Speed command in operator terms, see [dev.smartgimbal.protocol.FeiyuProtocol.move]. */
data class MoveCommand(val panRight: Int, val tiltUp: Int) {
    val isStop: Boolean get() = panRight == 0 && tiltUp == 0

    companion object {
        val STOP = MoveCommand(0, 0)
    }
}

enum class CommandSource { MANUAL, TRACKER }

data class ArbitratedCommand(val command: MoveCommand, val source: CommandSource?)

/**
 * Picks which command the send loop transmits.
 *
 * The D-pad always wins over the tracker. A tracker command is only used while it is fresh, so the
 * gimbal stops by itself when face detection stalls. Thread-safe: inputs arrive from the UI thread
 * and the camera analysis thread.
 */
class CommandArbiter(private val trackerTimeoutMillis: Long = DEFAULT_TRACKER_TIMEOUT_MS) {

    private class Stamped(val command: MoveCommand, val atMillis: Long)

    @Volatile private var manual: MoveCommand? = null
    @Volatile private var tracker: Stamped? = null

    /** `null` means no D-pad button is held. */
    fun setManual(command: MoveCommand?) {
        manual = command
    }

    /** `null` withdraws the tracker, e.g. when tracking is switched off. */
    fun setTracker(command: MoveCommand?, nowMillis: Long) {
        tracker = command?.let { Stamped(it, nowMillis) }
    }

    fun current(nowMillis: Long): ArbitratedCommand {
        manual?.let { return ArbitratedCommand(it, CommandSource.MANUAL) }
        val t = tracker
        if (t != null && nowMillis - t.atMillis <= trackerTimeoutMillis) {
            return ArbitratedCommand(t.command, CommandSource.TRACKER)
        }
        return ArbitratedCommand(MoveCommand.STOP, null)
    }

    companion object {
        const val DEFAULT_TRACKER_TIMEOUT_MS = 300L
    }
}
