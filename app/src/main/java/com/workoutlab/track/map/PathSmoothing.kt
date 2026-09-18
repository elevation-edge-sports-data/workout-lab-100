package com.workoutlab.track.map

import com.workoutlab.track.recording.PathPoint

/**
 * Light display-only smoothing. Distance and saved points stay unsmoothed.
 * Does not blend across a segment break (drive gap).
 */
object PathSmoothing {
    fun light(points: List<PathPoint>): List<PathPoint> {
        if (points.size < 3) return points
        val out = ArrayList<PathPoint>(points.size)
        var start = 0
        while (start < points.size) {
            var end = start + 1
            while (end < points.size && !points[end].startsSegment) end++
            out += smoothSegment(points.subList(start, end))
            start = end
        }
        return out
    }

    private fun smoothSegment(segment: List<PathPoint>): List<PathPoint> {
        if (segment.size < 3) return segment.toList()
        return segment.mapIndexed { index, point ->
            if (index == 0 || index == segment.lastIndex) {
                point
            } else {
                val prev = segment[index - 1]
                val next = segment[index + 1]
                PathPoint(
                    latitude = 0.25 * prev.latitude + 0.5 * point.latitude + 0.25 * next.latitude,
                    longitude = 0.25 * prev.longitude + 0.5 * point.longitude + 0.25 * next.longitude,
                    startsSegment = false,
                )
            }
        }
    }
}
