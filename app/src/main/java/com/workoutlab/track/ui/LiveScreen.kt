package com.workoutlab.track.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workoutlab.track.map.OsmTileLoader
import com.workoutlab.track.map.PathSegments
import com.workoutlab.track.recording.Geo
import com.workoutlab.track.recording.GaitClassifier
import com.workoutlab.track.recording.MotionGuess
import com.workoutlab.track.recording.PathPoint
import com.workoutlab.track.recording.RecordingService
import com.workoutlab.track.recording.RecordingUiState
import com.workoutlab.track.recording.SessionPhase

@Composable
fun LiveScreen(
    state: RecordingUiState,
    mapTilesEnabled: Boolean,
    onMapTilesEnabled: (Boolean) -> Unit,
    autoStartEnabled: Boolean,
    onAutoStartEnabled: (Boolean) -> Unit,
    watcherRunning: Boolean,
    batteryRestricted: Boolean,
    onOpenBatterySettings: () -> Unit,
    showBackgroundLocationRationale: Boolean,
    onConfirmBackgroundLocation: () -> Unit,
    onDismissBackgroundLocation: () -> Unit,
    tileLoader: OsmTileLoader?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    inactivityFinishSec: Int,
    onInactivityFinishSec: (Int) -> Unit,
) {
    var askLeavePaused by rememberSaveable { mutableStateOf(false) }
    var askAutoStartOff by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.phase == SessionPhase.Paused) {
        askLeavePaused = true
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Workout Lab Track",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            SettingsBlock(
                autoStartEnabled = autoStartEnabled,
                onAutoStartEnabled = onAutoStartEnabled,
                inactivityFinishSec = inactivityFinishSec,
                onInactivityFinishSec = onInactivityFinishSec,
                batteryUnrestricted = !batteryRestricted,
                onOpenBatterySettings = onOpenBatterySettings,
            )
            Spacer(Modifier.height(16.dp))

            when (state.phase) {
                SessionPhase.Idle -> {
                    if (!autoStartEnabled) {
                        AutoStartOffBlock(onStart = { askAutoStartOff = true })
                    } else {
                        WatchingBlock(
                            watcherRunning = watcherRunning,
                            batteryRestricted = batteryRestricted,
                            reason = watchingReason(
                                autoStartEnabled,
                                watcherRunning,
                                batteryRestricted,
                                state.watcherReason,
                            ),
                            timingLine = Geo.formatPromoteTiming(
                                state.promotedAtEpochMs,
                                state.firstPointAtEpochMs,
                            ),
                            onStart = onStart,
                        )
                    }
                }
                SessionPhase.Recording -> RecordingBlock(
                    state = state,
                    onStop = onStop,
                    onFinish = onFinish,
                    mapTilesEnabled = mapTilesEnabled,
                    onMapTilesEnabled = onMapTilesEnabled,
                    tileLoader = tileLoader,
                )
                SessionPhase.Paused -> PausedBlock(
                    state = state,
                    onResume = onResume,
                    onFinish = onFinish,
                    mapTilesEnabled = mapTilesEnabled,
                    onMapTilesEnabled = onMapTilesEnabled,
                    tileLoader = tileLoader,
                )
            }

            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }

    if (askAutoStartOff && !autoStartEnabled) {
        AlertDialog(
            onDismissRequest = { askAutoStartOff = false },
            title = { Text("Auto Start is off") },
            text = {
                Text("Turn on Auto Start to begin a session. GPS stays off until then.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askAutoStartOff = false
                        onAutoStartEnabled(true)
                        onStart()
                    },
                ) { Text("Turn on Auto Start and Start") }
            },
            dismissButton = {
                TextButton(onClick = { askAutoStartOff = false }) { Text("Cancel") }
            },
        )
    }

    if (showBackgroundLocationRationale) {
        AlertDialog(
            onDismissRequest = onDismissBackgroundLocation,
            title = { Text("Background location") },
            text = { Text("Needed to start a session when the screen is off.") },
            confirmButton = {
                TextButton(onClick = onConfirmBackgroundLocation) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = onDismissBackgroundLocation) { Text("Not now") }
            },
        )
    }

    if (askLeavePaused && state.phase == SessionPhase.Paused) {
        AlertDialog(
            onDismissRequest = { askLeavePaused = false },
            title = { Text("Session paused") },
            text = { Text("This workout is not finished. Resume, finish and save, or stay here.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        askLeavePaused = false
                        onResume()
                    },
                ) { Text("Resume") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            askLeavePaused = false
                            onFinish()
                        },
                    ) { Text("Finish") }
                    TextButton(onClick = { askLeavePaused = false }) { Text("Stay") }
                }
            },
        )
    }
}

