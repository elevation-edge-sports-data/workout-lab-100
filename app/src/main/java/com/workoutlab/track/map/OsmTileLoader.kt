package com.workoutlab.track.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches OSM raster tiles only. No location, session, or path is uploaded.
 * Callers must not invoke [load] when the Map tiles toggle is off.
 */
class OsmTileLoader(cacheDir: File) {
    private val diskDir = File(cacheDir, "osm-tiles").apply { mkdirs() }
    private val memory = LinkedHashMap<TileId, Bitmap>(32, 0.75f, true)
    private val memoryLock = Mutex()
    private val downloads = Semaphore(2)

    @Volatile
    var networkAllowed: Boolean = true

    suspend fun load(id: TileId): Bitmap? {
        if (!networkAllowed) return null
        memoryLock.withLock {
            memory[id]?.let { return it }
        }
        val disk = diskFile(id)
        if (disk.exists()) {
            val fromDisk = BitmapFactory.decodeFile(disk.absolutePath)
            if (fromDisk != null) {
                putMemory(id, fromDisk)
                return fromDisk
            }
        }
        if (!networkAllowed) return null
        downloads.acquire()
        val downloaded = try {
            download(id)
        } finally {
            downloads.release()
        } ?: return null
        putMemory(id, downloaded)
        return downloaded
    }

    private suspend fun download(id: TileId): Bitmap? = withContext(Dispatchers.IO) {
        if (!networkAllowed) return@withContext null
        val n = OsmTileMath.zoomCount(id.zoom)
        if (id.x < 0 || id.y < 0 || id.x >= n || id.y >= n) return@withContext null
        val url = URL("https://tile.openstreetmap.org/${id.zoom}/${id.x}/${id.y}.png")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val bytes = connection.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val file = diskFile(id)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            bitmap
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun putMemory(id: TileId, bitmap: Bitmap) {
        memoryLock.withLock {
            memory[id] = bitmap
            while (memory.size > 64) {
                val oldest = memory.entries.firstOrNull()?.key ?: break
                memory.remove(oldest)
            }
        }
    }

    private fun diskFile(id: TileId): File =
        File(diskDir, "${id.zoom}/${id.x}/${id.y}.png")

    companion object {
        const val USER_AGENT =
            "WorkoutLabTrack/0.1 (calibration; Android; local workout recorder; tiles only)"
    }
}
