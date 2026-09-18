package com.workoutlab.track.data.export

import com.workoutlab.track.data.db.PointEntity
import com.workoutlab.track.data.db.SESSION_COMPLETE
import com.workoutlab.track.data.db.SessionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExporterTest {
    private val session = SessionEntity(
        id = 1,
        startedAtEpochMs = 1_700_000_000_000L,
        stoppedAtEpochMs = 1_700_000_600_000L,
        workoutDistanceMeters = 1234.5,
        pointCount = 2,
        excludedSampleCount = 12,
        status = SESSION_COMPLETE,
    )

    private val points = listOf(
        PointEntity(
            id = 1,
            sessionId = 1,
            latitude = 40.7128,
            longitude = -74.0060,
            altitudeMeters = 12.0,
            accuracyMeters = 6.2f,
            speedMps = 2.1f,
            timestampEpochMs = 1_700_000_000_000L,
        ),
        PointEntity(
            id = 2,
            sessionId = 1,
            latitude = 40.7130,
            longitude = -74.0062,
            altitudeMeters = null,
            accuracyMeters = 5.0f,
            speedMps = null,
            timestampEpochMs = 1_700_000_001_000L,
        ),
    )

    @Test
    fun jsonContainsWorkoutMetadataAndNoInventedCatalog() {
        val json = SessionExporter.toJson(session, points)
        assertTrue(json.contains("\"type\": \"workout_session\""))
        assertFalse(json.contains("startBattery"))
        assertFalse(json.contains("stopBattery"))
        assertTrue(json.contains("\"excludedSampleCount\": 12"))
        assertTrue(json.contains("40.7128"))
        assertFalse(json.contains("Cyclone"))
        assertFalse(json.contains("invoice"))
        assertFalse(json.contains("commute"))
    }

    @Test
    fun gpxIsTrackWithWorkoutPointsOnly() {
        val gpx = SessionExporter.toGpx(session, points)
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("lat=\"40.7128000\""))
        assertTrue(gpx.contains("<trkseg>"))
        assertFalse(gpx.contains("rte"))
    }

    @Test
    fun archiveNamesKeepOlderSessions() {
        val first = SessionArchive.fileBase(1_700_000_000_000L, 1)
        val second = SessionArchive.fileBase(1_700_000_000_000L, 2)
        assertTrue(first.startsWith("workout-"))
        assertNotEquals(first, second)
    }
}
