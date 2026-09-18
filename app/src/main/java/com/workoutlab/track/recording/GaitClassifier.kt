package com.workoutlab.track.recording

enum class MotionGuess {
    WORKOUT,
    LIKELY_VEHICLE,
}

data class GaitDecision(
    val persistWorkoutPoint: Boolean,
    val mode: MotionGuess,
    val dropAsTeleport: Boolean,
    val gpsMoving: Boolean,
    val shouldFinishSession: Boolean,
)

/**
 * Jog vs drive from gait (steps), not a low-mph cutoff.
 * Activity Recognition IN_VEHICLE is treated as no-steps when present.
 * Speed is only a backstop: 12 mph and no recent steps → likely-vehicle now.
 */
class GaitClassifier {
    private var lastGps: GeoFix? = null
    private var vehicleSinceMs: Long? = null

    fun reset() {
        lastGps = null
        vehicleSinceMs = null
    }

    fun onStep() {
        vehicleSinceMs = null
    }

    fun consider(
        sample: GeoSample,
        lastStepAtMs: Long?,
        inVehicleReported: Boolean,
    ): GaitDecision {
        val now = sample.timestampEpochMs
        val previous = lastGps
        lastGps = GeoFix(sample.latitude, sample.longitude, now, sample.accuracyMeters)

        val speed = resolveSpeed(previous, sample)
        if (speed >= TELEPORT_SPEED_MPS) {
            return GaitDecision(
                persistWorkoutPoint = false,
                mode = currentMode(),
                dropAsTeleport = true,
                gpsMoving = true,
                shouldFinishSession = heldLongEnough(now),
            )
        }

        val recentStep = hasRecentStep(now, lastStepAtMs, STEP_WINDOW_MS)
        val noStepsForVehicle = inVehicleReported || !hasRecentStep(now, lastStepAtMs, NO_STEP_MOVING_MS)
        val gpsMoving = isMoving(previous, sample, speed)

        val likelyVehicle = when {
            noStepsForVehicle && speed >= SPEED_BACKSTOP_MPS -> true
            gpsMoving && noStepsForVehicle -> true
            else -> false
        }

        if (likelyVehicle) {
            if (vehicleSinceMs == null) vehicleSinceMs = now
            return GaitDecision(
                persistWorkoutPoint = false,
                mode = MotionGuess.LIKELY_VEHICLE,
                dropAsTeleport = false,
                gpsMoving = gpsMoving,
                shouldFinishSession = heldLongEnough(now),
            )
        }

        vehicleSinceMs = null
        val persist = recentStep && !inVehicleReported
        return GaitDecision(
            persistWorkoutPoint = persist,
            mode = MotionGuess.WORKOUT,
            dropAsTeleport = false,
            gpsMoving = gpsMoving,
            shouldFinishSession = false,
        )
    }

    fun currentMode(): MotionGuess =
        if (vehicleSinceMs != null) MotionGuess.LIKELY_VEHICLE else MotionGuess.WORKOUT

    fun shouldFinish(nowMs: Long): Boolean = heldLongEnough(nowMs)

    private fun heldLongEnough(nowMs: Long): Boolean {
        val since = vehicleSinceMs ?: return false
        return nowMs - since >= VEHICLE_HOLD_FINISH_MS
    }

    private fun hasRecentStep(nowMs: Long, lastStepAtMs: Long?, windowMs: Long): Boolean {
        if (lastStepAtMs == null) return false
        return nowMs - lastStepAtMs <= windowMs
    }

    private fun isMoving(previous: GeoFix?, sample: GeoSample, speedMps: Double): Boolean {
        if (speedMps >= MOVING_SPEED_MPS) return true
        if (previous == null) return false
        val moved = Geo.haversineMeters(
            previous.latitude,
            previous.longitude,
            sample.latitude,
            sample.longitude,
        )
        val minMove = maxOf(sample.accuracyMeters, previous.accuracyMeters).toDouble()
        return moved >= minMove
    }

    private fun resolveSpeed(previous: GeoFix?, sample: GeoSample): Double {
        if (sample.speedMps != null && sample.speedMps >= 0.0) return sample.speedMps
        if (previous == null) return 0.0
        return Geo.impliedSpeedMps(
            previous.latitude,
            previous.longitude,
            previous.timestampEpochMs,
            sample.latitude,
            sample.longitude,
            sample.timestampEpochMs,
        ) ?: 0.0
    }

    private data class GeoFix(
        val latitude: Double,
        val longitude: Double,
        val timestampEpochMs: Long,
        val accuracyMeters: Float,
    )

    companion object {
        const val ACCURACY_LIMIT_METERS = 25f
        const val STEP_WINDOW_MS = 5_000L
        const val NO_STEP_MOVING_MS = 8_000L
        const val VEHICLE_HOLD_FINISH_MS = 45_000L
        const val TELEPORT_SPEED_MPS = 50.0
        val SPEED_BACKSTOP_MPS = 12.0 * Geo.METERS_PER_MILE / 3600.0
        val MOVING_SPEED_MPS = 2.0 * Geo.METERS_PER_MILE / 3600.0
    }
}
