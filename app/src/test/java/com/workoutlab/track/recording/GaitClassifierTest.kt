package com.workoutlab.track.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GaitClassifierTest {
    private val backstop = GaitClassifier.SPEED_BACKSTOP_MPS

    @Test
    fun recentStepAllowsWorkout() {
        val classifier = GaitClassifier()
        val decision = classifier.consider(
            sample = sample(time = 5_000L, speed = 2.0),
            lastStepAtMs = 4_000L,
            inVehicleReported = false,
        )
        assertEquals(MotionGuess.WORKOUT, decision.mode)
        assertTrue(decision.persistWorkoutPoint)
    }

    @Test
    fun noStepForEightSecondsWhileMovingIsVehicle() {
        val classifier = GaitClassifier()
        classifier.consider(
            sample = sample(lat = 0.0, time = 0L, speed = 2.0, accuracy = 8f),
            lastStepAtMs = 0L,
            inVehicleReported = false,
        )
        val later = classifier.consider(
            sample = sample(lat = 0.0003, time = 9_000L, speed = 2.0, accuracy = 8f),
            lastStepAtMs = 0L,
            inVehicleReported = false,
        )
        assertEquals(MotionGuess.LIKELY_VEHICLE, later.mode)
        assertFalse(later.persistWorkoutPoint)
    }

    @Test
    fun twelveMphWithoutRecentStepsIsVehicleImmediately() {
        val classifier = GaitClassifier()
        val decision = classifier.consider(
            sample = sample(time = 1_000L, speed = backstop + 0.1),
            lastStepAtMs = null,
            inVehicleReported = false,
        )
        assertEquals(MotionGuess.LIKELY_VEHICLE, decision.mode)
        assertFalse(decision.persistWorkoutPoint)
        assertFalse(decision.shouldFinishSession)
    }

    @Test
    fun inVehicleIsTreatedAsNoSteps() {
        val classifier = GaitClassifier()
        val decision = classifier.consider(
            sample = sample(time = 1_000L, speed = 3.0),
            lastStepAtMs = 900L,
            inVehicleReported = true,
        )
        assertEquals(MotionGuess.LIKELY_VEHICLE, decision.mode)
        assertFalse(decision.persistWorkoutPoint)
    }

    @Test
    fun vehicleHeldFortyFiveSecondsRequestsFinish() {
        val classifier = GaitClassifier()
        classifier.consider(
            sample = sample(time = 0L, speed = backstop + 0.1),
            lastStepAtMs = null,
            inVehicleReported = false,
        )
        val later = classifier.consider(
            sample = sample(time = 45_000L, speed = backstop + 0.1),
            lastStepAtMs = null,
            inVehicleReported = false,
        )
        assertTrue(later.shouldFinishSession)
    }

    @Test
    fun stepClearsVehicleBeforeFinish() {
        val classifier = GaitClassifier()
        classifier.consider(
            sample = sample(time = 0L, speed = backstop + 0.1),
            lastStepAtMs = null,
            inVehicleReported = false,
        )
        classifier.onStep()
        val afterStep = classifier.consider(
            sample = sample(time = 2_000L, speed = 2.0),
            lastStepAtMs = 2_000L,
            inVehicleReported = false,
        )
        assertEquals(MotionGuess.WORKOUT, afterStep.mode)
        assertTrue(afterStep.persistWorkoutPoint)
        assertFalse(afterStep.shouldFinishSession)
    }

    private fun sample(
        lat: Double = 0.0,
        time: Long,
        speed: Double,
        accuracy: Float = 8f,
    ) = GeoSample(
        latitude = lat,
        longitude = 0.0,
        altitudeMeters = null,
        accuracyMeters = accuracy,
        speedMps = speed,
        timestampEpochMs = time,
    )
}
