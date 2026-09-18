package com.workoutlab.track.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.workoutlab.track.recording.PathPoint
import kotlin.math.max

@Composable
fun PathSketch(
    points: List<PathPoint>,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.error
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
    ) {
        if (points.size < 2) return@Canvas
        val lats = points.map { it.latitude }
        val lons = points.map { it.longitude }
        val minLat = lats.min()
        val maxLat = lats.max()
        val minLon = lons.min()
        val maxLon = lons.max()
        val latSpan = max(maxLat - minLat, 0.00001)
        val lonSpan = max(maxLon - minLon, 0.00001)
        val pad = 16.dp.toPx()
        val usableW = (size.width - pad * 2).coerceAtLeast(1f)
        val usableH = (size.height - pad * 2).coerceAtLeast(1f)

        fun x(lon: Double): Float = pad + ((lon - minLon) / lonSpan).toFloat() * usableW
        fun y(lat: Double): Float = pad + (1f - ((lat - minLat) / latSpan).toFloat()) * usableH

        val path = Path()
        points.forEachIndexed { index, point ->
            val px = x(point.longitude)
            val py = y(point.latitude)
            if (index == 0 || point.startsSegment) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = trackColor,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val first = points.first()
        val last = points.last()
        drawCircle(startColor, radius = 7.dp.toPx(), center = Offset(x(first.longitude), y(first.latitude)))
        drawCircle(endColor, radius = 7.dp.toPx(), center = Offset(x(last.longitude), y(last.latitude)))
    }
}
