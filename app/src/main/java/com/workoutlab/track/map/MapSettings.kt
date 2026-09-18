package com.workoutlab.track.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MapSettings(context: Context) {
    private val prefs = context.getSharedPreferences("workout_lab_track", Context.MODE_PRIVATE)
    private val _tilesEnabled = MutableStateFlow(prefs.getBoolean(KEY_TILES, true))
    val tilesEnabled: StateFlow<Boolean> = _tilesEnabled.asStateFlow()

    fun setTilesEnabled(enabled: Boolean, loader: OsmTileLoader? = null) {
        prefs.edit().putBoolean(KEY_TILES, enabled).apply()
        _tilesEnabled.value = enabled
        loader?.networkAllowed = enabled
    }

    companion object {
        private const val KEY_TILES = "map_tiles_enabled"
    }
}
