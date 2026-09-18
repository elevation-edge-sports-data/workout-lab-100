package com.workoutlab.track.recording

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.workoutlab.track.data.db.AppDatabase
import com.workoutlab.track.data.db.PointEntity
import com.workoutlab.track.data.db.SESSION_COMPLETE
import com.workoutlab.track.data.db.SESSION_PAUSED
import com.workoutlab.track.data.db.SESSION_RECORDING
import com.workoutlab.track.data.db.SessionEntity
import com.workoutlab.track.data.export.SessionArchive
import com.workoutlab.track.data.export.SessionExporter
import com.workoutlab.track.settings.TrackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RecordingRepository(
    private val app: Application,
    private val settings: TrackSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = AppDatabase.create(app).sessionDao()
    private val mutex = Mutex()
    private val track = WorkoutTrack()

    private val _ui = MutableStateFlow(RecordingUiState())
    val ui: StateFlow<RecordingUiState> = _ui.asStateFlow()

    private val _lastSession = MutableStateFlow<LastSessionUi?>(null)
    val lastSession: StateFlow<LastSessionUi?> = _lastSession.asStateFlow()

    private val _recentSessions = MutableStateFlow<List<LastSessionUi>>(emptyList())
    val recentSessions: StateFlow<List<LastSessionUi>> = _recentSessions.asStateFlow()

    @Volatile
    private var activeSessionId: Long? = null

    @Volatile
    private var finishing = false

    init {
        scope.launch {
            restoreIncompleteOrPause()
            refreshLastSession()
            mutex.withLock { syncWatcherLocked() }
        }
    }

    fun setMessage(message: String?) {
        _ui.update { it.copy(message = message) }
    }

    fun setWatcherReason(reason: String) {
        _ui.update { it.copy(watcherReason = reason) }
    }

    fun startRecording() {
        scope.launch {
            var started = false
            mutex.withLock {
                if (settings.eventLock.value) {
                    _ui.update {
                        it.copy(message = "Auto Start is off. Turn it on to start a session.")
                    }
                    return@withLock
                }
                if (_ui.value.phase != SessionPhase.Idle || activeSessionId != null) return@withLock
                finishing = false
                WatcherService.stop(app)
                val now = System.currentTimeMillis()
                val id = dao.insertSession(
                    SessionEntity(
                        startedAtEpochMs = now,
                        status = SESSION_RECORDING,
                    ),
                )
                track.reset()
                track.markActivity(now)
                activeSessionId = id
                _ui.value = RecordingUiState(
                    phase = SessionPhase.Recording,
                    startedAtEpochMs = now,
                    recordedElapsedMs = 0,
                    segmentStartedAtEpochMs = now,
                    elapsedMs = 0,
                    motionGuess = MotionGuess.WORKOUT,
                    promotedAtEpochMs = now,
                    firstPointAtEpochMs = null,
                )
                started = true
            }
            if (started) startService()
        }
    }

    fun setAutostartEnabled(enabled: Boolean) {
        settings.setAutostartEnabled(enabled)
        syncWatcher()
    }

    fun setEventLock(enabled: Boolean) {
        settings.setEventLock(enabled)
        if (enabled) {
            setMessage("Auto Start is off. Sessions will not start. GPS stays off.")
        } else {
            setMessage(null)
        }
        syncWatcher()
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        setEventLock(!enabled)
    }

    fun syncWatcher() {
        scope.launch {
            mutex.withLock { syncWatcherLocked() }
        }
    }

    fun resumeRecording() {
        scope.launch {
            var started = false
            mutex.withLock {
                if (settings.eventLock.value) {
                    _ui.update {
                        it.copy(message = "Auto Start is off. Turn it on to start a session.")
                    }
                    return@withLock
                }
                if (_ui.value.phase != SessionPhase.Paused || activeSessionId == null) return@withLock
                val now = System.currentTimeMillis()
                snapshotProgress(SESSION_RECORDING, elapsedNow())
                track.markActivity(now)
                _ui.update {
                    it.copy(
                        phase = SessionPhase.Recording,
                        segmentStartedAtEpochMs = now,
                        message = null,
                    )
                }
                started = true
            }
            if (started) startService()
        }
    }

    fun requestPause() {
        val intent = Intent(app, RecordingService::class.java)
            .setAction(RecordingService.ACTION_PAUSE)
        app.startService(intent)
    }

    fun requestFinish() {
        if (finishing) return
        if (_ui.value.phase == SessionPhase.Idle) return
        finishing = true
        if (_ui.value.phase == SessionPhase.Recording) {
            val intent = Intent(app, RecordingService::class.java)
                .setAction(RecordingService.ACTION_FINISH)
            app.startService(intent)
        }
        scope.launch { finishRecording() }
    }

    fun onStep(atMs: Long = System.currentTimeMillis()) {
        scope.launch {
            mutex.withLock { track.onStep(atMs) }
        }
    }

    fun setInVehicleReported(inVehicle: Boolean) {
        scope.launch {
            mutex.withLock { track.setInVehicleReported(inVehicle) }
        }
    }

    suspend fun onLocationSample(sample: GeoSample) {
        var autoFinish = false
        mutex.withLock {
            if (_ui.value.phase != SessionPhase.Recording) return
            val sessionId = activeSessionId ?: return
            val update = track.onSample(sample)
            _ui.update { state ->
                state.copy(
                    workoutDistanceMeters = update.distanceMeters,
                    accuracyMeters = sample.accuracyMeters,
                    hasFix = true,
                    motionGuess = update.guess,
                    pointCount = update.pointCount,
                    excludedSampleCount = update.excludedSampleCount,
                    pathPoints = update.pathPoints,
                    message = null,
                )
            }
            autoFinish = update.shouldFinishSession
            val accepted = update.accepted
            if (accepted != null && _ui.value.firstPointAtEpochMs == null) {
                _ui.update { it.copy(firstPointAtEpochMs = accepted.timestampEpochMs) }
            }
            if (!autoFinish && accepted != null) {
                dao.insertPoint(
                    PointEntity(
                        sessionId = sessionId,
                        latitude = accepted.latitude,
                        longitude = accepted.longitude,
                        altitudeMeters = accepted.altitudeMeters,
                        accuracyMeters = accepted.accuracyMeters,
                        speedMps = accepted.speedMps?.toFloat(),
                        timestampEpochMs = accepted.timestampEpochMs,
                        startsSegment = update.pathPoints.lastOrNull()?.startsSegment == true,
                    ),
                )
            }
        }
        if (autoFinish) requestFinish()
    }

    fun tickElapsed() {
        val state = _ui.value
        if (state.phase != SessionPhase.Recording) return
        _ui.update { it.copy(elapsedMs = elapsedNow()) }
        val now = System.currentTimeMillis()
        if (track.shouldFinish(now)) {
            requestFinish()
            return
        }
        val timeoutMs = settings.inactivityFinishSec.value * 1_000L
        if (track.shouldFinishInactivity(now, timeoutMs)) {
            requestFinish()
        }
    }

    suspend fun pauseRecording() {
        mutex.withLock {
            if (_ui.value.phase != SessionPhase.Recording) return
            val elapsed = elapsedNow()
            track.markGap()
            snapshotProgress(SESSION_PAUSED, elapsed)
            _ui.update {
                it.copy(
                    phase = SessionPhase.Paused,
                    recordedElapsedMs = elapsed,
                    segmentStartedAtEpochMs = null,
                    elapsedMs = elapsed,
                )
            }
        }
    }

    suspend fun finishRecording() {
        mutex.withLock {
            val sessionId = activeSessionId ?: return
            if (_ui.value.phase == SessionPhase.Idle) return
            val existing = dao.getSession(sessionId) ?: return
            if (existing.status == SESSION_COMPLETE) {
                activeSessionId = null
                _ui.value = RecordingUiState()
                refreshLastSession()
                syncWatcherLocked()
                return
            }
            val empty = track.pointCount < 2 && track.distanceMeters < 0.5
            val timingPromoted = _ui.value.promotedAtEpochMs
            val timingFirst = _ui.value.firstPointAtEpochMs
            if (empty) {
                dao.deleteSession(sessionId)
                activeSessionId = null
                track.reset()
                _ui.value = RecordingUiState(
                    promotedAtEpochMs = timingPromoted,
                    firstPointAtEpochMs = timingFirst,
                )
                refreshLastSession()
                syncWatcherLocked()
                return
            }
            val elapsed = if (_ui.value.phase == SessionPhase.Recording) elapsedNow() else _ui.value.elapsedMs
            val completed = existing.copy(
                stoppedAtEpochMs = System.currentTimeMillis(),
                workoutDistanceMeters = track.distanceMeters,
                pointCount = track.pointCount,
                excludedSampleCount = track.excludedSampleCount,
                recordedElapsedMs = elapsed,
                status = SESSION_COMPLETE,
            )
            dao.updateSession(completed)
            val points = dao.pointsFor(sessionId)
            SessionArchive.save(app, completed, points)
            activeSessionId = null
            track.reset()
            _ui.value = RecordingUiState(
                promotedAtEpochMs = timingPromoted,
                firstPointAtEpochMs = timingFirst,
            )
            refreshLastSession()
            syncWatcherLocked()
        }
    }

    suspend fun exportJson(sessionId: Long? = null): String? = withContext(Dispatchers.IO) {
        val session = if (sessionId != null) dao.getSession(sessionId) else dao.lastCompleteSessionOnce()
        if (session == null || session.status != SESSION_COMPLETE) return@withContext null
        SessionExporter.toJson(session, dao.pointsFor(session.id))
    }

    suspend fun exportGpx(sessionId: Long? = null): String? = withContext(Dispatchers.IO) {
        val session = if (sessionId != null) dao.getSession(sessionId) else dao.lastCompleteSessionOnce()
        if (session == null || session.status != SESSION_COMPLETE) return@withContext null
        SessionExporter.toGpx(session, dao.pointsFor(session.id))
    }

    private fun startService() {
        val intent = Intent(app, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
        ContextCompat.startForegroundService(app, intent)
    }

    private fun syncWatcherLocked() {
        val should = _ui.value.phase == SessionPhase.Idle && settings.watcherShouldRun()
        if (should) {
            WatcherService.start(app)
        } else {
            WatcherService.stop(app)
        }
    }

    private fun elapsedNow(): Long {
        val state = _ui.value
        val running = state.segmentStartedAtEpochMs?.let { System.currentTimeMillis() - it } ?: 0L
        return (state.recordedElapsedMs + running).coerceAtLeast(0)
    }

    private suspend fun snapshotProgress(status: String, elapsedMs: Long) {
        val sessionId = activeSessionId ?: return
        val existing = dao.getSession(sessionId) ?: return
        dao.updateSession(
            existing.copy(
                workoutDistanceMeters = track.distanceMeters,
                pointCount = track.pointCount,
                excludedSampleCount = track.excludedSampleCount,
                recordedElapsedMs = elapsedMs,
                status = status,
            ),
        )
    }

    private suspend fun restoreIncompleteOrPause() {
        mutex.withLock {
            val incomplete = dao.incompleteSessions()
            if (incomplete.isEmpty()) return
            val keep = mutableListOf<SessionEntity>()
            for (session in incomplete) {
                val points = dao.pointsFor(session.id)
                val empty = points.isEmpty() && session.workoutDistanceMeters < 0.5
                if (empty) {
                    dao.deleteSession(session.id)
                } else {
                    keep += session
                }
            }
            if (keep.isEmpty()) {
                activeSessionId = null
                track.reset()
                _ui.value = RecordingUiState()
                return
            }
            val newest = keep.first()
            for (older in keep.drop(1)) {
                val points = dao.pointsFor(older.id)
                val completed = older.copy(
                    stoppedAtEpochMs = older.stoppedAtEpochMs ?: System.currentTimeMillis(),
                    pointCount = points.size,
                    status = SESSION_COMPLETE,
                )
                dao.updateSession(completed)
                SessionArchive.save(app, completed, points)
            }
            val points = dao.pointsFor(newest.id)
            val path = points.map { PathPoint(it.latitude, it.longitude, it.startsSegment) }
            track.restore(path, newest.workoutDistanceMeters, newest.excludedSampleCount)
            activeSessionId = newest.id
            val elapsed = newest.recordedElapsedMs
            snapshotProgress(SESSION_PAUSED, elapsed)
            _ui.value = RecordingUiState(
                phase = SessionPhase.Paused,
                startedAtEpochMs = newest.startedAtEpochMs,
                recordedElapsedMs = elapsed,
                elapsedMs = elapsed,
                workoutDistanceMeters = newest.workoutDistanceMeters,
                pointCount = newest.pointCount,
                excludedSampleCount = newest.excludedSampleCount,
                pathPoints = path,
                promotedAtEpochMs = newest.startedAtEpochMs,
            )
        }
    }

    private suspend fun refreshLastSession() {
        val sessions = dao.completeSessions()
        val mapped = sessions.map { session ->
            val points = dao.pointsFor(session.id)
            val stopped = session.stoppedAtEpochMs ?: session.startedAtEpochMs
            LastSessionUi(
                id = session.id,
                startedAtEpochMs = session.startedAtEpochMs,
                stoppedAtEpochMs = stopped,
                durationMs = session.recordedElapsedMs.takeIf { it > 0 }
                    ?: (stopped - session.startedAtEpochMs).coerceAtLeast(0),
                workoutDistanceMeters = session.workoutDistanceMeters,
                pointCount = session.pointCount,
                excludedSampleCount = session.excludedSampleCount,
                points = points.map {
                    PathPoint(it.latitude, it.longitude, it.startsSegment)
                },
            )
        }
        _recentSessions.value = mapped
        _lastSession.value = mapped.firstOrNull()
    }
}
