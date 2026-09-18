package com.workoutlab.track.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.workoutlab.track.map.OsmTileLoader
import com.workoutlab.track.map.OsmTileMath
import com.workoutlab.track.map.PathSegments
import com.workoutlab.track.map.PathSmoothing
import com.workoutlab.track.map.TileId
import com.workoutlab.track.recording.PathPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

@Composable
fun SessionMap(
    points: List<PathPoint>,
    tilesEnabled: Boolean,
    tileLoader: OsmTileLoader?,
    modifier: Modifier = Modifier,
    currentSegmentOnly: Boolean = true,
) {
    val segment = if (currentSegmentOnly) PathSegments.current(points) else points
    val drawn = remember(segment) { PathSmoothing.light(segment) }
    if (drawn.size < 2) {
        PathSketch(points = drawn, modifier = modifier)
        return
    }

    val trackColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.error
    val bg = MaterialTheme.colorScheme.surfaceVariant
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    var tiles by remember { mutableStateOf<Map<TileId, Bitmap>>(emptyMap()) }
    var tileFailed by remember { mutableStateOf(false) }
    var zoomInt by remember { mutableIntStateOf(15) }
    var scale by remember { mutableFloatStateOf(1f) }
    var originX by remember { mutableFloatStateOf(0f) }
    var originY by remember { mutableFloatStateOf(0f) }
    var userAdjusted by remember { mutableStateOf(false) }

    val bounds = remember(segment) { boundsOf(segment) }
    val sessionKey = remember(segment.first()) {
        val first = segment.first()
        "${first.latitude},${first.longitude}"
    }

    LaunchedEffect(sessionKey) {
        userAdjusted = false
    }

    LaunchedEffect(sessionKey, viewW, viewH, userAdjusted, bounds) {
        if (viewW <= 0 || viewH <= 0 || userAdjusted) return@LaunchedEffect
        zoomInt = zoomToFit(bounds, viewW.toFloat(), viewH.toFloat())
        scale = 1f
        val cx = (OsmTileMath.worldX(bounds.minLon, zoomInt) + OsmTileMath.worldX(bounds.maxLon, zoomInt)) / 2.0
        val cy = (OsmTileMath.worldY(bounds.minLat, zoomInt) + OsmTileMath.worldY(bounds.maxLat, zoomInt)) / 2.0
        originX = (cx - viewW / 2.0).toFloat()
        originY = (cy - viewH / 2.0).toFloat()
    }

    val useTiles = tilesEnabled && tileLoader != null && !(tileFailed && tiles.isEmpty())

    LaunchedEffect(useTiles, zoomInt, originX, originY, scale, viewW, viewH, tileLoader) {
        if (!tilesEnabled || viewW <= 0 || viewH <= 0 || tileLoader == null) {
            tiles = emptyMap()
            return@LaunchedEffect
        }
        tileLoader.networkAllowed = true
        val visW = viewW / scale
        val visH = viewH / scale
        val x0 = floor(originX / OsmTileMath.TILE_SIZE).toInt()
        val y0 = floor(originY / OsmTileMath.TILE_SIZE).toInt()
        val x1 = ceil((originX + visW) / OsmTileMath.TILE_SIZE).toInt()
        val y1 = ceil((originY + visH) / OsmTileMath.TILE_SIZE).toInt()
        val loaded = LinkedHashMap<TileId, Bitmap>()
        var anyFail = false
        for (x in x0..x1) {
            for (y in y0..y1) {
                val id = TileId(zoomInt, x, y)
                val bitmap = tileLoader.load(id)
                if (bitmap != null) loaded[id] = bitmap else anyFail = true
            }
        }
        tiles = loaded
        tileFailed = loaded.isEmpty() && anyFail
    }

    val mapHeight = 280.dp
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(mapHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .onSizeChanged {
                viewW = it.width
                viewH = it.height
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var lastCentroid = Offset.Unspecified
                    var lastSpan = 0f
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        pressed.forEach { it.consume() }
                        val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } /
                            pressed.size.toFloat()
                        if (pressed.size >= 2) {
                            val span = (pressed[0].position - pressed[1].position).getDistance()
                            if (lastSpan > 0f && span > 0f) {
                                val factor = span / lastSpan
                                val worldX = originX + centroid.x / scale
                                val worldY = originY + centroid.y / scale
                                var nextScale = (scale * factor).coerceIn(0.45f, 2.4f)
                                var nextZoom = zoomInt
                                if (nextScale > 1.85f && nextZoom < OsmTileMath.MAX_ZOOM) {
                                    nextZoom += 1
                                    nextScale /= 2f
                                    originX = worldX * 2f - centroid.x / nextScale
                                    originY = worldY * 2f - centroid.y / nextScale
                                } else if (nextScale < 0.55f && nextZoom > OsmTileMath.MIN_ZOOM) {
                                    nextZoom -= 1
                                    nextScale *= 2f
                                    originX = worldX / 2f - centroid.x / nextScale
                                    originY = worldY / 2f - centroid.y / nextScale
                                } else {
                                    originX = worldX - centroid.x / nextScale
                                    originY = worldY - centroid.y / nextScale
                                }
                                scale = nextScale
                                zoomInt = nextZoom
                                userAdjusted = true
                            }
                            lastSpan = span
                        } else {
                            lastSpan = 0f
                            if (lastCentroid != Offset.Unspecified) {
                                val pan = centroid - lastCentroid
                                originX -= pan.x / scale
                                originY -= pan.y / scale
                                userAdjusted = true
                            }
                        }
                        lastCentroid = centroid
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        fun mapX(lon: Double): Float = ((OsmTileMath.worldX(lon, zoomInt) - originX) * scale).toFloat()
        fun mapY(lat: Double): Float = ((OsmTileMath.worldY(lat, zoomInt) - originY) * scale).toFloat()

        if (useTiles) {
            val tileDraw = (OsmTileMath.TILE_SIZE * scale).toInt().coerceAtLeast(1)
            tiles.forEach { (id, bitmap) ->
                val left = ((id.x * OsmTileMath.TILE_SIZE - originX) * scale)
                val top = ((id.y * OsmTileMath.TILE_SIZE - originY) * scale)
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                    dstSize = IntSize(tileDraw, tileDraw),
                    filterQuality = FilterQuality.Low,
                )
            }
        }

        val path = Path()
        drawn.forEachIndexed { index, point ->
            val px = mapX(point.longitude)
            val py = mapY(point.latitude)
            if (index == 0 || point.startsSegment) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = trackColor,
            style = Stroke(
                width = 4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        val first = drawn.first()
        val last = drawn.last()
        drawCircle(startColor, radius = 7.dp.toPx(), center = Offset(mapX(first.longitude), mapY(first.latitude)))
        drawCircle(endColor, radius = 7.dp.toPx(), center = Offset(mapX(last.longitude), mapY(last.latitude)))
    }
}

private data class GeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

private fun boundsOf(points: List<PathPoint>): GeoBounds {
    val lats = points.map { it.latitude }
    val lons = points.map { it.longitude }
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLon = lons.min()
    val maxLon = lons.max()
    val latPad = max((maxLat - minLat) * 0.12, 0.0004)
    val lonPad = max((maxLon - minLon) * 0.12, 0.0004)
    return GeoBounds(minLat - latPad, maxLat + latPad, minLon - lonPad, maxLon + lonPad)
}

private fun zoomToFit(bounds: GeoBounds, viewW: Float, viewH: Float): Int {
    for (z in OsmTileMath.MAX_ZOOM downTo OsmTileMath.MIN_ZOOM) {
        val w = abs(OsmTileMath.worldX(bounds.maxLon, z) - OsmTileMath.worldX(bounds.minLon, z))
        val h = abs(OsmTileMath.worldY(bounds.minLat, z) - OsmTileMath.worldY(bounds.maxLat, z))
        if (w <= viewW * 0.92 && h <= viewH * 0.92) return z
    }
    return OsmTileMath.MIN_ZOOM
}
