package com.workoutlab.track.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Insert
    suspend fun insertPoint(point: PointEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status = :status")
    suspend fun sessionsWithStatus(status: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE status IN ('recording', 'paused') ORDER BY startedAtEpochMs DESC")
    suspend fun incompleteSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE status = 'complete' ORDER BY startedAtEpochMs DESC LIMIT 1")
    fun lastCompleteSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE status = 'complete' ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun lastCompleteSessionOnce(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status = 'complete' ORDER BY startedAtEpochMs DESC LIMIT 100")
    suspend fun completeSessions(): List<SessionEntity>

    @Query("SELECT * FROM points WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
    suspend fun pointsFor(sessionId: Long): List<PointEntity>

    @Query("SELECT COUNT(*) FROM points WHERE sessionId = :sessionId")
    suspend fun pointCount(sessionId: Long): Int

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
