package com.workoutlab.track.data.export

import android.content.Context
import com.workoutlab.track.data.db.PointEntity
import com.workoutlab.track.data.db.SessionEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes every stopped workout to app-specific storage. Filenames include the
 * session id so a new Start never overwrites an older file.
 */
object SessionArchive {
    fun save(context: Context, session: SessionEntity, points: List<PointEntity>) {
        val dir = directory(context)
        val base = fileBase(session.startedAtEpochMs, session.id)
        File(dir, "$base.json").writeText(SessionExporter.toJson(session, points), Charsets.UTF_8)
        File(dir, "$base.gpx").writeText(SessionExporter.toGpx(session, points), Charsets.UTF_8)
    }

    fun fileBase(startedAtEpochMs: Long, id: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAtEpochMs))
        return "workout-$stamp-$id"
    }

    private fun directory(context: Context): File {
        val parent = context.getExternalFilesDir(null) ?: context.filesDir
        return File(parent, "sessions").apply { mkdirs() }
    }
}
