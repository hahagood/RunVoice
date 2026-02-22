package com.runvoice.service

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.runvoice.MainActivity
import com.runvoice.R
import com.runvoice.model.RunData
import com.runvoice.tracker.GpsTracker
import com.runvoice.tracker.HeartRateMonitor
import com.runvoice.tracker.MotionDetector
import com.runvoice.tracker.RunTimer
import com.runvoice.voice.Metronome
import com.runvoice.voice.VoiceAnnouncer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RunningService : Service() {

    companion object {
        const val CHANNEL_ID = "running_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.runvoice.START"
        const val ACTION_PAUSE = "com.runvoice.PAUSE"
        const val ACTION_RESUME = "com.runvoice.RESUME"
        const val ACTION_STOP = "com.runvoice.STOP"
        const val ACTION_TEST_ANNOUNCE = "com.runvoice.TEST_ANNOUNCE"
    }

    inner class RunBinder : Binder() {
        val service: RunningService get() = this@RunningService
    }

    private val binder = RunBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: SharedPreferences

    lateinit var gpsTracker: GpsTracker
    lateinit var heartRateMonitor: HeartRateMonitor
    lateinit var runTimer: RunTimer
    lateinit var voiceAnnouncer: VoiceAnnouncer
    lateinit var metronome: Metronome
    lateinit var motionDetector: MotionDetector

    private val _runData = MutableStateFlow(RunData())
    val runData: StateFlow<RunData> = _runData.asStateFlow()

    private var collectJob: Job? = null
    private var preRunHrJob: Job? = null
    private var lastKmAnnounced = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prefs = getSharedPreferences("runvoice", MODE_PRIVATE)
        motionDetector = MotionDetector(this)
        gpsTracker = GpsTracker(this, motionDetector)
        heartRateMonitor = HeartRateMonitor(this)
        runTimer = RunTimer()
        voiceAnnouncer = VoiceAnnouncer(this)
        metronome = Metronome()
        // Restore saved metronome BPM and auto-start if was active
        metronome.setBpm(prefs.getInt("metronome_bpm", 180))
        if (prefs.getBoolean("metronome_active", false)) {
            metronome.start(serviceScope)
        }
        // Auto-connect saved HR device on service creation
        heartRateMonitor.connectSavedDevice()
        startPreRunHrObservation()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRun()
            ACTION_PAUSE -> pauseRun()
            ACTION_RESUME -> resumeRun()
            ACTION_STOP -> stopRun()
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
        return START_STICKY
    }

    private fun startRun() {
        preRunHrJob?.cancel()
        lastKmAnnounced = 0
        _runData.value = RunData(isRunning = true, hrDeviceConnected = heartRateMonitor.connected.value)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification("跑步中 00:00"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("跑步中 00:00"))
        }

        runTimer.start(serviceScope)
        gpsTracker.start()
        motionDetector.start()
        // Only connect if not already connected
        if (!heartRateMonitor.connected.value) {
            heartRateMonitor.connectSavedDevice()
        }

        startCollecting()
        voiceAnnouncer.speak("开始跑步")
    }

    private fun pauseRun() {
        runTimer.pause()
        gpsTracker.pause()
        motionDetector.stop()
        _runData.update { it.copy(isPaused = true) }
        updateNotification("已暂停")
        voiceAnnouncer.speak("已暂停")
    }

    private fun resumeRun() {
        runTimer.start(serviceScope)
        gpsTracker.resume()
        motionDetector.start()
        _runData.update { it.copy(isPaused = false) }
        voiceAnnouncer.speak("继续跑步")
    }

    fun stopRun() {
        val data = _runData.value
        val km = data.distanceKm
        voiceAnnouncer.speak("跑步结束，总距离${String.format("%.1f", km)}公里，用时${formatTimeForSpeech(data.elapsedSeconds)}")

        collectJob?.cancel()
        runTimer.reset()
        gpsTracker.stop()
        motionDetector.stop()
        // Keep HR monitor connected — don't disconnect

        // Keep last data visible, just mark as stopped
        _runData.update { it.copy(isRunning = false, isPaused = false) }
        startPreRunHrObservation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = serviceScope.launch {
            combine(
                runTimer.elapsedSeconds,
                gpsTracker.distanceMeters,
                gpsTracker.paceSecondsPerKm,
                heartRateMonitor.heartRate,
                heartRateMonitor.connected
            ) { elapsed, distance, pace, hr, hrConn ->
                RunData(
                    elapsedSeconds = elapsed,
                    heartRate = hr,
                    distanceMeters = distance,
                    paceSecondsPerKm = pace,
                    isRunning = true,
                    isPaused = _runData.value.isPaused,
                    hrDeviceConnected = hrConn,
                    lastKmAnnounced = lastKmAnnounced,
                    metronomeActive = metronome.isPlaying.value,
                    metronomeBpm = metronome.bpm.value
                )
            }.collect { data ->
                _runData.value = data

                // Check if we crossed a new kilometer
                val currentKm = (data.distanceMeters / 1000).toInt()
                if (currentKm > lastKmAnnounced && currentKm > 0) {
                    lastKmAnnounced = currentKm
                    voiceAnnouncer.announceKilometer(
                        km = currentKm,
                        elapsedSeconds = data.elapsedSeconds,
                        heartRate = data.heartRate,
                        paceSecondsPerKm = data.paceSecondsPerKm
                    )
                }

                // Update notification every ~5 seconds
                if (data.elapsedSeconds % 5 == 0L) {
                    updateNotification("跑步中 ${data.timeFormatted} · ${data.distanceFormatted}km")
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
        metronome.toggle(serviceScope)
        prefs.edit().putBoolean("metronome_active", metronome.isPlaying.value).apply()
    }

    fun setMetronomeBpm(bpm: Int) {
        metronome.setBpm(bpm)
        prefs.edit().putInt("metronome_bpm", metronome.bpm.value).apply()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        voiceAnnouncer.shutdown()
        metronome.release()
        gpsTracker.stop()
        heartRateMonitor.disconnect()
        super.onDestroy()
    }
}
