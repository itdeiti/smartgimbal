package dev.smartgimbal.voice

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The app's own microphone stream: 16 kHz mono PCM16, delivered in ~100 ms chunks on a
 * background thread to every registered consumer (Vosk, and in plan B also the audio encoder).
 *
 * Marked as not privacy-sensitive so other capturers (CameraX's audio track) may run at the same
 * time; whether Android actually feeds both is what the phase 3 spike measures via [lastRmsDb].
 */
class MicCapture {

    fun interface Consumer {
        /** Called on the capture thread; [samples] is only valid during the call. */
        fun onAudio(samples: ShortArray, count: Int)
    }

    private val consumers = java.util.concurrent.CopyOnWriteArrayList<Consumer>()
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Level of the last chunk in dBFS (0 = full scale, about -90 = digital silence). */
    @Volatile var lastRmsDb: Double = SILENCE_DB
        private set

    val isRunning: Boolean get() = running

    fun addConsumer(consumer: Consumer) = consumers.add(consumer)
    fun removeConsumer(consumer: Consumer) = consumers.remove(consumer)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = SAMPLE_RATE / 10 // 100 ms
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, chunk * 2 * 4))
            .setPrivacySensitive(false)
            .build()
        running = true
        thread = Thread({ loop(record, chunk) }, "mic-capture").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        lastRmsDb = SILENCE_DB
    }

    private fun loop(record: AudioRecord, chunk: Int) {
        val buffer = ShortArray(chunk)
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                lastRmsDb = rmsDb(buffer, n)
                consumers.forEach { it.onAudio(buffer, n) }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val SILENCE_DB = -90.0

        fun rmsDb(samples: ShortArray, count: Int): Double {
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples[i] / 32768.0
                sum += s * s
            }
            val rms = sqrt(sum / count)
            return if (rms <= 0.0) SILENCE_DB else max(SILENCE_DB, 20 * log10(rms))
        }
    }
}
