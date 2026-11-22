package dev.smartgimbal.ble

import java.util.concurrent.atomic.AtomicLong

data class LogEntry(
    val id: Long,
    val timeMillis: Long,
    val kind: Kind,
    val text: String,
) {
    enum class Kind { INFO, ERROR, TX, RX }

    companion object {
        private val nextId = AtomicLong()

        fun of(kind: Kind, text: String) =
            LogEntry(nextId.incrementAndGet(), System.currentTimeMillis(), kind, text)

        fun info(text: String) = of(Kind.INFO, text)
        fun error(text: String) = of(Kind.ERROR, text)
    }
}
