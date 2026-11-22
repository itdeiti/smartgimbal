package dev.smartgimbal

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/** Plays [effect] on the default vibrator; does nothing on devices without one. */
fun Context.vibrate(effect: VibrationEffect) {
    getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
}
