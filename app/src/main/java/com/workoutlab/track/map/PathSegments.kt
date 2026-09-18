package com.workoutlab.track.map

import com.workoutlab.track.recording.PathPoint

object PathSegments {
    /** Last contiguous workout segment only — never fit two neighborhoods after a split. */
    fun current(points: List<PathPoint>): List<PathPoint> {
        if (points.isEmpty()) return emptyList()
        val start = points.indexOfLast { it.startsSegment }.let { index ->
            if (index < 0) 0 else index
        }
        return points.subList(start, points.size)
    }
}
