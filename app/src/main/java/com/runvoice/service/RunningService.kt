package com.runvoice.service

import android.app.*
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.runvoice.MainActivity
import com.runvoice.R
import com.runvoice.core.RunCommand
import com.runvoice.core.RunSessionController
import com.runvoice.core.RunSessionState
import com.runvoice.core.AnnouncementEvent
import com.runvoice.core.AnnouncementPolicy
import com.runvoice.core.HeartRateAlertPolicy
import com.runvoice.core.TrackingAlert
import com.runvoice.model.RunData
import com.runvoice.recovery.RunCheckpoint
import com.runvoice.recovery.RunCheckpointStore
import com.runvoice.tracker.GpsTracker
import com.runvoice.tracker.HeartRateMonitor
import com.runvoice.tracker.HeartRateState
import com.runvoice.tracker.MotionDetector
import com.runvoice.tracker.RunTimer
import com.runvoice.tracker.TraceSaveResult
import com.runvoice.voice.Metronome
import com.runvoice.voice.VoiceAnnouncer
import com.runvoice.voice.VoiceStatsText
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RunningService : Service() {

    companion object {
        private const val TAG = "RunVoiceService"
        const val CHANNEL_ID = "running_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.runvoice.START"
        const val ACTION_START_NEW = "com.runvoice.START_NEW"
        const val ACTION_CONTINUE_PREVIOUS = "com.runvoice.CONTINUE_PREVIOUS"
        const val ACTION_PAUSE = "com.runvoice.PAUSE"
        const val ACTION_RESUME = "com.runvoice.RESUME"
        const val ACTION_INTERRUPT_FOR_RECOVERY = "com.runvoice.INTERRUPT_FOR_RECOVERY"
        const val ACTION_STOP = "com.runvoice.STOP"
        const val ACTION_TEST_ANNOUNCE = "com.runvoice.TEST_ANNOUNCE"
        const val ACTION_HANDLE_MEDIA_BUTTON = "com.runvoice.HANDLE_MEDIA_BUTTON"
        private const val STATIONARY_PROMPT_DELAY_MS = 0L
        private const val STATIONARY_PROMPT_MIN_INTERVAL_MS = 60_000L
        private const val HEART_RATE_DISCONNECT_PROMPT_DELAY_MS = 5_000L
        private const val CHECKPOINT_INTERVAL_SECONDS = 5L
    }

    inner class RunBinder : Binder() {
        val service: RunningService get() = this@RunningService
    }

    private val binder = RunBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sessionController = RunSessionController()
    private val announcementPolicy = AnnouncementPolicy()
    private val heartRateAlertPolicy = HeartRateAlertPolicy()
    private val checkpointMutex = Mutex()
    private lateinit var prefs: SharedPreferences
    private lateinit var checkpointStore: RunCheckpointStore

    private lateinit var gpsTracker: GpsTracker
    private lateinit var heartRateMonitor: HeartRateMonitor
    private lateinit var runTimer: RunTimer
    private lateinit var voiceAnnouncer: VoiceAnnouncer
    private lateinit var metronome: Metronome
    private lateinit var motionDetector: MotionDetector

    private val _runData = MutableStateFlow(RunData())
    val runData: StateFlow<RunData> = _runData.asStateFlow()
    private val _recoveryInProgress = MutableStateFlow(false)
    val recoveryInProgress: StateFlow<Boolean> = _recoveryInProgress.asStateFlow()
    private val _recoveryError = MutableStateFlow<String?>(null)
    val recoveryError: StateFlow<String?> = _recoveryError.asStateFlow()
    val heartRateState: StateFlow<HeartRateState> get() = heartRateMonitor.state
    val heartRateScanning: StateFlow<Boolean> get() = heartRateMonitor.scanning
    val heartRateDevices: StateFlow<List<HeartRateMonitor.BleDevice>> get() = heartRateMonitor.discoveredDevices

    private var collectJob: Job? = null
    private var stationaryPromptJob: Job? = null
    private var trackerEventJob: Job? = null
    private var heartRateConnectionPromptJob: Job? = null
    private var preRunHrJob: Job? = null
    private var maxHeartRate = 0
    private var mediaSession: MediaSession? = null
    private var lastMediaButtonHandledAt = 0L
    private var lastStationaryPromptAt = 0L
    private var lastLapElapsedSeconds = 0L
    private var sessionStartedAtEpochMillis = 0L
    private var lastCheckpointElapsedSeconds = -CHECKPOINT_INTERVAL_SECONDS
    private var lastCheckpointWrittenElapsedSeconds = -1L
    @Volatile
    private var checkpointGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = buildMediaSession()
        prefs = getSharedPreferences("runvoice", MODE_PRIVATE)
        checkpointStore = RunCheckpointStore(this)
        motionDetector = MotionDetector(this)
        gpsTracker = GpsTracker(this, motionDetector)
        heartRateMonitor = HeartRateMonitor(this)
        gpsTracker.setHeartRateProviders(
            heartRateProvider = { heartRateMonitor.heartRate.value },
            hrConnectedProvider = { heartRateMonitor.connected.value }
        )
        runTimer = RunTimer()
        voiceAnnouncer = VoiceAnnouncer(this, onRecovered = ::announceVoiceRecovery)
        metronome = Metronome()
        // Restore saved metronome BPM and auto-start if was active
        metronome.setBpm(prefs.getInt("metronome_bpm", 180))
        if (prefs.getBoolean("metronome_active", false) && checkpointStore.load() == null) {
            metronome.start()
        }
        // Auto-connect saved HR device on service creation
        heartRateMonitor.connectSavedDevice()
        startPreRunHrObservation()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRun()
            ACTION_START_NEW -> {
                if (!hasActiveRunSession() && !_recoveryInProgress.value) {
                    if (discardRecoverableRun()) startRun()
                }
            }
            ACTION_CONTINUE_PREVIOUS -> continuePreviousRun()
            ACTION_PAUSE -> pauseRun()
            ACTION_RESUME -> resumeRun()
            ACTION_INTERRUPT_FOR_RECOVERY -> interruptForRecovery()
            ACTION_STOP -> serviceScope.launch { stopRun() }
            ACTION_HANDLE_MEDIA_BUTTON,
            Intent.ACTION_MEDIA_BUTTON -> dispatchMediaButtonIntent(intent)
            ACTION_TEST_ANNOUNCE -> {
                val data = _runData.value
                voiceAnnouncer.announceKilometer(
                    km = maxOf(1, (data.distanceMeters / 1000).toInt()),
                    elapsedSeconds = data.elapsedSeconds,
                    heartRate = data.heartRate,
                    paceSecondsPerKm = data.paceSecondsPerKm
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun startRun() {
        if (checkpointStore.load() != null) {
            _recoveryError.value = "检测到未完成的跑步，请先选择继续上次跑步或开始新记录"
            return
        }
        if (!sessionController.dispatch(RunCommand.Start).accepted) {
            Log.w(TAG, "Ignoring Start in ${sessionController.state}")
            return
        }
        _recoveryError.value = null
        checkpointGeneration++
        sessionStartedAtEpochMillis = System.currentTimeMillis()
        lastCheckpointElapsedSeconds = -CHECKPOINT_INTERVAL_SECONDS
        lastCheckpointWrittenElapsedSeconds = -1L
        preRunHrJob?.cancel()
        announcementPolicy.reset()
        heartRateAlertPolicy.reset()
        lastLapElapsedSeconds = 0L
        maxHeartRate = heartRateMonitor.heartRate.value.coerceAtLeast(0)
        _runData.value = RunData(
            heartRate = heartRateMonitor.heartRate.value,
            maxHeartRate = maxHeartRate,
            isRunning = true,
            hrDeviceConnected = heartRateMonitor.connected.value,
            metronomeActive = metronome.isPlaying.value,
            metronomeBpm = metronome.bpm.value
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification("跑步中 00:00"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("跑步中 00:00"))
        }
        updateMediaSession(active = true, paused = false)

        runTimer.start(serviceScope)
        val gpsStart = gpsTracker.start()
        if (gpsStart.isFailure) {
            Log.e(TAG, "Unable to start GPS tracking", gpsStart.exceptionOrNull())
            runTimer.reset()
            gpsTracker.stop(saveSession = false)
            checkpointGeneration++
            sessionController.dispatch(RunCommand.BeginFinish)
            sessionController.dispatch(RunCommand.CompleteFinish)
            _runData.update { it.copy(isRunning = false, isPaused = false) }
            updateMediaSession(active = false, paused = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            startPreRunHrObservation()
            voiceAnnouncer.speak("无法启动定位，本次跑步未开始")
            return
        }
        motionDetector.start()
        // Only connect if not already connected
        if (!heartRateMonitor.connected.value) {
            heartRateMonitor.connectSavedDevice()
        }

        startCollecting()
        startTrackerEventObservation()
        startStationaryPromptObservation()
        startHeartRateConnectionPromptObservation()
        persistCheckpoint(force = true)
        voiceAnnouncer.speak("开始跑步")
    }

    private fun continuePreviousRun() {
        if (_recoveryInProgress.value || hasActiveRunSession()) return
        val checkpoint = checkpointStore.load()
        if (checkpoint == null) {
            _recoveryError.value = "没有找到可以继续的跑步记录"
            return
        }
        if (!sessionController.dispatch(RunCommand.Start).accepted) {
            _recoveryError.value = "当前状态不能继续上次跑步"
            return
        }

        _recoveryInProgress.value = true
        _recoveryError.value = null
        checkpointGeneration++
        preRunHrJob?.cancel()
        sessionStartedAtEpochMillis = checkpoint.startedAtEpochMillis
        lastCheckpointElapsedSeconds = checkpoint.elapsedSeconds
        lastCheckpointWrittenElapsedSeconds = -1L
        lastLapElapsedSeconds = checkpoint.lastLapElapsedSeconds
        maxHeartRate = checkpoint.maxHeartRate
        announcementPolicy.restore(checkpoint.distanceMeters)
        heartRateAlertPolicy.reset()
        runTimer.restore(checkpoint.elapsedSeconds)
        if (prefs.getBoolean("metronome_active", false)) metronome.start()
        _runData.value = RunData(
            elapsedSeconds = checkpoint.elapsedSeconds,
            heartRate = heartRateMonitor.heartRate.value,
            maxHeartRate = checkpoint.maxHeartRate,
            distanceMeters = checkpoint.distanceMeters,
            paceSecondsPerKm = 0,
            isRunning = true,
            isPaused = false,
            hrDeviceConnected = heartRateMonitor.connected.value,
            metronomeActive = metronome.isPlaying.value,
            metronomeBpm = metronome.bpm.value
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("正在恢复上次跑步"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("正在恢复上次跑步"))
        }

        serviceScope.launch {
            val recovery = runCatching {
                withContext(Dispatchers.IO) {
                    gpsTracker.prepareRecovery(checkpoint.tracePath)
                }
            }.getOrElse { failure ->
                failRunRecovery("无法读取上次轨迹：${failure.message ?: "未知错误"}")
                return@launch
            }
            val restoredDistance = maxOf(checkpoint.distanceMeters, recovery.totalDistanceMeters)
            maxHeartRate = maxOf(checkpoint.maxHeartRate, recovery.maxHeartRate)
            announcementPolicy.restore(restoredDistance)
            _runData.update {
                it.copy(distanceMeters = restoredDistance, maxHeartRate = maxHeartRate)
            }

            val gpsRestore = gpsTracker.startRecovered(recovery, restoredDistance)
            if (gpsRestore.isFailure) {
                gpsTracker.closeTraceForRecovery()
                failRunRecovery("无法恢复定位：${gpsRestore.exceptionOrNull()?.message ?: "未知错误"}")
                return@launch
            }

            runTimer.start(serviceScope)
            motionDetector.start()
            if (!heartRateMonitor.connected.value) heartRateMonitor.connectSavedDevice()
            startCollecting()
            startTrackerEventObservation()
            startStationaryPromptObservation()
            startHeartRateConnectionPromptObservation()
            updateMediaSession(active = true, paused = false)
            updateNotification("已续跑 ${_runData.value.timeFormatted} · ${_runData.value.distanceFormatted}km")
            _recoveryInProgress.value = false
            persistCheckpoint(force = true)
            voiceAnnouncer.speak("已继续上次跑步")
        }
    }

    private fun failRunRecovery(message: String) {
        Log.w(TAG, message)
        checkpointGeneration++
        runTimer.reset()
        gpsTracker.stopUpdates()
        motionDetector.stop()
        sessionController.dispatch(RunCommand.BeginFinish)
        sessionController.dispatch(RunCommand.CompleteFinish)
        _runData.update { it.copy(isRunning = false, isPaused = false) }
        updateMediaSession(active = false, paused = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        startPreRunHrObservation()
        _recoveryInProgress.value = false
        _recoveryError.value = message
    }

    private fun discardRecoverableRun(): Boolean {
        val checkpoint = checkpointStore.load()
        if (checkpoint != null) {
            val discarded = runCatching {
                gpsTracker.discardRecoveredTrace(checkpoint.tracePath)
            }.onFailure {
                Log.w(TAG, "Unable to delete interrupted trace", it)
            }.getOrDefault(false)
            if (!discarded) {
                _recoveryError.value = "无法删除上次轨迹，请检查存储空间后重试"
                return false
            }
        }
        checkpointGeneration++
        checkpointStore.clear()
        prefs.edit().putBoolean("metronome_active", false).apply()
        _recoveryError.value = null
        return true
    }

    private fun interruptForRecovery() {
        if (!hasActiveRunSession()) return
        if (sessionController.state == RunSessionState.Running) {
            sessionController.dispatch(RunCommand.Pause)
            runTimer.pause()
        }
        collectJob?.cancel()
        stationaryPromptJob?.cancel()
        trackerEventJob?.cancel()
        heartRateConnectionPromptJob?.cancel()
        heartRateAlertPolicy.reset()
        _runData.update { it.copy(isPaused = true) }
        persistCheckpointSynchronously()
        gpsTracker.stopUpdates()
        motionDetector.stop()
        gpsTracker.closeTraceForRecovery()
        updateMediaSession(active = false, paused = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pauseRun() {
        if (!sessionController.dispatch(RunCommand.Pause).accepted) {
            Log.w(TAG, "Ignoring Pause in ${sessionController.state}")
            return
        }
        runTimer.pause()
        heartRateAlertPolicy.reset()
        gpsTracker.pause()
        motionDetector.stop()
        stationaryPromptJob?.cancel()
        _runData.update { it.copy(isPaused = true) }
        persistCheckpoint(force = true)
        updateMediaSession(active = true, paused = true)
        updateNotification("已暂停")
        voiceAnnouncer.speak("已暂停")
    }

    private fun resumeRun() {
        if (!sessionController.dispatch(RunCommand.Resume).accepted) {
            Log.w(TAG, "Ignoring Resume in ${sessionController.state}")
            return
        }
        runTimer.start(serviceScope)
        heartRateAlertPolicy.reset()
        val gpsResume = gpsTracker.resume()
        if (gpsResume.isFailure) {
            Log.e(TAG, "Unable to resume GPS tracking", gpsResume.exceptionOrNull())
            runTimer.pause()
            sessionController.dispatch(RunCommand.Pause)
            _runData.update { it.copy(isPaused = true) }
            voiceAnnouncer.speak("无法恢复定位，跑步仍保持暂停")
            return
        }
        motionDetector.start()
        startStationaryPromptObservation()
        _runData.update { it.copy(isPaused = false) }
        persistCheckpoint(force = true)
        updateMediaSession(active = true, paused = false)
        voiceAnnouncer.speak("继续跑步")
    }

    suspend fun stopRun(saveSession: Boolean = true): TraceSaveResult {
        if (!sessionController.dispatch(RunCommand.BeginFinish).accepted) {
            Log.w(TAG, "Ignoring Finish in ${sessionController.state}")
            return TraceSaveResult.Failed("当前状态不能结束跑步")
        }
        // Invalidate every checkpoint task captured by this session before closing the trace.
        // The mutex then waits for an already-running write and guarantees that clear() is last.
        checkpointGeneration++
        val data = _runData.value
        if (saveSession) {
            val km = data.distanceKm
            voiceAnnouncer.speak("跑步结束，总距离${String.format(Locale.getDefault(), "%.1f", km)}公里，用时${formatTimeForSpeech(data.elapsedSeconds)}")
        } else {
            voiceAnnouncer.speak("已放弃本次跑步记录")
        }

        collectJob?.cancel()
        heartRateAlertPolicy.reset()
        stationaryPromptJob?.cancel()
        trackerEventJob?.cancel()
        heartRateConnectionPromptJob?.cancel()
        runTimer.reset()
        gpsTracker.stopUpdates()
        motionDetector.stop()
        val saveResult = withContext(Dispatchers.IO) {
            checkpointMutex.withLock {
                gpsTracker.closeTrace(saveSession = saveSession).also {
                    checkpointStore.clear()
                }
            }
        }
        // Keep HR monitor connected — don't disconnect

        // Keep last data visible, just mark as stopped
        _runData.update { it.copy(isRunning = false, isPaused = false) }
        sessionController.dispatch(RunCommand.CompleteFinish)
        updateMediaSession(active = false, paused = false)
        startPreRunHrObservation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return saveResult
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = serviceScope.launch {
            val trackingState = combine(
                runTimer.elapsedSeconds,
                gpsTracker.distanceMeters,
                gpsTracker.paceSecondsPerKm,
                heartRateMonitor.heartRate,
                heartRateMonitor.connected
            ) { elapsed, distance, pace, hr, hrConn ->
                TrackingHrState(elapsed, distance, pace, hr, hrConn)
            }
            combine(
                trackingState,
                metronome.isPlaying,
                metronome.bpm
            ) { tracking, metroActive, metroBpm ->
                val elapsed = tracking.elapsed
                val distance = tracking.distance
                val pace = tracking.pace
                val hr = tracking.hr
                val hrConn = tracking.hrConnected
                val currentMaxHr = maxOf(maxHeartRate, hr)
                maxHeartRate = currentMaxHr
                RunData(
                    elapsedSeconds = elapsed,
                    heartRate = hr,
                    maxHeartRate = currentMaxHr,
                    distanceMeters = distance,
                    paceSecondsPerKm = pace,
                    isRunning = true,
                    isPaused = _runData.value.isPaused,
                    hrDeviceConnected = hrConn,
                    metronomeActive = metroActive,
                    metronomeBpm = metroBpm
                )
            }.collect { data ->
                _runData.value = data

                val shouldAlertHighHeartRate = if (data.hrDeviceConnected && !data.isPaused) {
                    heartRateAlertPolicy.shouldAlert(
                        heartRateBpm = data.heartRate,
                        nowMillis = SystemClock.elapsedRealtime()
                    )
                } else {
                    heartRateAlertPolicy.reset()
                    false
                }
                if (shouldAlertHighHeartRate) {
                    voiceAnnouncer.speakPriority(
                        "心率持续超过一百八十，请立即减速，注意身体状况"
                    )
                }

                announcementPolicy.eventsFor(data.distanceMeters, data.paceSecondsPerKm).forEach { event ->
                    when (event) {
                        is AnnouncementEvent.Kilometer -> voiceAnnouncer.announceKilometer(
                            km = event.kilometer,
                            elapsedSeconds = data.elapsedSeconds,
                            heartRate = data.heartRate,
                            paceSecondsPerKm = data.paceSecondsPerKm
                        )
                        is AnnouncementEvent.CurrentPace -> voiceAnnouncer.announceQuarterStats(
                            paceSecondsPerKm = event.paceSecondsPerKm,
                            heartRate = data.heartRate,
                        )
                    }
                }

                // Update notification every ~5 seconds
                if (data.elapsedSeconds % 5 == 0L) {
                    updateNotification("跑步中 ${data.timeFormatted} · ${data.distanceFormatted}km")
                }
                persistCheckpoint()
            }
        }
    }

    private data class TrackingHrState(
        val elapsed: Long,
        val distance: Float,
        val pace: Int,
        val hr: Int,
        val hrConnected: Boolean
    )

    private fun startStationaryPromptObservation() {
        stationaryPromptJob?.cancel()
        stationaryPromptJob = serviceScope.launch {
            var waitingForMovementResumePrompt = false
            gpsTracker.stationaryDetected
                .collectLatest { stationary ->
                    if (!stationary) {
                        val data = _runData.value
                        if (waitingForMovementResumePrompt && data.isRunning && !data.isPaused) {
                            waitingForMovementResumePrompt = false
                            voiceAnnouncer.speak("已恢复运动，继续计数")
                        }
                        return@collectLatest
                    }

                    delay(STATIONARY_PROMPT_DELAY_MS)

                    val data = _runData.value
                    if (!gpsTracker.stationaryDetected.value || !data.isRunning || data.isPaused) {
                        return@collectLatest
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStationaryPromptAt >= STATIONARY_PROMPT_MIN_INTERVAL_MS) {
                        lastStationaryPromptAt = now
                        waitingForMovementResumePrompt = true
                        voiceAnnouncer.speak("检测为静止")
                    }
                }
        }
    }

    private fun startTrackerEventObservation() {
        trackerEventJob?.cancel()
        trackerEventJob = serviceScope.launch {
            launch {
                gpsTracker.trackingAlerts.collect { alert ->
                    val data = _runData.value
                    if (!data.isRunning || data.isPaused) return@collect
                    val prompt = when (alert) {
                        TrackingAlert.HighSpeedStarted,
                        TrackingAlert.LocationJumpStarted -> "GPS信号不稳定，正在校正距离"
                        TrackingAlert.Recovered -> "GPS信号已稳定，继续记录距离"
                    }
                    voiceAnnouncer.speakPriority(prompt)
                }
            }
            launch {
                gpsTracker.lapCompletions.collect { lap ->
                    val data = _runData.value
                    if (!data.isRunning || data.isPaused) return@collect
                    val elapsed = runTimer.elapsedSeconds.value
                    val lapElapsed = (elapsed - lastLapElapsedSeconds).coerceAtLeast(0L)
                    lastLapElapsedSeconds = elapsed
                    persistCheckpoint(force = true)
                    val averagePace = if (lap.lapDistanceMeters > 0f && lapElapsed > 0L) {
                        (lapElapsed * 1_000f / lap.lapDistanceMeters).toInt()
                    } else {
                        0
                    }
                    voiceAnnouncer.announceLap(
                        lapNumber = lap.lapNumber,
                        distanceMeters = lap.lapDistanceMeters,
                        averagePaceSecondsPerKm = averagePace
                    )
                }
            }
        }
    }

    private fun startHeartRateConnectionPromptObservation() {
        heartRateConnectionPromptJob?.cancel()
        heartRateConnectionPromptJob = serviceScope.launch {
            var hasConnectedDuringSession = heartRateMonitor.connected.value
            var disconnectAnnounced = false
            heartRateMonitor.connected.collectLatest { connected ->
                if (connected) {
                    hasConnectedDuringSession = true
                    if (disconnectAnnounced && _runData.value.isRunning) {
                        disconnectAnnounced = false
                        voiceAnnouncer.speakPriority("心率带已恢复")
                    }
                    return@collectLatest
                }
                if (!hasConnectedDuringSession) return@collectLatest

                delay(HEART_RATE_DISCONNECT_PROMPT_DELAY_MS)
                if (!heartRateMonitor.connected.value && _runData.value.isRunning) {
                    disconnectAnnounced = true
                    voiceAnnouncer.speakPriority("心率带已断开，正在重连")
                }
            }
        }
    }

    private fun startPreRunHrObservation() {
        preRunHrJob?.cancel()
        preRunHrJob = serviceScope.launch {
            combine(
                heartRateMonitor.heartRate,
                heartRateMonitor.connected,
                metronome.isPlaying,
                metronome.bpm
            ) { hr, hrConn, metroActive, metroBpm ->
                MetroHrState(hr, hrConn, metroActive, metroBpm)
            }.collect { state ->
                _runData.update {
                    it.copy(
                        heartRate = state.hr,
                        maxHeartRate = it.maxHeartRate,
                        hrDeviceConnected = state.hrConn,
                        metronomeActive = state.metroActive,
                        metronomeBpm = state.metroBpm
                    )
                }
            }
        }
    }

    private data class MetroHrState(
        val hr: Int, val hrConn: Boolean,
        val metroActive: Boolean, val metroBpm: Int
    )

    private fun formatTimeForSpeech(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildString {
            if (h > 0) append("${h}小时")
            if (m > 0) append("${m}分")
            append("${s}秒")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "跑步服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "跑步时前台服务通知"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildMediaSession(): MediaSession {
        val mediaButtonReceiver = PendingIntent.getBroadcast(
            this,
            0,
            Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName(this@RunningService, MediaButtonIntentReceiver::class.java)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return MediaSession(this, "RunVoiceSession").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setMediaButtonReceiver(mediaButtonReceiver)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setMediaButtonBroadcastReceiver(ComponentName(this@RunningService, MediaButtonIntentReceiver::class.java))
            }
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = extractMediaKeyEvent(mediaButtonIntent) ?: return false
                    return handleMediaButtonEvent(event)
                }

                override fun onPlay() {
                    handleTransportControl()
                }

                override fun onPause() {
                    handleTransportControl()
                }
            })
            isActive = false
        }
    }

    private fun updateMediaSession(active: Boolean, paused: Boolean) {
        val session = mediaSession ?: return
        session.isActive = active
        val state = when {
            !active -> PlaybackState.STATE_STOPPED
            paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun announceCurrentStats(
        leadingText: String? = null,
        recoveryStatus: Boolean = false,
    ) {
        val data = _runData.value
        if (!hasActiveRunSession()) return

        val parts = buildList {
            leadingText?.let(::add)
            add("现在时间${formatCurrentTimeForSpeech()}")
            add("当前已跑${data.distanceFormatted}公里")
            add("用时${formatTimeForSpeech(data.elapsedSeconds)}")
            if (data.heartRate > 0) {
                add("心率：${VoiceStatsText.heartRate(data.heartRate)}")
            }
            if (data.maxHeartRate > 0) {
                add("最大心率：${VoiceStatsText.heartRate(data.maxHeartRate)}")
            }
            val averagePaceSecondsPerKm = data.averagePaceSecondsPerKm
            if (averagePaceSecondsPerKm > 0) {
                add("平均配速：${VoiceStatsText.pace(averagePaceSecondsPerKm)}")
            }
            if (data.isPaused) {
                add("当前已暂停")
            }
        }

        val text = parts.joinToString("，")
        if (recoveryStatus) voiceAnnouncer.speakRecoveryStatus(text) else voiceAnnouncer.speak(text)
    }

    private fun announceVoiceRecovery() {
        val data = _runData.value
        if (!hasActiveRunSession()) return
        Log.w(TAG, "TTS recovered during an active run at ${data.distanceMeters}m")
        updateNotification(
            "语音已恢复 · ${if (data.isPaused) "已暂停" else "跑步中"} " +
                "${data.timeFormatted} · ${data.distanceFormatted}km"
        )
        announceCurrentStats(
            leadingText = "语音播报已自动恢复",
            recoveryStatus = true,
        )
    }

    private fun formatCurrentTimeForSpeech(): String {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        return "${hour}点${minute}分"
    }

    private fun extractMediaKeyEvent(intent: Intent): KeyEvent? {
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
    }

    private fun dispatchMediaButtonIntent(intent: Intent) {
        val event = extractMediaKeyEvent(intent)
        if (event == null) {
            Log.w(TAG, "Ignoring media button intent without KeyEvent")
            return
        }

        val handled = mediaSession?.controller?.dispatchMediaButtonEvent(event) == true
        if (!handled) {
            Log.w(TAG, "Media button was not handled by session controller")
        }
    }

    private fun handleMediaButtonEvent(event: KeyEvent): Boolean {
        if (!_runData.value.isRunning) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action != KeyEvent.ACTION_UP || event.repeatCount != 0) {
                    return true
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastMediaButtonHandledAt < 400L) return true
                lastMediaButtonHandledAt = now
                announceCurrentStats()
                return true
            }
        }
        return false
    }

    private fun handleTransportControl() {
        if (!_runData.value.isRunning) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMediaButtonHandledAt < 400L) return
        lastMediaButtonHandledAt = now
        announceCurrentStats()
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RunVoice")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_running)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    fun toggleMetronome() {
        metronome.toggle()
        prefs.edit().putBoolean("metronome_active", metronome.isPlaying.value).apply()
    }

    fun setMetronomeBpm(bpm: Int) {
        metronome.setBpm(bpm)
        prefs.edit().putInt("metronome_bpm", metronome.bpm.value).apply()
    }

    fun startHeartRateScan() = heartRateMonitor.startScan()

    fun stopHeartRateScan() = heartRateMonitor.stopScan()

    fun savedHeartRateDeviceAddress(): String? = heartRateMonitor.getSavedDeviceAddress()

    fun selectHeartRateDevice(address: String) {
        heartRateMonitor.saveDevice(address)
        heartRateMonitor.connectToDevice(address)
    }

    fun disconnectHeartRateDevice() = heartRateMonitor.clearSavedDevice()

    fun currentTracePathForSnapshot(): String? {
        gpsTracker.flushTrace()
        return gpsTracker.currentTracePath()
    }

    private fun persistCheckpoint(force: Boolean = false) {
        if (!hasActiveRunSession()) return
        val data = _runData.value
        if (!force &&
            data.elapsedSeconds - lastCheckpointElapsedSeconds < CHECKPOINT_INTERVAL_SECONDS
        ) {
            return
        }
        val checkpoint = buildCheckpoint(data) ?: return
        val generation = checkpointGeneration
        lastCheckpointElapsedSeconds = data.elapsedSeconds
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                checkpointMutex.withLock {
                    if (generation != checkpointGeneration || !hasActiveRunSession()) {
                        return@withLock
                    }
                    if (checkpoint.elapsedSeconds < lastCheckpointWrittenElapsedSeconds) return@withLock
                    check(gpsTracker.flushTrace()) { "GPS trace flush failed" }
                    checkpointStore.save(checkpoint)
                    lastCheckpointWrittenElapsedSeconds = checkpoint.elapsedSeconds
                }
            }.onFailure { Log.w(TAG, "Unable to persist recovery checkpoint", it) }
        }
    }

    private fun persistCheckpointSynchronously() {
        if (!hasActiveRunSession()) return
        val checkpoint = buildCheckpoint(_runData.value) ?: return
        runCatching {
            runBlocking {
                checkpointMutex.withLock {
                    check(gpsTracker.flushTrace()) { "GPS trace flush failed" }
                    checkpointStore.save(checkpoint)
                    lastCheckpointWrittenElapsedSeconds = checkpoint.elapsedSeconds
                }
            }
        }.onFailure { Log.w(TAG, "Unable to persist final recovery checkpoint", it) }
    }

    private fun buildCheckpoint(data: RunData): RunCheckpoint? {
        val tracePath = gpsTracker.currentTracePath() ?: return null
        val startedAt = sessionStartedAtEpochMillis.takeIf { it > 0L } ?: return null
        return RunCheckpoint(
            tracePath = tracePath,
            startedAtEpochMillis = startedAt,
            updatedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(startedAt),
            elapsedSeconds = data.elapsedSeconds,
            distanceMeters = data.distanceMeters.coerceAtLeast(0f),
            maxHeartRate = data.maxHeartRate.coerceAtLeast(0),
            lastLapElapsedSeconds = lastLapElapsedSeconds.coerceIn(0L, data.elapsedSeconds),
            wasPaused = data.isPaused
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (hasActiveRunSession()) {
            Log.w(TAG, "Running task removed; proactively reconnecting TTS without stopping tracking")
            voiceAnnouncer.reconnectAfterExternalCleanup()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun hasActiveRunSession(): Boolean =
        sessionController.state == RunSessionState.Running ||
            sessionController.state == RunSessionState.Paused

    override fun onDestroy() {
        val preserveInterruptedRun = hasActiveRunSession()
        if (preserveInterruptedRun) {
            persistCheckpointSynchronously()
            gpsTracker.stopUpdates()
            motionDetector.stop()
            gpsTracker.closeTraceForRecovery()
        }
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        voiceAnnouncer.shutdown()
        metronome.release()
        if (!preserveInterruptedRun) gpsTracker.stop(saveSession = false)
        heartRateMonitor.disconnect()
        if (!preserveInterruptedRun) {
            // A completed session must not restart the metronome with a later idle service.
            prefs.edit().putBoolean("metronome_active", false).apply()
        }
        super.onDestroy()
    }
}
