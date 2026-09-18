package com.workoutlab.track.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

object OsmTileMath {
    const val TILE_SIZE = 256
    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 19

    fun zoomCount(zoom: Int): Int = 1 shl zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun lonToTileX(lon: Double, zoom: Int): Double =
        (lon + 180.0) / 360.0 * zoomCount(zoom)

    fun latToTileY(lat: Double, zoom: Int): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(clamped)
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * zoomCount(zoom)
    }

    fun worldX(lon: Double, zoom: Int): Double = lonToTileX(lon, zoom) * TILE_SIZE

    fun worldY(lat: Double, zoom: Int): Double = latToTileY(lat, zoom) * TILE_SIZE

    fun tileX(lon: Double, zoom: Int): Int = floor(lonToTileX(lon, zoom)).toInt()

    fun tileY(lat: Double, zoom: Int): Int = floor(latToTileY(lat, zoom)).toInt()
}

data class TileId(val zoom: Int, val x: Int, val y: Int)
