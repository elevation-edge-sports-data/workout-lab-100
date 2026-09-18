package com.workoutlab.track.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val SESSION_RECORDING = "recording"
const val SESSION_PAUSED = "paused"
const val SESSION_COMPLETE = "complete"

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long? = null,
    val workoutDistanceMeters: Double = 0.0,
    val pointCount: Int = 0,
    val excludedSampleCount: Int = 0,
    val recordedElapsedMs: Long = 0,
    val status: String,
)

@Entity(
    tableName = "points",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val timestampEpochMs: Long,
    val startsSegment: Boolean = false,
)
