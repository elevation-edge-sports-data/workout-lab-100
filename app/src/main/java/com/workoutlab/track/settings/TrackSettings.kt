package com.workoutlab.track.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrackSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _eventLock = MutableStateFlow(prefs.getBoolean(KEY_EVENT_LOCK, false))
    val eventLock: StateFlow<Boolean> = _eventLock.asStateFlow()

    private val _autostartEnabled = MutableStateFlow(!_eventLock.value)
    val autostartEnabled: StateFlow<Boolean> = _autostartEnabled.asStateFlow()

    fun setAutostartEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTOSTART, enabled)
            .putBoolean(KEY_EVENT_LOCK, !enabled)
            .apply()
        _autostartEnabled.value = enabled
        _eventLock.value = !enabled
    }

    fun setEventLock(enabled: Boolean) {
        setAutostartEnabled(!enabled)
    }

    fun watcherShouldRun(): Boolean = autostartEnabled.value

    private val _inactivityFinishSec = MutableStateFlow(
        prefs.getInt(KEY_INACTIVITY_SEC, DEFAULT_INACTIVITY_SEC).let { sec ->
            INACTIVITY_OPTIONS.minByOrNull { kotlin.math.abs(it - sec) } ?: DEFAULT_INACTIVITY_SEC
        },
    )
    val inactivityFinishSec: StateFlow<Int> = _inactivityFinishSec.asStateFlow()

    fun setInactivityFinishSec(seconds: Int) {
        val value = INACTIVITY_OPTIONS.minByOrNull { kotlin.math.abs(it - seconds) } ?: DEFAULT_INACTIVITY_SEC
        prefs.edit().putInt(KEY_INACTIVITY_SEC, value).apply()
        _inactivityFinishSec.value = value
    }

    private val _recentSortByDistance = MutableStateFlow(prefs.getBoolean(KEY_RECENT_SORT_DISTANCE, false))
    val recentSortByDistance: StateFlow<Boolean> = _recentSortByDistance.asStateFlow()

    fun setRecentSortByDistance(byDistance: Boolean) {
        prefs.edit().putBoolean(KEY_RECENT_SORT_DISTANCE, byDistance).apply()
        _recentSortByDistance.value = byDistance
    }

    companion object {
        private const val PREFS = "workout_lab_track"
        private const val KEY_AUTOSTART = "autostart_enabled"
        private const val KEY_EVENT_LOCK = "event_lock"
        private const val KEY_INACTIVITY_SEC = "inactivity_finish_sec"
        private const val KEY_RECENT_SORT_DISTANCE = "recent_sort_by_distance"
        const val DEFAULT_INACTIVITY_SEC = 90
        val INACTIVITY_OPTIONS = listOf(60, 90, 120)
    }
}
