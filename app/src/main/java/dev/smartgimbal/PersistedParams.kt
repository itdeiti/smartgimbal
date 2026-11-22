package dev.smartgimbal

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A parameter set kept in SharedPreferences and exposed as a StateFlow.
 *
 * Changes apply at once; the write to disk waits until the value has been quiet for
 * [SAVE_DELAY_MS], so dragging a slider does not rewrite the file on every step.
 */
open class PersistedParams<T>(
    private val prefs: SharedPreferences,
    load: (SharedPreferences) -> T,
    private val save: SharedPreferences.Editor.(T) -> Unit,
) {
    private val _params = MutableStateFlow(load(prefs))
    val params: StateFlow<T> = _params.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { prefs.edit { save(_params.value) } }

    fun update(transform: (T) -> T) {
        _params.value = transform(_params.value)
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private companion object {
        const val SAVE_DELAY_MS = 300L
    }
}
