package com.workoutlab.track.map

import com.workoutlab.track.recording.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSmoothingTest {
    @Test
    fun keepsSegmentEndsAndDoesNotBlendAcrossGap() {
        val points = listOf(
            PathPoint(40.0, -74.0, startsSegment = true),
            PathPoint(40.001, -74.001),
            PathPoint(40.002, -74.002),
            PathPoint(34.0, -118.0, startsSegment = true),
            PathPoint(34.001, -118.001),
            PathPoint(34.002, -118.002),
        )
        val smooth = PathSmoothing.light(points)
        assertEquals(6, smooth.size)
        assertEquals(40.0, smooth.first().latitude, 0.0)
        assertEquals(34.002, smooth.last().latitude, 0.0)
        assertTrue(smooth[3].startsSegment)
        assertEquals(34.0, smooth[3].latitude, 0.0)
        assertTrue(smooth[1].latitude in 40.000..40.002)
    }
}
