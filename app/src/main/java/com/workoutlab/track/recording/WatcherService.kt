package com.workoutlab.track.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pedometer-like watcher: steps and activity. No tiles, path, or miles.
 * GPS is a short precise peek after gait, then off again.
 */
class WatcherService : LifecycleService() {
    private val app: WorkoutLabApp
        get() = application as WorkoutLabApp
    private val repository: RecordingRepository
        get() = app.repository

    private val detector = AutostartDetector()
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var activityMonitor: ActivityRecognitionMonitor

    private var gaitStarted = false
    private var lastStepCount: Float? = null
    private var ticker: Job? = null
    private var peekTimeout: Job? = null
    private var peeking = false
    private var peekUpdatesStarted = false
    private var bestPeekAccuracy: Float? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> onStep()
                Sensor.TYPE_STEP_COUNTER -> {
                    val count = event.values.firstOrNull() ?: return
                    val previous = lastStepCount
                    lastStepCount = count
                    if (previous != null && count > previous) onStep()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val peekCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val accuracy = if (location.hasAccuracy()) location.accuracy else return
            val previous = bestPeekAccuracy
            if (previous == null || accuracy < previous) bestPeekAccuracy = accuracy
            if (accuracy <= GaitClassifier.ACCURACY_LIMIT_METERS) {
                finishPeek(accuracy)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        activityMonitor = ActivityRecognitionMonitor(
            this,
            WatcherService::class.java,
            ActivityRecognitionMonitor.ACTION_ACTIVITY,
        )
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                halt()
                return START_NOT_STICKY
            }
            ActivityRecognitionMonitor.ACTION_ACTIVITY -> {
                applyActivity(intent)
                if (!gaitStarted) {
                    if (shouldRun()) {
                        startWatching()
                        return START_STICKY
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            else -> {
                startInForeground()
                if (!shouldRun()) {
                    halt()
                    return START_NOT_STICKY
                }
                startWatching()
                return START_STICKY
            }
        }
    }

    private fun startWatching() {
        startInForeground()
        _running.value = true
        acquireWakeLock()
        startGait()
        activityMonitor.start()
        startTicker()
        publishReason()
    }

    private fun applyActivity(intent: Intent?) {
        val guess = ActivityRecognitionMonitor.guessFrom(intent) ?: return
        detector.setInVehicle(guess.inVehicle)
        detector.setWalkingReported(guess.walking, System.currentTimeMillis())
        if (!guess.inVehicle && guess.walking) {
            handleAction(detector.onTick(System.currentTimeMillis()))
        }
        publishReason()
    }

    private fun onStep() {
        if (!shouldRun()) return
        handleAction(detector.onStep(System.currentTimeMillis()))
        publishReason()
    }

    private fun handleAction(action: AutostartAction) {
        when (action) {
            AutostartAction.NONE -> Unit
            AutostartAction.PEEK_GPS -> peekGps()
            AutostartAction.PROMOTE -> promote()
        }
    }

    private fun peekGps() {
        if (peeking) return
        if (!hasLocationPermission()) {
            repository.setWatcherReason("no permission")
            return
        }
        peeking = true
        bestPeekAccuracy = null
        val now = System.currentTimeMillis()
        detector.markPeekStarted(now)
        startPeekUpdates()
        peekTimeout = lifecycleScope.launch {
            delay(PEEK_TIMEOUT_MS)
            finishPeek(bestPeekAccuracy)
        }
        publishReason()
    }

    private fun startPeekUpdates() {
        if (peekUpdatesStarted) return
        if (!hasLocationPermission()) return
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(500L)
                .setWaitForAccurateLocation(false)
                .setDurationMillis(PEEK_TIMEOUT_MS)
                .build()
            fusedClient.requestLocationUpdates(request, peekCallback, Looper.getMainLooper())
            peekUpdatesStarted = true
        } catch (_: SecurityException) {
            finishPeek(null)
        }
    }

    private fun stopPeekUpdates() {
        if (!peekUpdatesStarted) return
        fusedClient.removeLocationUpdates(peekCallback)
        peekUpdatesStarted = false
    }

    private fun finishPeek(accuracyMeters: Float?) {
        if (!peeking) return
        peeking = false
        peekTimeout?.cancel()
        peekTimeout = null
        stopPeekUpdates()
        detector.markPeekFinished()
        val now = System.currentTimeMillis()
        if (detector.shouldPromote(accuracyMeters, now) && shouldRun()) {
            promote()
        } else {
            publishReason()
        }
    }

    private fun promote() {
        if (!shouldRun()) return
        stopPeekUpdates()
        peeking = false
        repository.startRecording()
    }

    private fun startGait() {
        if (gaitStarted) return
        gaitStarted = true
        val detectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (detectorSensor != null) {
            sensorManager.registerListener(
                stepListener,
                detectorSensor,
                SensorManager.SENSOR_DELAY_GAME,
                0,
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { counter ->
            sensorManager.registerListener(
                stepListener,
                counter,
                SensorManager.SENSOR_DELAY_GAME,
                0,
            )
        }
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = lifecycleScope.launch {
            while (isActive) {
                if (!shouldRun()) {
                    halt()
                    return@launch
                }
                handleAction(detector.onTick(System.currentTimeMillis()))
                publishReason()
                delay(1_000)
            }
        }
    }

    private fun halt() {
        peeking = false
        peekTimeout?.cancel()
        peekTimeout = null
        ticker?.cancel()
        ticker = null
        stopPeekUpdates()
        stopGait()
        activityMonitor.stop()
        detector.reset()
        releaseWakeLock()
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopGait() {
        if (!gaitStarted) return
        sensorManager.unregisterListener(stepListener)
        lastStepCount = null
        gaitStarted = false
    }

    override fun onDestroy() {
        peekTimeout?.cancel()
        ticker?.cancel()
        stopPeekUpdates()
        stopGait()
        activityMonitor.stop()
        releaseWakeLock()
        _running.value = false
        super.onDestroy()
    }

    private fun shouldRun(): Boolean {
        if (!app.trackSettings.watcherShouldRun()) return false
        return repository.ui.value.phase == SessionPhase.Idle
    }

    private fun publishReason() {
        val reason = when {
            !app.trackSettings.autostartEnabled.value -> "auto start off"
            !hasActivityPermission() -> "no permission"
            !hasLocationPermission() -> "no permission"
            else -> detector.debugReason(System.currentTimeMillis())
        }
        repository.setWatcherReason(reason)
        _reason.value = reason
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasActivityPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startInForeground() {
        val notification = buildNotification()
        val includeLocation = hasLocationPermission()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                if (includeLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(NOTIFICATION_ID, notification, types)
            } else if (Build.VERSION.SDK_INT >= 29) {
                val types = if (includeLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
        lock.setReferenceCounted(false)
        lock.acquire(10 * 60 * 60 * 1000L)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
        wakeLock = null
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.watcher_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.watcher_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.watcher_notification_title))
            .setContentText(getString(R.string.watcher_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "com.workoutlab.track.action.WATCH_START"
        const val ACTION_STOP = "com.workoutlab.track.action.WATCH_STOP"
        private const val CHANNEL_ID = "workout_autostart"
        private const val NOTIFICATION_ID = 43
        private const val PEEK_TIMEOUT_MS = 10_000L
        private const val WAKE_TAG = "workoutlab:watcher"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _reason = MutableStateFlow("no steps")
        val reason: StateFlow<String> = _reason.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, WatcherService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (!_running.value) {
                context.stopService(Intent(context, WatcherService::class.java))
                return
            }
            val intent = Intent(context, WatcherService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
