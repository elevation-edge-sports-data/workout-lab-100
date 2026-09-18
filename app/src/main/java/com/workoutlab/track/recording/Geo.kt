package com.workoutlab.track.recording

import java.util.Locale
import kotlin.math.asin
import kotlin.math.round
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    const val METERS_PER_MILE = 1609.344
    const val FEET_PER_METER = 3.280839895
    const val MILES_PER_POINT = 0.250

    fun miles(meters: Double): Double = meters / METERS_PER_MILE

    fun formatMiles(meters: Double): String =
        String.format(Locale.US, "%.3f mi", miles(meters))

    fun pointsFromMiles(distanceMiles: Double): Double = distanceMiles / MILES_PER_POINT

    fun formatPoints(meters: Double): String =
        String.format(Locale.US, "%.2f pts", pointsFromMiles(miles(meters)))

    fun formatAccuracyFeet(accuracyMeters: Float): String {
        val feet = round(accuracyMeters * FEET_PER_METER).toInt()
        return "GPS ±$feet ft"
    }

    fun formatClock(epochMs: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(epochMs))

    fun formatSessionWhen(epochMs: Long): String =
        java.text.SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(java.util.Date(epochMs))

    fun formatPromoteTiming(promotedAtEpochMs: Long?, firstPointAtEpochMs: Long?): String? {
        if (promotedAtEpochMs == null) return null
        val promoted = formatClock(promotedAtEpochMs)
        if (firstPointAtEpochMs == null) {
            return "Promoted $promoted · waiting for first point"
        }
        val delaySec = ((firstPointAtEpochMs - promotedAtEpochMs) / 1000L).coerceAtLeast(0)
        return "Promoted $promoted · first point ${formatClock(firstPointAtEpochMs)} (+${delaySec}s)"
    }

    fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * asin(min(1.0, sqrt(a)))
        return EARTH_RADIUS_METERS * c
    }

    fun impliedSpeedMps(
        lat1: Double,
        lon1: Double,
        time1Ms: Long,
        lat2: Double,
        lon2: Double,
        time2Ms: Long,
    ): Double? {
        val dtSec = (time2Ms - time1Ms) / 1000.0
        if (dtSec <= 0.0) return null
        return haversineMeters(lat1, lon1, lat2, lon2) / dtSec
    }
}
