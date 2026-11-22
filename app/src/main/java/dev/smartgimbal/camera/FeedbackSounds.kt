package dev.smartgimbal.camera

import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import dev.smartgimbal.AppLog

/**
 * Short sounds so the camera can be operated without looking at the screen: a tick per second of
 * the photo countdown, the shutter click and a blip when the zoom changes.
 *
 * Tones follow the media volume. Callers skip them while a video is recording, because they
 * would end up in its sound track. Main thread only.
 */
class FeedbackSounds {

    // Creating a ToneGenerator can fail when the audio system is out of tracks; then stay silent.
    private val tones: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
    } catch (e: RuntimeException) {
        AppLog.w("No tone generator, feedback sounds are off", e)
        null
    }
    private val shutter = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    /** One second of the countdown; the last one before the photo sounds longer. */
    fun countdownTick(last: Boolean) {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, if (last) LAST_TICK_MS else TICK_MS)
    }

    fun shutter() = shutter.play(MediaActionSound.SHUTTER_CLICK)

    fun zoomChanged() {
        tones?.startTone(ToneGenerator.TONE_PROP_ACK, ZOOM_MS)
    }

    fun release() {
        tones?.release()
        shutter.release()
    }

    private companion object {
        /** Percent of the media volume. */
        const val TONE_VOLUME = 80
        const val TICK_MS = 80
        const val LAST_TICK_MS = 300
        const val ZOOM_MS = 100
    }
}
