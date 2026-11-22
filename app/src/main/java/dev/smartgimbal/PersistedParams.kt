package dev.smartgimbal

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A parameter set kept in SharedPreferences and exposed as a StateFlow. */
open class PersistedParams<T>(
    private val prefs: SharedPreferences,
    load: (SharedPreferences) -> T,
    private val save: SharedPreferences.Editor.(T) -> Unit,
) {
    private val _params = MutableStateFlow(load(prefs))
    val params: StateFlow<T> = _params.asStateFlow()

    fun update(transform: (T) -> T) {
        val next = transform(_params.value)
        _params.value = next
        prefs.edit { save(next) }
    }
}
