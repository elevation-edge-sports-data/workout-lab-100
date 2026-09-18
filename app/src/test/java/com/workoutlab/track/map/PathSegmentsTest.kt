package com.workoutlab.track.map

import com.workoutlab.track.recording.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class PathSegmentsTest {
    @Test
    fun currentSegmentIgnoresEarlierNeighborhood() {
        val points = listOf(
            PathPoint(40.0, -74.0, startsSegment = true),
            PathPoint(40.001, -74.001),
            PathPoint(34.0, -118.0, startsSegment = true),
            PathPoint(34.001, -118.001),
        )
        val current = PathSegments.current(points)
        assertEquals(2, current.size)
        assertEquals(34.0, current.first().latitude, 0.0)
        assertEquals(-118.0, current.first().longitude, 0.0)
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals(0, PathSegments.current(emptyList()).size)
    }
}
