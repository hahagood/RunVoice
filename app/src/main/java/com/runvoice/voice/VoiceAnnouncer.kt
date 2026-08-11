package com.runvoice.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class VoiceAnnouncer(
    context: Context,
    private val onRecovered: () -> Unit = {},
) {
    companion object {
        private const val TAG = "RunVoiceTTS"
        private const val PREWARM_DELAY_MS = 85L
        private const val PREWARM_TONE_MS = 45
        private const val INITIALIZATION_TIMEOUT_MS = 10_000L
        private const val MIN_UTTERANCE_TIMEOUT_MS = 20_000L
        private const val MAX_UTTERANCE_TIMEOUT_MS = 45_000L
        private const val UTTERANCE_TIMEOUT_PER_CHARACTER_MS = 350L
        private const val UTTERANCE_TIMEOUT_BASE_MS = 15_000L
        private const val MAX_REQUEST_RETRIES = 1
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requests = SpeechRequestQueue()
    private val recoveryEpisode = SpeechRecoveryEpisode()
    private val utteranceSequence = AtomicLong()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val speechAudioAttributes = AudioAttributes.Builder()
        // Accessibility stream was silent on some OEM builds.
        // Route spoken workout prompts through the normal media path instead.
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
        .build()
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAudioAttributes)
        .setAcceptsDelayedFocusGain(false)
        .build()
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 35)
    private val speakParams = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
    }

    private var tts: TextToSpeech? = null
    private var engineGeneration = 0
    private var ready = false
    private var initializing = false
    private var closed = false
    private var inFlight: SpeechRequest? = null
    private var pendingSpeakRunnable: Runnable? = null
    private var initializationWatchdog: Runnable? = null
    private var utteranceWatchdog: Runnable? = null
    private var engineRecoveryRunnable: Runnable? = null

    init {
        initializeTts("initial")
    }

    /**
     * Announce running stats.
     * @param km completed kilometers (integer)
     * @param elapsedSeconds total elapsed time
     * @param heartRate current heart rate
     * @param paceSecondsPerKm current pace in seconds per km
     */
    fun announceKilometer(km: Int, elapsedSeconds: Long, heartRate: Int, paceSecondsPerKm: Int) {
        val timeText = formatElapsedTimeForSpeech(elapsedSeconds)
        val parts = buildList {
            add("已跑${km}公里")
            add("用时${timeText}")
            if (heartRate > 0) add("心率：${VoiceStatsText.heartRate(heartRate)}")
            if (paceSecondsPerKm > 0) add("配速：${VoiceStatsText.pace(paceSecondsPerKm)}")
        }
        enqueueOrSpeak(
            text = parts.joinToString("，"),
            utteranceId = uniqueUtteranceId("km_$km"),
        )
    }

    fun announceQuarterStats(paceSecondsPerKm: Int, heartRate: Int) {
        if (paceSecondsPerKm <= 0) return
        val parts = buildList {
            add("配速：${VoiceStatsText.pace(paceSecondsPerKm)}")
            if (heartRate > 0) add("心率：${VoiceStatsText.heartRate(heartRate)}")
        }
        enqueueOrSpeak(
            parts.joinToString("，"),
            uniqueUtteranceId("quarter_stats"),
        )
    }

    fun announceLap(lapNumber: Int, distanceMeters: Float, averagePaceSecondsPerKm: Int) {
        if (lapNumber <= 0 || distanceMeters <= 0f) return
        val lapText = ordinalForSpeech(lapNumber)
        val nextLapText = ordinalForSpeech(lapNumber + 1)
        val distanceText = if (distanceMeters < 1_000f) {
            "${distanceMeters.roundToInt()}米"
        } else {
            "${String.format(Locale.CHINA, "%.2f", distanceMeters / 1_000f)}公里"
        }
        val paceText = if (averagePaceSecondsPerKm > 0) {
            "平均配速：${VoiceStatsText.pace(averagePaceSecondsPerKm)}，"
        } else {
            ""
        }
        enqueueOrSpeak(
            "${lapText}段完成，${paceText}距离${distanceText}。${nextLapText}段开始",
            uniqueUtteranceId("lap_$lapNumber"),
        )
    }

    fun speak(text: String) {
        enqueueOrSpeak(text, uniqueUtteranceId("custom"))
    }

    /** Puts safety and tracking-health prompts ahead of queued routine announcements. */
    fun speakPriority(text: String) {
        enqueueOrSpeak(text, uniqueUtteranceId("priority"), priority = true)
    }

    /** A fresh run snapshot emitted after recovery; success must not enqueue another snapshot. */
    fun speakRecoveryStatus(text: String) {
        enqueueOrSpeak(
            text = text,
            utteranceId = uniqueUtteranceId("recovery"),
            recoveryStatus = true,
        )
    }

    /** Rebind after the running task is removed, before the next distance announcement is due. */
    fun reconnectAfterExternalCleanup() {
        runOnMain {
            if (!closed) beginEngineRecovery("running task removed", consumeRetry = false)
        }
    }

    fun shutdown() {
        runOnMain(::shutdownOnMain)
    }

    private fun initializeTts(reason: String) {
        if (closed) return
        engineRecoveryRunnable = null
        cancelInitializationWatchdog()
        initializing = true
        ready = false
        val generation = ++engineGeneration
        Log.i(TAG, "Initializing TTS generation=$generation reason=$reason")

        val newEngine = runCatching {
            TextToSpeech(appContext) { status ->
                // Some constructor failure paths can invoke OnInit before the assignment to [tts]
                // completes. Always post so the generation owns a fully assigned engine object.
                mainHandler.post { handleTtsInitialized(generation, status) }
            }
        }.getOrElse { error ->
            Log.w(TAG, "Unable to construct TTS generation=$generation", error)
            beginEngineRecovery("constructor failure")
            return
        }
        tts = newEngine

        initializationWatchdog = Runnable {
            initializationWatchdog = null
            if (!closed && generation == engineGeneration && !ready) {
                Log.w(TAG, "TTS initialization timed out generation=$generation")
                beginEngineRecovery("initialization timeout")
            }
        }.also { mainHandler.postDelayed(it, INITIALIZATION_TIMEOUT_MS) }
    }

    private fun handleTtsInitialized(generation: Int, status: Int) {
        if (closed || generation != engineGeneration) return
        cancelInitializationWatchdog()
        initializing = false
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed generation=$generation status=$status")
            beginEngineRecovery("initialization status=$status")
            return
        }

        val engine = tts ?: run {
            beginEngineRecovery("initialized without engine")
            return
        }
        val listenerResult = runCatching {
            engine.setOnUtteranceProgressListener(progressListener(generation))
        }.getOrElse {
            Log.w(TAG, "Unable to install TTS progress listener", it)
            TextToSpeech.ERROR
        }
        val attributesResult = runCatching {
            engine.setAudioAttributes(speechAudioAttributes)
        }.getOrElse {
            Log.w(TAG, "Unable to configure TTS audio attributes", it)
            TextToSpeech.ERROR
        }
        if (listenerResult != TextToSpeech.SUCCESS || attributesResult != TextToSpeech.SUCCESS) {
            beginEngineRecovery(
                "configuration failure listener=$listenerResult attributes=$attributesResult"
            )
            return
        }

        val localeResult = preferredLocales().firstNotNullOfOrNull { locale ->
            val result = runCatching { engine.setLanguage(locale) }.getOrElse {
                Log.w(TAG, "Unable to set TTS language to $locale", it)
                TextToSpeech.ERROR
            }
            if (result >= TextToSpeech.LANG_AVAILABLE) {
                Log.i(TAG, "TTS language set to $locale")
                result
            } else {
                null
            }
        }
        if (localeResult == null) {
            Log.w(TAG, "Chinese TTS locale unavailable, falling back to engine default locale")
        }

        val awaitingRecoveryConfirmation = recoveryEpisode.awaitingUtteranceConfirmation
        ready = true
        recoveryEpisode.onEngineReady()
        Log.i(
            TAG,
            "TTS ready generation=$generation " +
                "awaitingRecoveryConfirmation=$awaitingRecoveryConfirmation",
        )
        speakNextIfIdle()
    }

    private fun progressListener(generation: Int) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.d(TAG, "TTS utterance started generation=$generation id=$utteranceId")
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post { completeUtterance(generation, utteranceId) }
        }

        @Deprecated("The platform may call this overload on older engines")
        override fun onError(utteranceId: String?) {
            mainHandler.post {
                failUtterance(generation, utteranceId, "utterance error")
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post {
                failUtterance(generation, utteranceId, "utterance errorCode=$errorCode")
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            mainHandler.post {
                failUtterance(generation, utteranceId, "utterance stopped interrupted=$interrupted")
            }
        }
    }

    private fun preferredLocales(): List<Locale> = listOf(
        Locale.SIMPLIFIED_CHINESE,
        Locale.CHINA,
        Locale("zh", "CN"),
        Locale.CHINESE,
    )

    private fun enqueueOrSpeak(
        text: String,
        utteranceId: String,
        priority: Boolean = false,
        recoveryStatus: Boolean = false,
    ) {
        runOnMain {
            if (closed) return@runOnMain
            val queued = requests.addLast(
                SpeechRequest(
                    text = text,
                    utteranceId = utteranceId,
                    priority = priority,
                    recoveryStatus = recoveryStatus,
                )
            )
            if (!queued) {
                Log.w(TAG, "Dropping new priority TTS request because its queue is full: $utteranceId")
                return@runOnMain
            }
            if (!ready) {
                Log.d(TAG, "Queueing TTS while unavailable: $utteranceId")
                if (!initializing && engineRecoveryRunnable == null) {
                    scheduleEngineInitialization("queued while unavailable")
                }
                return@runOnMain
            }
            speakNextIfIdle()
        }
    }

    private fun speakNextIfIdle() {
        if (!ready || inFlight != null || requests.isEmpty || closed) return
        val engine = tts ?: run {
            beginEngineRecovery("ready without engine")
            return
        }
        val request = requests.removeNext() ?: return
        inFlight = request
        val generation = engineGeneration

        val speakAction = Runnable {
            pendingSpeakRunnable = null
            if (closed || generation != engineGeneration || inFlight?.utteranceId != request.utteranceId) {
                return@Runnable
            }
            val result = runCatching {
                engine.speak(
                    request.text,
                    TextToSpeech.QUEUE_FLUSH,
                    speakParams,
                    request.utteranceId,
                )
            }.getOrElse {
                Log.w(TAG, "TTS speak threw for ${request.utteranceId}", it)
                TextToSpeech.ERROR
            }
            if (result != TextToSpeech.SUCCESS) {
                failUtterance(generation, request.utteranceId, "speak result=$result")
            } else {
                startUtteranceWatchdog(generation, request)
            }
        }

        requestSpeechAudioFocus(request.utteranceId)
        runCatching {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, PREWARM_TONE_MS)
        }.onFailure {
            Log.w(TAG, "Unable to play TTS prewarm tone", it)
        }
        pendingSpeakRunnable = speakAction
        mainHandler.postDelayed(speakAction, PREWARM_DELAY_MS)
    }

    private fun startUtteranceWatchdog(generation: Int, request: SpeechRequest) {
        cancelUtteranceWatchdog()
        val timeoutMillis = (
            UTTERANCE_TIMEOUT_BASE_MS + request.text.length * UTTERANCE_TIMEOUT_PER_CHARACTER_MS
        ).coerceIn(MIN_UTTERANCE_TIMEOUT_MS, MAX_UTTERANCE_TIMEOUT_MS)
        utteranceWatchdog = Runnable {
            utteranceWatchdog = null
            if (!closed && generation == engineGeneration &&
                inFlight?.utteranceId == request.utteranceId
            ) {
                Log.w(TAG, "TTS utterance timed out after ${timeoutMillis}ms: ${request.utteranceId}")
                failUtterance(generation, request.utteranceId, "utterance timeout")
            }
        }.also { mainHandler.postDelayed(it, timeoutMillis) }
    }

    private fun completeUtterance(generation: Int, utteranceId: String?) {
        if (closed || generation != engineGeneration) return
        val active = inFlight ?: return
        if (utteranceId != active.utteranceId) return
        Log.d(TAG, "TTS utterance done generation=$generation id=${active.utteranceId}")
        cancelUtteranceWatchdog()
        inFlight = null
        abandonSpeechAudioFocus()
        val recoveryCompletion = recoveryEpisode.onUtteranceCompleted(active.recoveryStatus)
        if (recoveryCompletion.outageEnded) {
            // Initialization only proves that binding succeeded. A real onDone is the first proof
            // that this outage has ended; resetting backoff or announcing recovery any earlier can
            // create a one-second rebuild loop when an engine initializes but cannot synthesize.
            if (recoveryCompletion.requestFreshSnapshot) {
                requests.clearRoutine()
                runCatching(onRecovered).onFailure {
                    Log.w(TAG, "TTS recovery callback failed", it)
                }
            }
        }
        speakNextIfIdle()
    }

    private fun failUtterance(generation: Int, utteranceId: String?, reason: String) {
        if (closed || generation != engineGeneration) return
        val active = inFlight ?: return
        if (utteranceId != active.utteranceId) return
        Log.w(TAG, "TTS failure generation=$generation id=${active.utteranceId}: $reason")
        beginEngineRecovery(reason)
    }

    private fun beginEngineRecovery(reason: String, consumeRetry: Boolean = true) {
        if (closed) return
        pendingSpeakRunnable?.let(mainHandler::removeCallbacks)
        pendingSpeakRunnable = null
        cancelInitializationWatchdog()
        cancelUtteranceWatchdog()

        inFlight?.let { active ->
            if (!consumeRetry) {
                requests.addFirst(active)
            } else if (active.retryCount < MAX_REQUEST_RETRIES) {
                requests.addFirst(active.copy(retryCount = active.retryCount + 1))
            } else {
                Log.w(TAG, "Dropping TTS request after retry limit: ${active.utteranceId}")
            }
        }
        inFlight = null
        recoveryEpisode.onEngineFailure()
        ready = false
        initializing = false
        engineGeneration++ // Invalidate callbacks and delayed work owned by the old engine.
        abandonSpeechAudioFocus()
        safelyShutdownEngine()
        scheduleEngineInitialization(reason)
    }

    private fun scheduleEngineInitialization(reason: String) {
        if (closed || engineRecoveryRunnable != null || initializing) return
        val delayMillis = recoveryEpisode.nextDelayMillis()
        Log.w(
            TAG,
            "Scheduling TTS recovery in ${delayMillis}ms reason=$reason " +
                "priority=${requests.prioritySize} routine=${requests.routineSize}",
        )
        engineRecoveryRunnable = Runnable {
            engineRecoveryRunnable = null
            initializeTts("recovery after $reason")
        }.also { mainHandler.postDelayed(it, delayMillis) }
    }

    private fun shutdownOnMain() {
        if (closed) return
        closed = true
        ready = false
        initializing = false
        engineGeneration++
        pendingSpeakRunnable?.let(mainHandler::removeCallbacks)
        pendingSpeakRunnable = null
        engineRecoveryRunnable?.let(mainHandler::removeCallbacks)
        engineRecoveryRunnable = null
        cancelInitializationWatchdog()
        cancelUtteranceWatchdog()
        inFlight = null
        requests.clear()
        abandonSpeechAudioFocus()
        safelyShutdownEngine()
        runCatching(toneGenerator::release)
    }

    private fun safelyShutdownEngine() {
        val engine = tts ?: return
        tts = null
        runCatching(engine::stop).onFailure { Log.w(TAG, "Unable to stop old TTS engine", it) }
        runCatching(engine::shutdown).onFailure { Log.w(TAG, "Unable to shutdown old TTS engine", it) }
    }

    private fun cancelInitializationWatchdog() {
        initializationWatchdog?.let(mainHandler::removeCallbacks)
        initializationWatchdog = null
    }

    private fun cancelUtteranceWatchdog() {
        utteranceWatchdog?.let(mainHandler::removeCallbacks)
        utteranceWatchdog = null
    }

    private fun requestSpeechAudioFocus(utteranceId: String) {
        val focusResult = runCatching {
            audioManager.requestAudioFocus(audioFocusRequest)
        }.getOrElse {
            Log.w(TAG, "Audio focus request threw for $utteranceId", it)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus request failed result=$focusResult for $utteranceId")
        }
    }

    private fun abandonSpeechAudioFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
            .onFailure { Log.w(TAG, "Unable to abandon TTS audio focus", it) }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private fun uniqueUtteranceId(prefix: String): String =
        "${prefix}_${System.currentTimeMillis()}_${utteranceSequence.incrementAndGet()}"

    private fun formatElapsedTimeForSpeech(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return buildString {
            if (hours > 0) append("${hours}小时")
            if (minutes > 0) append("${minutes}分")
            append("${secs}秒")
        }
    }

    private fun ordinalForSpeech(number: Int): String {
        val numeral = when (number) {
            1 -> "一"
            2 -> "二"
            3 -> "三"
            4 -> "四"
            5 -> "五"
            6 -> "六"
            7 -> "七"
            8 -> "八"
            9 -> "九"
            10 -> "十"
            else -> number.toString()
        }
        return "第$numeral"
    }
}
