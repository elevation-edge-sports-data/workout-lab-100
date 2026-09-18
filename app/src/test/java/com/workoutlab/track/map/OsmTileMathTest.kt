package com.workoutlab.track.map

import org.junit.Assert.assertEquals
import org.junit.Test

class OsmTileMathTest {
    @Test
    fun equatorPrimeMeridianAtZoom3IsCenterTile() {
        assertEquals(4.0, OsmTileMath.lonToTileX(0.0, 3), 0.0001)
        assertEquals(4.0, OsmTileMath.latToTileY(0.0, 3), 0.0001)
    }

    @Test
    fun knownManhattanTileAtZoom12() {
        val x = OsmTileMath.tileX(-74.0060, 12)
        val y = OsmTileMath.tileY(40.7128, 12)
        assertEquals(1205, x)
        assertEquals(1540, y)
    }
}
