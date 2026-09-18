package com.workoutlab.track.recording

enum class SessionPhase {
    Idle,
    Recording,
    Paused,
}

data class RecordingUiState(
    val phase: SessionPhase = SessionPhase.Idle,
    val startedAtEpochMs: Long? = null,
    val recordedElapsedMs: Long = 0,
    val segmentStartedAtEpochMs: Long? = null,
    val elapsedMs: Long = 0,
    val workoutDistanceMeters: Double = 0.0,
    val accuracyMeters: Float? = null,
    val hasFix: Boolean = false,
    val motionGuess: MotionGuess? = null,
    val pointCount: Int = 0,
    val excludedSampleCount: Int = 0,
    val pathPoints: List<PathPoint> = emptyList(),
    val message: String? = null,
    val watcherReason: String? = null,
    val promotedAtEpochMs: Long? = null,
    val firstPointAtEpochMs: Long? = null,
)

data class LastSessionUi(
    val id: Long,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val durationMs: Long,
    val workoutDistanceMeters: Double,
    val pointCount: Int,
    val excludedSampleCount: Int,
    val points: List<PathPoint>,
)

data class PathPoint(
    val latitude: Double,
    val longitude: Double,
    val startsSegment: Boolean = false,
)
