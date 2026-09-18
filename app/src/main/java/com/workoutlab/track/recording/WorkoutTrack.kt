package com.workoutlab.track.recording

data class GeoSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMps: Double?,
    val timestampEpochMs: Long,
)

data class TrackUpdate(
    val accepted: GeoSample?,
    val guess: MotionGuess,
    val distanceMeters: Double,
    val pointCount: Int,
    val excludedSampleCount: Int,
    val accuracyTooWeak: Boolean,
    val dropAsTeleport: Boolean,
    val stationary: Boolean = false,
    val dropAsJump: Boolean = false,
    val shouldFinishSession: Boolean = false,
    val pathPoints: List<PathPoint> = emptyList(),
)

/**
 * Workout-only distance from gait. A GPS fix may add miles and path only when
 * a step was seen in the last 5 seconds and accuracy is within 82 ft.
 * Vehicle-like samples are never saved. Distance is not joined across a gap.
 */
class WorkoutTrack(
    private val classifier: GaitClassifier = GaitClassifier(),
) {
    var distanceMeters: Double = 0.0
        private set
    var pointCount: Int = 0
        private set
    var excludedSampleCount: Int = 0
        private set

    private val path = mutableListOf<PathPoint>()
    private var lastIncluded: GeoSample? = null
    private var lastMode: MotionGuess = MotionGuess.WORKOUT
    private var lastStepAtMs: Long? = null
    private var inVehicleReported: Boolean = false
    private var nextStartsSegment: Boolean = false
    var lastActivityAtMs: Long? = null
        private set

    fun reset() {
        classifier.reset()
        distanceMeters = 0.0
        pointCount = 0
        excludedSampleCount = 0
        path.clear()
        lastIncluded = null
        lastMode = MotionGuess.WORKOUT
        lastStepAtMs = null
        inVehicleReported = false
        nextStartsSegment = false
        lastActivityAtMs = null
    }

    fun markActivity(atMs: Long) {
        lastActivityAtMs = atMs
    }

    fun shouldFinishInactivity(nowMs: Long, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) return false
        val last = lastActivityAtMs ?: return false
        return nowMs - last >= timeoutMs
    }

    fun onStep(atMs: Long) {
        lastStepAtMs = atMs
        lastActivityAtMs = atMs
        classifier.onStep()
        if (lastMode == MotionGuess.LIKELY_VEHICLE) {
            lastIncluded = null
            nextStartsSegment = path.isNotEmpty()
            lastMode = MotionGuess.WORKOUT
        }
    }

    fun setInVehicleReported(inVehicle: Boolean) {
        inVehicleReported = inVehicle
    }

    fun shouldFinish(nowMs: Long): Boolean = classifier.shouldFinish(nowMs)

    /** Do not join distance across a pause; keep the existing path. */
    fun markGap() {
        lastIncluded = null
        lastStepAtMs = null
        nextStartsSegment = path.isNotEmpty()
    }

    fun restore(
        points: List<PathPoint>,
        distanceMeters: Double,
        excludedSampleCount: Int,
    ) {
        reset()
        this.distanceMeters = distanceMeters
        this.excludedSampleCount = excludedSampleCount
        this.pointCount = points.size
        path.addAll(points)
        lastIncluded = null
        nextStartsSegment = path.isNotEmpty()
    }

    fun onSample(sample: GeoSample): TrackUpdate {
        if (sample.accuracyMeters > GaitClassifier.ACCURACY_LIMIT_METERS) {
            return snapshot(
                accepted = null,
                accuracyTooWeak = true,
            )
        }

        val decision = classifier.consider(
            sample = sample,
            lastStepAtMs = lastStepAtMs,
            inVehicleReported = inVehicleReported,
        )
        lastMode = decision.mode

        if (decision.dropAsTeleport) {
            return snapshot(
                accepted = null,
                dropAsTeleport = true,
                shouldFinishSession = decision.shouldFinishSession,
            )
        }

        if (decision.mode == MotionGuess.LIKELY_VEHICLE || !decision.persistWorkoutPoint) {
            if (decision.mode == MotionGuess.LIKELY_VEHICLE) {
                lastIncluded = null
                nextStartsSegment = path.isNotEmpty()
                excludedSampleCount += 1
            }
            return snapshot(
                accepted = null,
                guess = decision.mode,
                shouldFinishSession = decision.shouldFinishSession,
            )
        }

        val previous = lastIncluded
        if (previous == null) {
            accept(sample)
            return snapshot(accepted = sample)
        }

        val moved = Geo.haversineMeters(
            previous.latitude,
            previous.longitude,
            sample.latitude,
            sample.longitude,
        )
        if (isJumpOutlier(previous, sample, moved)) {
            excludedSampleCount += 1
            return snapshot(accepted = null, dropAsJump = true)
        }
        val minMove = maxOf(sample.accuracyMeters, previous.accuracyMeters).toDouble()
        if (moved < minMove) {
            return snapshot(accepted = null, stationary = true)
        }

        distanceMeters += moved
        accept(sample)
        return snapshot(accepted = sample)
    }

    /**
     * Spike filter: do not add miles or a path vertex for a jump farther than
     * 80 ft in under 2 seconds, or farther than 3× reported accuracy in that window.
     * Last accepted point is kept; rejected samples are not drawn.
     */
    private fun isJumpOutlier(previous: GeoSample, sample: GeoSample, movedMeters: Double): Boolean {
        val dtMs = sample.timestampEpochMs - previous.timestampEpochMs
        if (dtMs <= 0L) return true
        val acc = maxOf(sample.accuracyMeters, previous.accuracyMeters).toDouble()
        if (dtMs < JUMP_WINDOW_MS) {
            if (movedMeters > JUMP_80_FT_METERS) return true
            if (movedMeters > JUMP_ACCURACY_MULT * acc) return true
        }
        val dtSec = dtMs / 1000.0
        val speed = movedMeters / dtSec
        return movedMeters > JUMP_80_FT_METERS && speed >= GaitClassifier.SPEED_BACKSTOP_MPS
    }

    private fun accept(sample: GeoSample) {
        val startsSegment = nextStartsSegment || path.isEmpty()
        nextStartsSegment = false
        lastIncluded = sample
        lastActivityAtMs = sample.timestampEpochMs
        pointCount += 1
        path.add(
            PathPoint(
                latitude = sample.latitude,
                longitude = sample.longitude,
                startsSegment = startsSegment,
            ),
        )
    }

    private fun snapshot(
        accepted: GeoSample?,
        accuracyTooWeak: Boolean = false,
        dropAsTeleport: Boolean = false,
        stationary: Boolean = false,
        dropAsJump: Boolean = false,
        shouldFinishSession: Boolean = false,
        guess: MotionGuess = lastMode,
    ) = TrackUpdate(
        accepted = accepted,
        guess = guess,
        distanceMeters = distanceMeters,
        pointCount = pointCount,
        excludedSampleCount = excludedSampleCount,
        accuracyTooWeak = accuracyTooWeak,
        dropAsTeleport = dropAsTeleport,
        stationary = stationary,
        dropAsJump = dropAsJump,
        shouldFinishSession = shouldFinishSession,
        pathPoints = path.toList(),
    )

    companion object {
        val JUMP_80_FT_METERS = 80.0 / Geo.FEET_PER_METER
        const val JUMP_WINDOW_MS = 2_000L
        const val JUMP_ACCURACY_MULT = 3.0
    }
}