@Composable
private fun WatchingBlock(
    watcherRunning: Boolean,
    batteryRestricted: Boolean,
    reason: String,
    timingLine: String?,
    onStart: () -> Unit,
) {
    Text(
        text = "Watching",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
    )
    Spacer(Modifier.height(12.dp))
    HugeButton(text = "START", onClick = onStart, recording = false)
    Spacer(Modifier.height(12.dp))
    Text(
        text = if (watcherRunning) {
            "Autostart on (not recording). GPS, path, and miles stay off until a session starts."
        } else {
            "Watcher is not running. Battery is not Unrestricted."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Not promoted: $reason",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    timingLine?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
    if (batteryRestricted) {
        Spacer(Modifier.height(8.dp))
        Banner("Battery: not Unrestricted")
    }
}

@Composable
private fun AutoStartOffBlock(onStart: () -> Unit) {
    Text(
        text = "Auto Start off",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
    )
    Spacer(Modifier.height(12.dp))
    HugeButton(text = "START", onClick = onStart, recording = false)
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Sessions will not start. GPS stays off. Turn Auto Start on to Start.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Not promoted: auto start off",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RecordingBlock(
    state: RecordingUiState,
    onStop: () -> Unit,
    onFinish: () -> Unit,
    mapTilesEnabled: Boolean,
    onMapTilesEnabled: (Boolean) -> Unit,
    tileLoader: OsmTileLoader?,
) {
    Text(
        text = "Recording",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
    )
    Spacer(Modifier.height(12.dp))
    HugeButton(text = "STOP", onClick = onStop, recording = true)
    Spacer(Modifier.height(12.dp))
    FinishButton(onFinish)
    Spacer(Modifier.height(12.dp))
    Geo.formatPromoteTiming(state.promotedAtEpochMs, state.firstPointAtEpochMs)?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
    }
    SessionStats(state)
    Spacer(Modifier.height(12.dp))
    Text(gpsLine(state), style = MaterialTheme.typography.bodyLarge)
    val weakGps = !state.hasFix ||
        (state.accuracyMeters != null && state.accuracyMeters > GaitClassifier.ACCURACY_LIMIT_METERS)
    if (weakGps) {
        Spacer(Modifier.height(8.dp))
        Banner("Weak GPS — distance may pause")
    } else if (state.hasFix) {
        Spacer(Modifier.height(8.dp))
        Banner("OK GPS")
    }
    Spacer(Modifier.height(8.dp))
    ModeChip(state.motionGuess)
    if (state.motionGuess == MotionGuess.LIKELY_VEHICLE) {
        Spacer(Modifier.height(8.dp))
        Banner("Mode: likely-vehicle — not saved. Finishes after 45s; next workout is a new path.")
    }
    Spacer(Modifier.height(16.dp))
    LivePathSketch(state.pathPoints, mapTilesEnabled, tileLoader)
    if (hasSessionMap(state.pathPoints)) {
        Spacer(Modifier.height(12.dp))
        MapTilesToggle(enabled = mapTilesEnabled, onEnabled = onMapTilesEnabled)
    }
}

@Composable
private fun PausedBlock(
    state: RecordingUiState,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    mapTilesEnabled: Boolean,
    onMapTilesEnabled: (Boolean) -> Unit,
    tileLoader: OsmTileLoader?,
) {
    Text(
        text = "Paused",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
    )
    Spacer(Modifier.height(8.dp))
    SessionStats(state)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "GPS is off. Resume the same session or finish and save.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    LivePathSketch(state.pathPoints, mapTilesEnabled, tileLoader)
    if (hasSessionMap(state.pathPoints)) {
        Spacer(Modifier.height(12.dp))
        MapTilesToggle(enabled = mapTilesEnabled, onEnabled = onMapTilesEnabled)
    }
    Spacer(Modifier.height(20.dp))
    HugeButton(text = "RESUME", onClick = onResume, recording = false)
    Spacer(Modifier.height(12.dp))
    FinishButton(onFinish)
}

@Composable
private fun FinishButton(onFinish: () -> Unit) {
    Button(
        onClick = onFinish,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        shape = MaterialTheme.shapes.large,
    ) {
        Text("FINISH", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun HugeButton(text: String, onClick: () -> Unit, recording: Boolean) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (recording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = text,
            fontSize = 48.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun SessionStats(state: RecordingUiState) {
    Text(
        text = RecordingService.formatElapsed(state.elapsedMs),
        fontSize = 56.sp,
        fontWeight = FontWeight.Light,
        textAlign = TextAlign.Center,
    )
    Text(
        text = RecordingService.formatDistance(state.workoutDistanceMeters),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = Geo.formatPoints(state.workoutDistanceMeters),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SettingsBlock(
    autoStartEnabled: Boolean,
    onAutoStartEnabled: (Boolean) -> Unit,
    inactivityFinishSec: Int,
    onInactivityFinishSec: (Int) -> Unit,
    batteryUnrestricted: Boolean,
    onOpenBatterySettings: () -> Unit,
) {
    SettingRow(
        label = "Auto Start",
        checked = autoStartEnabled,
        onChecked = onAutoStartEnabled,
        caption = if (autoStartEnabled) {
            "Starts a session from a walk or jog. Needed to start a session when the screen is off."
        } else {
            "Sessions will not start. GPS stays off."
        },
    )
    Spacer(Modifier.height(8.dp))
    SimpleDropdown(
        label = "Auto-stop after inactivity",
        value = "$inactivityFinishSec s",
        options = listOf("60 s", "90 s", "120 s"),
        onSelected = { selected ->
            onInactivityFinishSec(selected.substringBefore(' ').toInt())
        },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onOpenBatterySettings, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (batteryUnrestricted) {
                "Battery: Unrestricted"
            } else {
                "Battery: not Unrestricted — tap to set"
            },
        )
    }
}

@Composable
private fun MapTilesToggle(enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    SettingRow(label = "Map tiles", checked = enabled, onChecked = onEnabled)
}

@Composable
private fun SettingRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    caption: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            )
            Switch(checked = checked, onCheckedChange = onChecked)
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun hasSessionMap(points: List<PathPoint>): Boolean =
    PathSegments.current(points).size >= 2

@Composable
private fun LivePathSketch(
    points: List<PathPoint>,
    mapTilesEnabled: Boolean,
    tileLoader: OsmTileLoader?,
) {
    val segment = PathSegments.current(points)
    if (segment.size < 2) {
        Text(
            "Not enough distance covered for a path sketch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        SessionMap(
            points = points,
            tilesEnabled = mapTilesEnabled,
            tileLoader = tileLoader,
        )
    }
}

@Composable
private fun Banner(text: String) {
    val error = text.startsWith("Weak") ||
        text.contains("likely-vehicle") ||
        text.contains("Unrestricted")
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ModeChip(mode: MotionGuess?) {
    val label = when (mode) {
        MotionGuess.WORKOUT -> "workout"
        MotionGuess.LIKELY_VEHICLE -> "likely-vehicle"
        null -> "waiting for GPS"
    }
    Text(
        text = "Mode: $label",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
    )
}

private fun watchingReason(
    autoStartEnabled: Boolean,
    watcherRunning: Boolean,
    batteryRestricted: Boolean,
    watcherReason: String?,
): String = when {
    !autoStartEnabled -> "auto start off"
    !watcherRunning && batteryRestricted -> "battery killed"
    !watcherRunning -> "battery killed"
    else -> watcherReason ?: "no steps"
}

private fun gpsLine(state: RecordingUiState): String {
    val accuracy = state.accuracyMeters
    return if (!state.hasFix || accuracy == null) {
        "GPS: no fix"
    } else {
        Geo.formatAccuracyFeet(accuracy)
    }
}
