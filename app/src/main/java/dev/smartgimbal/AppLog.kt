package dev.smartgimbal

import android.util.Log

/**
 * Logcat output under one tag, readable with `adb logcat -s SmartGimbal`.
 *
 * Debug lines exist only in debug builds: they carry recognised speech, the gimbal's Bluetooth
 * address and media URIs, and several are written per analysed frame. The message is a lambda so
 * a release build does not even format the string.
 */
object AppLog {
    const val TAG = "SmartGimbal"

    inline fun d(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }
}
