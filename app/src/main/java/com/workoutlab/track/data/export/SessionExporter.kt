package com.workoutlab.track.data.export

import com.workoutlab.track.data.db.PointEntity
import com.workoutlab.track.data.db.SessionEntity
import java.time.Instant
import java.util.Locale

object SessionExporter {
    fun toJson(session: SessionEntity, points: List<PointEntity>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"app\": \"Workout Lab Track\",\n")
        sb.append("  \"type\": \"workout_session\",\n")
        sb.append("  \"startedAt\": \"").append(iso(session.startedAtEpochMs)).append("\",\n")
        sb.append("  \"stoppedAt\": ").append(session.stoppedAtEpochMs?.let { "\"${iso(it)}\"" } ?: "null").append(",\n")
        sb.append("  \"workoutDistanceMeters\": ").append(num(session.workoutDistanceMeters)).append(",\n")
        sb.append("  \"pointCount\": ").append(session.pointCount).append(",\n")
        sb.append("  \"excludedSampleCount\": ").append(session.excludedSampleCount).append(",\n")
        sb.append("  \"points\": [\n")
        points.forEachIndexed { index, point ->
            sb.append("    { \"lat\": ").append(num(point.latitude))
                .append(", \"lon\": ").append(num(point.longitude))
            if (point.altitudeMeters != null) {
                sb.append(", \"ele\": ").append(num(point.altitudeMeters))
            }
            sb.append(", \"time\": \"").append(iso(point.timestampEpochMs)).append("\"")
            sb.append(", \"acc\": ").append(num(point.accuracyMeters.toDouble()))
            if (point.speedMps != null) {
                sb.append(", \"speed\": ").append(num(point.speedMps.toDouble()))
            }
            sb.append(" }")
            if (index < points.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    fun toGpx(session: SessionEntity, points: List<PointEntity>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Workout Lab Track\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>Workout Lab Track</name>\n")
        sb.append("    <time>").append(iso(session.startedAtEpochMs)).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>Workout</name>\n")
        sb.append("    <trkseg>\n")
        for (point in points) {
            sb.append("      <trkpt lat=\"").append(num(point.latitude))
                .append("\" lon=\"").append(num(point.longitude)).append("\">")
            if (point.altitudeMeters != null) {
                sb.append("<ele>").append(num(point.altitudeMeters)).append("</ele>")
            }
            sb.append("<time>").append(iso(point.timestampEpochMs)).append("</time>")
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private fun num(value: Double): String = String.format(Locale.US, "%.7f", value)
}
