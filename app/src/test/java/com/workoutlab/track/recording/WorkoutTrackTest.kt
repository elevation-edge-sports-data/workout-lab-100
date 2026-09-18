package com.workoutlab.track.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutTrackTest {
    @Test
    fun accuracyWorseThan82ftAddsNoDistance() {
        val track = WorkoutTrack()
        track.onStep(0L)
        val update = track.onSample(sample(accuracy = 30f, time = 500L, speed = 2.0))
        assertTrue(update.accuracyTooWeak)
        assertNull(update.accepted)
        assertEquals(0.0, update.distanceMeters, 0.0001)
    }

    @Test
    fun gpsWithoutRecentStepAddsNoMiles() {
        val track = WorkoutTrack()
        track.onSample(sample(lat = 0.0, time = 0L, speed = 2.0))
        track.onSample(sample(lat = 0.0002, time = 1_000L, speed = 2.0))
        assertEquals(0.0, track.distanceMeters, 0.0001)
        assertEquals(0, track.pointCount)
    }

    @Test
    fun indoorJitterWithStepsStaysZero() {
        val track = WorkoutTrack()
        track.onStep(0L)
        track.onSample(sample(lat = 0.0, time = 200L, accuracy = 15f, speed = 0.2))
        repeat(8) { i ->
            track.onStep((i + 1) * 1000L)
            track.onSample(
                sample(
                    lat = 0.00004 * ((i % 3) - 1),
                    lon = 0.00004 * ((i % 2) - 0.5),
                    time = (i + 1) * 1000L + 200L,
                    accuracy = 15f,
                    speed = 0.2,
                ),
            )
        }
        assertEquals(0.0, track.distanceMeters, 0.0001)
        assertEquals("0.000 mi", Geo.formatMiles(track.distanceMeters))
    }

    @Test
    fun stepAndMovementAddsDistance() {
        val track = WorkoutTrack()
        track.onStep(0L)
        track.onSample(sample(lat = 0.0, time = 200L, speed = 2.0))
        track.onStep(1_000L)
        val update = track.onSample(sample(lat = 0.0002, time = 1_200L, speed = 2.0))
        assertTrue(update.distanceMeters > 8.0)
        assertEquals(2, update.pathPoints.size)
    }

    @Test
    fun vehicleGapDoesNotDrawAcrossDrive() {
        val track = WorkoutTrack()
        track.onStep(0L)
        track.onSample(sample(lat = 0.0, time = 200L, speed = 2.0))
        track.onStep(1_000L)
        track.onSample(sample(lat = 0.0002, time = 1_200L, speed = 2.0))
        val afterWalk = track.distanceMeters
        assertTrue(afterWalk > 0.0)

        val vehicle = track.onSample(
            sample(lat = 0.01, time = 10_000L, speed = GaitClassifier.SPEED_BACKSTOP_MPS + 1),
        )
        assertEquals(MotionGuess.LIKELY_VEHICLE, vehicle.guess)
        assertEquals(afterWalk, track.distanceMeters, 0.0001)

        track.onStep(12_000L)
        track.onSample(sample(lat = 1.0, time = 12_200L, speed = 2.0))
        track.onStep(13_000L)
        val resumed = track.onSample(sample(lat = 1.0002, time = 13_200L, speed = 2.0))
        assertTrue(track.distanceMeters < 5_000.0)
        assertTrue(resumed.pathPoints.last().startsSegment || resumed.pathPoints.any { it.startsSegment })
        assertTrue(track.distanceMeters > afterWalk)
    }

    @Test
    fun jumpFartherThan80ftInUnder2sAddsNoMilesOrPoint() {
        val track = WorkoutTrack()
        track.onStep(0L)
        track.onSample(sample(lat = 0.0, time = 200L, speed = 2.0))
        track.onStep(1_000L)
        val after = track.onSample(sample(lat = 0.0004, time = 1_200L, speed = 20.0))
        assertTrue(after.dropAsJump)
        assertNull(after.accepted)
        assertEquals(1, after.pathPoints.size)
        assertEquals(0.0, after.distanceMeters, 0.0001)
    }

    @Test
    fun jumpFartherThan3xAccuracyInUnder2sAddsNoMiles() {
        val track = WorkoutTrack()
        track.onStep(0L)
        track.onSample(sample(lat = 0.0, time = 200L, accuracy = 5f, speed = 2.0))
        track.onStep(1_000L)
        val after = track.onSample(
            sample(lat = 0.000162, time = 1_200L, accuracy = 5f, speed = 8.0),
        )
        assertTrue(after.dropAsJump)
        assertNull(after.accepted)
        assertEquals(0.0, after.distanceMeters, 0.0001)
        assertEquals(1, after.pathPoints.size)
    }

    @Test
    fun rejectedJumpDoesNotCatchUpLaterDistance() {
        val track = WorkoutTrack()
        track.onStep(0L)
        track.onSample(sample(lat = 0.0, time = 200L, speed = 2.0))
        track.onStep(1_000L)
        track.onSample(sample(lat = 0.0004, time = 1_200L, speed = 20.0))
        track.onStep(2_000L)
        val later = track.onSample(sample(lat = 0.0004, time = 3_500L, speed = 2.0))
        assertTrue(later.dropAsJump)
        assertNull(later.accepted)
        assertEquals(0.0, track.distanceMeters, 0.0001)
        assertEquals(1, track.pointCount)
    }

    @Test
    fun inactivityTimeoutFinishesWithoutVehicle() {
        val track = WorkoutTrack()
        track.markActivity(0L)
        assertTrue(!track.shouldFinishInactivity(59_000L, 60_000L))
        assertTrue(track.shouldFinishInactivity(60_000L, 60_000L))
        track.onStep(61_000L)
        assertTrue(!track.shouldFinishInactivity(90_000L, 60_000L))
        assertTrue(track.shouldFinishInactivity(121_000L, 60_000L))
    }

    @Test
    fun pauseGapDoesNotAddDistanceAcrossResume() {
        val track = WorkoutTrack()
        track.onStep(0L)
        track.onSample(sample(lat = 0.0, time = 200L, speed = 2.0))
        track.onStep(1_000L)
        val update = track.onSample(sample(lat = 0.0002, time = 1_200L, speed = 2.0))
        val beforePause = update.distanceMeters
        val points = update.pathPoints
        track.markGap()
        track.onStep(60_000L)
        track.onSample(sample(lat = 1.0, time = 60_200L, speed = 2.0))
        assertEquals(beforePause, track.distanceMeters, 0.0001)
        track.restore(points, beforePause, 0)
        assertEquals(beforePause, track.distanceMeters, 0.0001)
        assertEquals(points.size, track.pointCount)
    }

    private fun sample(
        lat: Double = 0.0,
        lon: Double = 0.0,
        time: Long = 0L,
        speed: Double = 2.0,
        accuracy: Float = 8f,
    ) = GeoSample(
        latitude = lat,
        longitude = lon,
        altitudeMeters = null,
        accuracyMeters = accuracy,
        speedMps = speed,
        timestampEpochMs = time,
    )
}
