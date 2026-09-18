package com.workoutlab.track

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.workoutlab.track.recording.RecordingRepository
import com.workoutlab.track.recording.WatcherService
import com.workoutlab.track.settings.TrackSettings
import com.workoutlab.track.ui.LiveScreen
import com.workoutlab.track.ui.RecentScreen
import com.workoutlab.track.ui.theme.WorkoutLabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val repository: RecordingRepository
        get() = (application as WorkoutLabApp).repository
    private val trackSettings: TrackSettings
        get() = (application as WorkoutLabApp).trackSettings

    private var showBackgroundLocationRationale by mutableStateOf(false)
    private var askedBackgroundThisSession = false
    private var batteryRestricted by mutableStateOf(false)
    private var exportSessionId: Long? = null
    private var afterPermission: AfterPermission = AfterPermission.Watcher

    private val sessionPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val locationOk = hasLocationPermission() ||
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when (afterPermission) {
            AfterPermission.Start, AfterPermission.Resume -> {
                if (locationOk) {
                    maybeRequestNotificationThenGo()
                } else {
                    repository.setMessage("Location permission is required to record a workout.")
                }
            }
            AfterPermission.Watcher -> {
                repository.syncWatcher()
                maybeAskBackgroundLocation()
            }
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        when (afterPermission) {
            AfterPermission.Start -> repository.startRecording()
            AfterPermission.Resume -> repository.resumeRecording()
            AfterPermission.Watcher -> {
                repository.syncWatcher()
                maybeAskBackgroundLocation()
            }
        }
    }

    private val backgroundLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        repository.syncWatcher()
    }

    private val createJson = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { writeExport(it, json = true) } }

    private val createGpx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri -> uri?.let { writeExport(it, json = false) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshBatteryRestricted()
        setContent {
            val state by repository.ui.collectAsStateWithLifecycle()
            val app = application as WorkoutLabApp
            val tilesOn by app.mapSettings.tilesEnabled.collectAsStateWithLifecycle()
            val autoStartEnabled by app.trackSettings.autostartEnabled.collectAsStateWithLifecycle()
            val inactivitySec by app.trackSettings.inactivityFinishSec.collectAsStateWithLifecycle()
            val recent by repository.recentSessions.collectAsStateWithLifecycle()
            val sortByDistance by app.trackSettings.recentSortByDistance.collectAsStateWithLifecycle()
            val watcherRunning by WatcherService.running.collectAsStateWithLifecycle()
            var tab by rememberSaveable { mutableIntStateOf(0) }
            WorkoutLabTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Text(if (tab == 0) "●" else "○") },
                                label = { Text("Current") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Text(if (tab == 1) "●" else "○") },
                                label = { Text("Recent") },
                            )
                        }
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        if (tab == 0) {
                            LiveScreen(
                                state = state,
                                mapTilesEnabled = tilesOn,
                                onMapTilesEnabled = { enabled ->
                                    app.mapSettings.setTilesEnabled(enabled, app.tileLoader)
                                },
                                autoStartEnabled = autoStartEnabled,
                                onAutoStartEnabled = { enabled -> repository.setAutoStartEnabled(enabled) },
                                watcherRunning = watcherRunning,
                                batteryRestricted = batteryRestricted,
                                onOpenBatterySettings = { openBatterySettings() },
                                showBackgroundLocationRationale = showBackgroundLocationRationale,
                                onConfirmBackgroundLocation = {
                                    showBackgroundLocationRationale = false
                                    askedBackgroundThisSession = true
                                    if (Build.VERSION.SDK_INT >= 29) {
                                        backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                    }
                                },
                                onDismissBackgroundLocation = {
                                    showBackgroundLocationRationale = false
                                    askedBackgroundThisSession = true
                                },
                                tileLoader = app.tileLoader,
                                onStart = { onStartOrResume(resume = false) },
                                onStop = { repository.requestPause() },
                                onResume = { onStartOrResume(resume = true) },
                                onFinish = { repository.requestFinish() },
                                inactivityFinishSec = inactivitySec,
                                onInactivityFinishSec = { sec ->
                                    app.trackSettings.setInactivityFinishSec(sec)
                                },
                            )
                        } else {
                            RecentScreen(
                                sessions = recent,
                                sortByDistance = sortByDistance,
                                onSortByDistance = { byDistance ->
                                    app.trackSettings.setRecentSortByDistance(byDistance)
                                },
                                mapTilesEnabled = tilesOn,
                                tileLoader = app.tileLoader,
                                onExportJson = { id -> launchExport(json = true, sessionId = id) },
                                onExportGpx = { id -> launchExport(json = false, sessionId = id) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshBatteryRestricted()
        if (trackSettings.watcherShouldRun()) {
            ensureWatcherPermissions()
        } else {
            repository.syncWatcher()
        }
    }

    private fun onStartOrResume(resume: Boolean) {
        repository.setMessage(null)
        if (trackSettings.eventLock.value) {
            repository.setMessage("Auto Start is off. Turn it on to start a session.")
            return
        }
        afterPermission = if (resume) AfterPermission.Resume else AfterPermission.Start
        val permissions = mutableListOf<String>()
        if (!hasLocationPermission()) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needsActivityRecognition()) {
            permissions += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (permissions.isNotEmpty()) {
            sessionPermissions.launch(permissions.toTypedArray())
        } else {
            maybeRequestNotificationThenGo()
        }
    }

    private fun ensureWatcherPermissions() {
        afterPermission = AfterPermission.Watcher
        val permissions = mutableListOf<String>()
        if (needsActivityRecognition()) {
            permissions += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (!hasLocationPermission()) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT == 29 &&
            !hasBackgroundLocation() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (permissions.isNotEmpty()) {
            sessionPermissions.launch(permissions.toTypedArray())
        } else {
            repository.syncWatcher()
            maybeAskBackgroundLocation()
        }
    }

    private fun maybeRequestNotificationThenGo() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        when (afterPermission) {
            AfterPermission.Start -> repository.startRecording()
            AfterPermission.Resume -> repository.resumeRecording()
            AfterPermission.Watcher -> {
                repository.syncWatcher()
                maybeAskBackgroundLocation()
            }
        }
    }

    private enum class AfterPermission { Start, Resume, Watcher }

    private fun maybeAskBackgroundLocation() {
        if (askedBackgroundThisSession) return
        if (!trackSettings.watcherShouldRun()) return
        if (Build.VERSION.SDK_INT < 29) return
        if (!hasLocationPermission()) return
        if (hasBackgroundLocation()) return
        showBackgroundLocationRationale = true
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun needsActivityRecognition(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun refreshBatteryRestricted() {
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        batteryRestricted = !manager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatterySettings() {
        val pkg = packageName
        val attempts = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
            },
            Intent("android.settings.APP_BATTERY_SETTINGS").apply {
                data = Uri.fromParts("package", pkg, null)
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Try the next known settings screen.
            }
        }
        try {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            repository.setMessage("Could not open app settings.")
        }
    }

    private fun launchExport(json: Boolean, sessionId: Long) {
        val session = repository.recentSessions.value.firstOrNull { it.id == sessionId } ?: return
        exportSessionId = sessionId
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(session.startedAtEpochMs))
        if (json) {
            createJson.launch("workout-$stamp.json")
        } else {
            createGpx.launch("workout-$stamp.gpx")
        }
    }

    private fun writeExport(uri: Uri, json: Boolean) {
        lifecycleScope.launch {
            val id = exportSessionId
            val body = if (json) repository.exportJson(id) else repository.exportGpx(id)
            if (body == null) {
                repository.setMessage("No session to export.")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(body.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open file")
                }.isSuccess
            }
            repository.setMessage(if (ok) "Saved." else "Could not write the file.")
        }
    }
}
