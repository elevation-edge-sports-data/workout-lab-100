package com.workoutlab.track.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartDetectorTest {
    @Test
    fun walkCadenceFor12sRequestsGpsPeek() {
        val detector = AutostartDetector()
        var last = AutostartAction.NONE
        for (t in 0L..12_000L step 600L) {
            last = detector.onStep(t)
        }
        assertEquals(AutostartAction.PEEK_GPS, last)
        assertTrue(detector.isGaitHeld(12_000L))
    }

    @Test
    fun slowStepsDoNotRequestPeek() {
        val detector = AutostartDetector()
        var last = AutostartAction.NONE
        for (t in 0L..20_000L step 2_000L) {
            last = detector.onStep(t)
        }
        assertEquals(AutostartAction.NONE, last)
        assertFalse(detector.isGaitHeld(20_000L))
    }

    @Test
    fun inVehicleBlocksPromote() {
        val detector = AutostartDetector()
        detector.setInVehicle(true)
        var last = AutostartAction.NONE
        for (t in 0L..12_000L step 600L) {
            last = detector.onStep(t)
        }
        assertEquals(AutostartAction.NONE, last)
        assertFalse(detector.shouldPromote(8f, 12_000L))
    }

    @Test
    fun stepGapResetsHold() {
        val detector = AutostartDetector()
        for (t in 0L..8_000L step 600L) {
            detector.onStep(t)
        }
        assertEquals(AutostartAction.NONE, detector.onTick(12_000L))
        assertFalse(detector.isGaitHeld(12_000L))
        var last = AutostartAction.NONE
        for (t in 12_000L..24_000L step 600L) {
            last = detector.onStep(t)
        }
        assertEquals(AutostartAction.PEEK_GPS, last)
    }

    @Test
    fun accuracyWithin82ftPromotes() {
        val detector = AutostartDetector()
        for (t in 0L..12_000L step 600L) {
            detector.onStep(t)
        }
        assertTrue(detector.shouldPromote(25f, 12_000L))
    }

    @Test
    fun accuracyWorseThan82ftDoesNotPromoteImmediately() {
        val detector = AutostartDetector()
        for (t in 0L..12_000L step 600L) {
            detector.onStep(t)
        }
        assertFalse(detector.shouldPromote(40f, 12_000L))
        assertEquals("accuracy", detector.debugReason(12_000L))
    }

    @Test
    fun poorAccuracyThen20sGaitPromotes() {
        val detector = AutostartDetector()
        for (t in 0L..12_000L step 600L) {
            detector.onStep(t)
        }
        assertFalse(detector.shouldPromote(40f, 12_000L))
        var last = AutostartAction.NONE
        for (t in 12_600L..33_000L step 600L) {
            last = detector.onStep(t)
        }
        assertEquals(AutostartAction.PROMOTE, last)
        assertTrue(detector.shouldPromote(40f, 33_000L))
    }

    @Test
    fun missingGpsFixDoesNotPromoteUntilFallback() {
        val detector = AutostartDetector()
        for (t in 0L..12_000L step 600L) {
            detector.onStep(t)
        }
        assertFalse(detector.shouldPromote(null, 12_000L))
        var last = AutostartAction.NONE
        for (t in 12_600L..33_000L step 600L) {
            last = detector.onStep(t)
        }
        assertEquals(AutostartAction.PROMOTE, last)
    }

    @Test
    fun activityWalkingFor12sRequestsPeek() {
        val detector = AutostartDetector()
        detector.setWalkingReported(true, 0L)
        assertEquals(AutostartAction.NONE, detector.onTick(5_000L))
        detector.setWalkingReported(true, 10_000L)
        assertEquals(AutostartAction.PEEK_GPS, detector.onTick(12_000L))
        assertTrue(detector.isGaitHeld(12_000L))
    }

    @Test
    fun debugReasonNoSteps() {
        val detector = AutostartDetector()
        assertEquals("no steps", detector.debugReason(0L))
    }
}
