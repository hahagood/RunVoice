package com.runvoice.voice

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class VoiceAnnouncer(context: Context) {

    companion object {
        private const val TAG = "RunVoiceTTS"
        private const val PREWARM_DELAY_MS = 85L
        private const val PREWARM_TONE_MS = 45
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingUtterances = ArrayDeque<Pair<String, String>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSpeakRunnable: Runnable? = null
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 35)
    private val speakParams = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
            } else {
                Log.w(TAG, "TTS init failed with status=$status")
            }
        }
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

        val paceText = if (paceSecondsPerKm > 0) "配速${formatPaceForSpeech(paceSecondsPerKm)}" else ""

        val hrText = if (heartRate > 0) "，当前心率${heartRate}" else ""
        val text = "已跑${km}公里，用时${timeText}${hrText}，${paceText}"

        enqueueOrSpeak(text, "km_$km")
    }

    fun announceCurrentPace(paceSecondsPerKm: Int) {
        if (paceSecondsPerKm <= 0) return
        enqueueOrSpeak("当前配速${formatPaceForSpeech(paceSecondsPerKm)}每公里", "pace_${System.currentTimeMillis()}")
    }

    fun speak(text: String) {
        enqueueOrSpeak(text, "custom_${System.currentTimeMillis()}")
    }

    fun shutdown() {
        pendingSpeakRunnable?.let(mainHandler::removeCallbacks)
        pendingSpeakRunnable = null
        toneGenerator.release()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pendingUtterances.clear()
    }

    private fun configureTts() {
        val engine = tts ?: return

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                // Accessibility stream was silent on some OEM builds.
                // Route spoken workout prompts through the normal media path instead.
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()
        )

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS utterance failed: $utteranceId")
            }
        })

        val localeResult = preferredLocales()
            .firstNotNullOfOrNull { locale ->
                val result = engine.setLanguage(locale)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.i(TAG, "TTS language set to $locale")
                    result
                } else {
                    null
                }
            }

        if (localeResult == null) {
            Log.w(TAG, "Chinese TTS locale unavailable, falling back to engine default locale")
        }

        ready = true
        flushPendingUtterances()
    }

    private fun preferredLocales(): List<Locale> = listOf(
        Locale.SIMPLIFIED_CHINESE,
        Locale.CHINA,
        Locale("zh", "CN"),
        Locale.CHINESE
    )

    private fun enqueueOrSpeak(text: String, utteranceId: String) {
        if (!ready) {
            pendingUtterances.addLast(text to utteranceId)
            Log.d(TAG, "Queueing TTS before init: $utteranceId")
            return
        }

        val engine = tts ?: return
        engine.stop()
        pendingSpeakRunnable?.let(mainHandler::removeCallbacks)

        val speakAction = Runnable {
            pendingSpeakRunnable = null
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, speakParams, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS speak failed for $utteranceId result=$result")
            }
        }

        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, PREWARM_TONE_MS)
        pendingSpeakRunnable = speakAction
        mainHandler.postDelayed(speakAction, PREWARM_DELAY_MS)
    }

    private fun flushPendingUtterances() {
        while (pendingUtterances.isNotEmpty()) {
            val (text, id) = pendingUtterances.removeFirst()
            enqueueOrSpeak(text, id)
        }
    }

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

    private fun formatPaceForSpeech(secondsPerKm: Int): String {
        val minutes = secondsPerKm / 60
        val seconds = secondsPerKm % 60
        return "${minutes}分${seconds}秒"
    }
}
