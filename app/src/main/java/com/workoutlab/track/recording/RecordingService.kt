package com.workoutlab.track.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.workoutlab.track.MainActivity
import com.workoutlab.track.R
import com.workoutlab.track.WorkoutLabApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class RecordingService : LifecycleService() {
    private val repository: RecordingRepository
        get() = (application as WorkoutLabApp).repository

    private lateinit var fusedClient: FusedLocationProviderClient
    private var ticker: Job? = null
    private var gpsStarted = false
    private var gaitStarted = false
    private var lastStepCount: Float? = null
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private lateinit var activityMonitor: ActivityRecognitionMonitor

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> repository.onStep()
                Sensor.TYPE_STEP_COUNTER -> {
                    val count = event.values.firstOrNull() ?: return
                    val previous = lastStepCount
                    lastStepCount = count
                    if (previous != null && count > previous) repository.onStep()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lifecycleScope.launch {
                repository.onLocationSample(location.toSample())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        activityMonitor = ActivityRecognitionMonitor(
            this,
            RecordingService::class.java,
            ActivityRecognitionMonitor.ACTION_ACTIVITY,
        )
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE, ACTION_STOP -> {
                lifecycleScope.launch { pauseRecording() }
            }
            ACTION_FINISH -> {
                lifecycleScope.launch { haltGpsAndService() }
            }
            ActivityRecognitionMonitor.ACTION_ACTIVITY -> {
                ActivityRecognitionMonitor.guessFrom(intent)?.let { guess ->
                    repository.setInVehicleReported(guess.inVehicle)
                }
                if (!gpsStarted && !gaitStarted) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                startInForeground()
                startGps()
                startGait()
                activityMonitor.start()
                startTicker()
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startGps() {
        if (gpsStarted) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            lifecycleScope.launch { pauseRecording() }
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        gpsStarted = true
    }

    private fun startGait() {
        if (gaitStarted) return
        gaitStarted = true
        val detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (detector != null) {
            sensorManager.registerListener(stepListener, detector, SensorManager.SENSOR_DELAY_GAME, 0)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { counter ->
            sensorManager.registerListener(stepListener, counter, SensorManager.SENSOR_DELAY_GAME, 0)
        }
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = lifecycleScope.launch {
            while (isActive) {
                repository.tickElapsed()
                val state = repository.ui.value
                if (state.phase == SessionPhase.Recording) {
                    updateNotification(state)
                }
                delay(1_000)
            }
        }
    }

    private suspend fun pauseRecording() {
        stopGps()
        ticker?.cancel()
        ticker = null
        repository.pauseRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun haltGpsAndService() {
        stopGps()
        ticker?.cancel()
        ticker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopGps() {
        if (gpsStarted) {
            fusedClient.removeLocationUpdates(locationCallback)
            gpsStarted = false
        }
        stopGait()
    }

    private fun stopGait() {
        if (!gaitStarted) return
        sensorManager.unregisterListener(stepListener)
        lastStepCount = null
        gaitStarted = false
        activityMonitor.stop()
        repository.setInVehicleReported(false)
    }

    override fun onDestroy() {
        stopGps()
        ticker?.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(state: RecordingUiState) {
        val text = formatNotificationText(state)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSubText(getString(R.string.notification_extra_battery))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.workoutlab.track.action.START"
        const val ACTION_PAUSE = "com.workoutlab.track.action.PAUSE"
        const val ACTION_STOP = "com.workoutlab.track.action.STOP"
        const val ACTION_FINISH = "com.workoutlab.track.action.FINISH"
        private const val CHANNEL_ID = "workout_recording"
        private const val NOTIFICATION_ID = 42

        fun formatNotificationText(state: RecordingUiState): String {
            val elapsed = formatElapsed(state.elapsedMs)
            val distance = formatDistance(state.workoutDistanceMeters)
            return "$elapsed · $distance"
        }

        fun formatElapsed(elapsedMs: Long): String {
            val totalSec = (elapsedMs / 1000).coerceAtLeast(0)
            val hours = totalSec / 3600
            val minutes = (totalSec % 3600) / 60
            val seconds = totalSec % 60
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }

        fun formatDistance(meters: Double): String = Geo.formatMiles(meters)
    }
}

private fun Location.toSample(): GeoSample = GeoSample(
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = if (hasAltitude()) altitude else null,
    accuracyMeters = if (hasAccuracy()) accuracy else 999f,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    timestampEpochMs = time,
)
