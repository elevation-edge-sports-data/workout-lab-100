package com.workoutlab.track.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun oneDegreeLatitudeIsAbout111km() {
        val meters = Geo.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, meters, 250.0)
    }

    @Test
    fun samePointIsZero() {
        assertEquals(0.0, Geo.haversineMeters(40.0, -74.0, 40.0, -74.0), 0.0001)
    }

    @Test
    fun accuracyIsShownInFeetNeverMeters() {
        assertEquals("GPS ±23 ft", Geo.formatAccuracyFeet(7f))
        assertEquals("GPS ±82 ft", Geo.formatAccuracyFeet(25f))
    }

    @Test
    fun quarterMileIsOnePointDisplayOnly() {
        val halfMileMeters = 0.50 * Geo.METERS_PER_MILE
        assertEquals("2.00 pts", Geo.formatPoints(halfMileMeters))
        assertEquals("1.00 pts", Geo.formatPoints(0.250 * Geo.METERS_PER_MILE))
        assertEquals("0.00 pts", Geo.formatPoints(0.0))
    }

    @Test
    fun elevenThousandthsOfAMileFormatsWithThreeDecimals() {
        val meters = 0.011 * Geo.METERS_PER_MILE
        assertEquals("0.011 mi", Geo.formatMiles(meters))
        assertEquals("0.000 mi", Geo.formatMiles(0.0))
    }

    @Test
    fun promoteTimingShowsDelayToFirstPoint() {
        val line = Geo.formatPromoteTiming(1_000L, 16_000L)
        assertTrue(line!!.contains("+15s"))
        assertTrue(line.contains("Promoted"))
        assertTrue(line.contains("first point"))
        assertTrue(Geo.formatPromoteTiming(5_000L, null)!!.contains("waiting for first point"))
    }

    @Test
    fun impliedSpeedMatchesDistanceOverTime() {
        val speed = Geo.impliedSpeedMps(
            lat1 = 0.0,
            lon1 = 0.0,
            time1Ms = 0L,
            lat2 = 0.0,
            lon2 = 0.0,
            time2Ms = 1000L,
        )
        assertEquals(0.0, speed!!, 0.0001)
    }
}
