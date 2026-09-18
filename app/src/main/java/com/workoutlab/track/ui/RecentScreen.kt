package com.workoutlab.track.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workoutlab.track.map.OsmTileLoader
import com.workoutlab.track.map.PathSegments
import com.workoutlab.track.recording.Geo
import com.workoutlab.track.recording.LastSessionUi
import com.workoutlab.track.recording.PathPoint
import com.workoutlab.track.recording.RecordingService

@Composable
fun RecentScreen(
    sessions: List<LastSessionUi>,
    sortByDistance: Boolean,
    onSortByDistance: (Boolean) -> Unit,
    mapTilesEnabled: Boolean,
    tileLoader: OsmTileLoader?,
    onExportJson: (Long) -> Unit,
    onExportGpx: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = if (sortByDistance) {
        sessions.sortedByDescending { it.workoutDistanceMeters }
    } else {
        sessions.sortedByDescending { it.stoppedAtEpochMs }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "Recent",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        SimpleDropdown(
            label = "Sort",
            value = if (sortByDistance) "Most distance" else "Most recent",
            options = listOf("Most recent", "Most distance"),
            onSelected = { onSortByDistance(it == "Most distance") },
        )
        Spacer(Modifier.height(12.dp))
        if (sorted.isEmpty()) {
            Text(
                text = "No finished sessions yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(sorted, key = { it.id }) { session ->
                    RecentSessionCard(
                        session = session,
                        mapTilesEnabled = mapTilesEnabled,
                        tileLoader = tileLoader,
                        onExportJson = { onExportJson(session.id) },
                        onExportGpx = { onExportGpx(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSessionCard(
    session: LastSessionUi,
    mapTilesEnabled: Boolean,
    tileLoader: OsmTileLoader?,
    onExportJson: () -> Unit,
    onExportGpx: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = Geo.formatSessionWhen(session.startedAtEpochMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text("Time  ${RecordingService.formatElapsed(session.durationMs)}")
            Text("Distance  ${RecordingService.formatDistance(session.workoutDistanceMeters)}")
            Text("Points  ${Geo.formatPoints(session.workoutDistanceMeters)}")
            Spacer(Modifier.height(12.dp))
            RecentPath(session.points, mapTilesEnabled, tileLoader)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onExportJson, modifier = Modifier.weight(1f)) {
                    Text("Export JSON")
                }
                OutlinedButton(onClick = onExportGpx, modifier = Modifier.weight(1f)) {
                    Text("Export GPX")
                }
            }
        }
    }
}

@Composable
private fun RecentPath(
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
