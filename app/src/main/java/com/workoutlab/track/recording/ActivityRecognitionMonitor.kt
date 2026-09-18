package com.workoutlab.track.recording

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

data class ActivityGuess(
    val inVehicle: Boolean,
    val walking: Boolean,
)

/**
 * Low-rate on-device activity updates plus walk/vehicle transitions.
 * IN_VEHICLE is likely-vehicle. ON_FOOT / WALKING / RUNNING counts as gait.
 */
class ActivityRecognitionMonitor(
    private val context: Context,
    private val serviceClass: Class<*>,
    private val action: String,
) {
    private val client = ActivityRecognition.getClient(context)
    private var started = false

    fun start() {
        if (started) return
        started = true
        val pi = pendingIntent()
        try {
            client.requestActivityUpdates(UPDATE_INTERVAL_MS, pi)
        } catch (_: SecurityException) {
            started = false
            return
        } catch (_: Exception) {
            started = false
            return
        }
        try {
            client.requestActivityTransitionUpdates(transitionRequest(), pi)
        } catch (_: Exception) {
            // Updates alone are still useful.
        }
    }

    fun stop() {
        if (!started) return
        val pi = pendingIntent()
        try {
            client.removeActivityUpdates(pi)
        } catch (_: Exception) {
            // Still mark stopped so a later start can retry.
        }
        try {
            client.removeActivityTransitionUpdates(pi)
        } catch (_: Exception) {
            // Ignore.
        }
        started = false
    }

    private fun pendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        return PendingIntent.getService(
            context,
            REQUEST_CODE,
            Intent(context, serviceClass).setAction(action),
            flags,
        )
    }

    companion object {
        const val ACTION_ACTIVITY = "com.workoutlab.track.action.ACTIVITY"
        private const val REQUEST_CODE = 71
        private const val UPDATE_INTERVAL_MS = 8_000L

        fun guessFrom(intent: Intent?): ActivityGuess? {
            if (intent == null) return null
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent)
                if (result != null) {
                    var inVehicle = false
                    var walking = false
                    for (event in result.transitionEvents) {
                        val enter = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                        when (event.activityType) {
                            DetectedActivity.IN_VEHICLE -> if (enter) inVehicle = true
                            DetectedActivity.WALKING,
                            DetectedActivity.RUNNING,
                            DetectedActivity.ON_FOOT,
                            -> if (enter) walking = true
                        }
                    }
                    return ActivityGuess(inVehicle = inVehicle, walking = walking && !inVehicle)
                }
            }
            if (!ActivityRecognitionResult.hasResult(intent)) return null
            val result = ActivityRecognitionResult.extractResult(intent) ?: return null
            val type = result.mostProbableActivity.type
            val inVehicle = type == DetectedActivity.IN_VEHICLE
            val walking = type == DetectedActivity.WALKING ||
                type == DetectedActivity.RUNNING ||
                type == DetectedActivity.ON_FOOT
            return ActivityGuess(inVehicle = inVehicle, walking = walking && !inVehicle)
        }

        private fun transitionRequest(): ActivityTransitionRequest {
            fun enter(type: Int) = ActivityTransition.Builder()
                .setActivityType(type)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
            fun exit(type: Int) = ActivityTransition.Builder()
                .setActivityType(type)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
            return ActivityTransitionRequest(
                listOf(
                    enter(DetectedActivity.IN_VEHICLE),
                    exit(DetectedActivity.IN_VEHICLE),
                    enter(DetectedActivity.WALKING),
                    exit(DetectedActivity.WALKING),
                    enter(DetectedActivity.RUNNING),
                    exit(DetectedActivity.RUNNING),
                    enter(DetectedActivity.ON_FOOT),
                    exit(DetectedActivity.ON_FOOT),
                ),
            )
        }

        private fun mutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
    }
}
