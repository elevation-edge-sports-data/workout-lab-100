package com.workoutlab.track.recording

enum class AutostartAction {
    NONE,
    PEEK_GPS,
    PROMOTE,
}

/**
 * Cheap watcher logic: walk/jog cadence or on-foot activity for about 12 seconds,
 * not likely-vehicle. GPS is not used here. After a peek, promote if accuracy is
 * within 82 ft, or if gait continues about 20 seconds even when the peek is poor.
 */
class AutostartDetector {
    private val stepAt = ArrayDeque<Long>()
    private var holdStartedAt: Long? = null
    private var lastStepAt: Long? = null
    private var lastWalkingAt: Long? = null
    private var inVehicle = false
    private var walkingReported = false
    private var lastPeekAt: Long? = null
    private var firstPeekDoneAt: Long? = null
    private var lastAccuracy: Float? = null
    private var peekInFlight = false

    fun reset() {
        stepAt.clear()
        holdStartedAt = null
        lastStepAt = null
        lastWalkingAt = null
        inVehicle = false
        walkingReported = false
        lastPeekAt = null
        firstPeekDoneAt = null
        lastAccuracy = null
        peekInFlight = false
    }

    fun setInVehicle(inVehicle: Boolean) {
        this.inVehicle = inVehicle
        if (inVehicle) {
            holdStartedAt = null
            peekInFlight = false
            firstPeekDoneAt = null
            walkingReported = false
        }
    }

    fun setWalkingReported(walking: Boolean, nowMs: Long) {
        walkingReported = walking && !inVehicle
        if (walkingReported) {
            lastWalkingAt = nowMs
            if (holdStartedAt == null) holdStartedAt = nowMs
        }
    }

    fun onStep(nowMs: Long): AutostartAction {
        if (inVehicle) return AutostartAction.NONE
        val previous = lastStepAt
        if (previous != null && nowMs - previous > STEP_GAP_RESET_MS && !walkingFresh(nowMs)) {
            beginHold(nowMs, clearPeek = true)
        }
        lastStepAt = nowMs
        stepAt.addLast(nowMs)
        if (holdStartedAt == null) holdStartedAt = nowMs
        prune(nowMs)
        applyCadenceGate(nowMs)
        return evaluate(nowMs)
    }

    fun onTick(nowMs: Long): AutostartAction {
        val last = lastStepAt
        val stepStale = last != null && nowMs - last > STEP_GAP_RESET_MS
        if (stepStale) {
            stepAt.clear()
            lastStepAt = null
            if (!walkingFresh(nowMs)) {
                peekInFlight = false
                firstPeekDoneAt = null
                lastAccuracy = null
                holdStartedAt = null
            }
        }
        prune(nowMs)
        applyCadenceGate(nowMs)
        return evaluate(nowMs)
    }

    fun markPeekStarted(nowMs: Long) {
        peekInFlight = true
        lastPeekAt = nowMs
    }

    fun markPeekFinished() {
        peekInFlight = false
    }

    fun isGaitHeld(nowMs: Long): Boolean {
        if (inVehicle) return false
        val start = holdStartedAt ?: return false
        if (nowMs - start < HOLD_MS) return false
        if (walkingFresh(nowMs)) return true
        val last = lastStepAt ?: return false
        if (nowMs - last > STEP_GAP_RESET_MS) return false
        return cadenceSpm(nowMs) in MIN_SPM..MAX_SPM
    }

    fun gaitContinues(nowMs: Long): Boolean {
        if (inVehicle) return false
        if (walkingFresh(nowMs)) return true
        val last = lastStepAt ?: return false
        return nowMs - last <= STEP_GAP_RESET_MS
    }

    fun shouldPromote(accuracyMeters: Float?, nowMs: Long): Boolean {
        peekInFlight = false
        lastPeekAt = nowMs
        lastAccuracy = accuracyMeters
        if (firstPeekDoneAt == null) firstPeekDoneAt = nowMs
        if (accuracyMeters != null &&
            accuracyMeters <= GaitClassifier.ACCURACY_LIMIT_METERS &&
            isGaitHeld(nowMs)
        ) {
            return true
        }
        return fallbackReady(nowMs) && gaitContinues(nowMs)
    }

    fun debugReason(nowMs: Long): String {
        if (inVehicle) return "likely-vehicle"
        if (lastStepAt == null && !walkingFresh(nowMs)) return "no steps"
        if (!isGaitHeld(nowMs)) return "no steps"
        val accuracy = lastAccuracy
        if (firstPeekDoneAt != null &&
            (accuracy == null || accuracy > GaitClassifier.ACCURACY_LIMIT_METERS)
        ) {
            return "accuracy"
        }
        if (peekInFlight) return "accuracy"
        return "watching"
    }

    private fun evaluate(nowMs: Long): AutostartAction {
        if (peekInFlight) return AutostartAction.NONE
        if (fallbackReady(nowMs) && gaitContinues(nowMs)) return AutostartAction.PROMOTE
        if (!isGaitHeld(nowMs)) return AutostartAction.NONE
        val peeked = lastPeekAt
        if (peeked != null && nowMs - peeked < PEEK_COOLDOWN_MS) return AutostartAction.NONE
        return AutostartAction.PEEK_GPS
    }

    private fun fallbackReady(nowMs: Long): Boolean {
        val done = firstPeekDoneAt ?: return false
        return nowMs - done >= FALLBACK_MS
    }

    private fun walkingFresh(nowMs: Long): Boolean {
        if (!walkingReported) return false
        val at = lastWalkingAt ?: return false
        return nowMs - at <= AR_HOLD_GAP_MS
    }

    private fun applyCadenceGate(nowMs: Long) {
        if (walkingFresh(nowMs)) return
        val start = holdStartedAt ?: return
        if (nowMs - start < CADENCE_READY_MS) return
        if (cadenceSpm(nowMs) !in MIN_SPM..MAX_SPM) {
            beginHold(nowMs)
        }
    }

    private fun beginHold(nowMs: Long, clearPeek: Boolean = false) {
        holdStartedAt = nowMs
        peekInFlight = false
        if (clearPeek) {
            firstPeekDoneAt = null
            lastAccuracy = null
        }
        while (stepAt.isNotEmpty() && stepAt.first() < nowMs) {
            stepAt.removeFirst()
        }
        if (lastStepAt == nowMs && stepAt.none { it == nowMs }) {
            stepAt.addLast(nowMs)
        }
    }

    private fun cadenceSpm(nowMs: Long): Double {
        val windowStart = nowMs - CADENCE_WINDOW_MS
        val inWindow = stepAt.filter { it >= windowStart }
        if (inWindow.size < 2) return 0.0
        val duration = (inWindow.last() - inWindow.first()).coerceAtLeast(1L)
        return (inWindow.size - 1) * 60_000.0 / duration
    }

    private fun prune(nowMs: Long) {
        val keepFrom = nowMs - KEEP_STEPS_MS
        while (stepAt.isNotEmpty() && stepAt.first() < keepFrom) {
            stepAt.removeFirst()
        }
    }

    companion object {
        const val HOLD_MS = 12_000L
        const val CADENCE_READY_MS = 5_000L
        const val CADENCE_WINDOW_MS = 8_000L
        const val KEEP_STEPS_MS = 60_000L
        const val STEP_GAP_RESET_MS = 3_500L
        const val AR_HOLD_GAP_MS = 15_000L
        const val PEEK_COOLDOWN_MS = 8_000L
        const val FALLBACK_MS = 20_000L
        const val MIN_SPM = 80.0
        const val MAX_SPM = 200.0
    }
}
