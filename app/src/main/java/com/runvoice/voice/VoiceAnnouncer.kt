package com.runvoice.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class VoiceAnnouncer(context: Context) {

    companion object {
        private const val TAG = "RunVoiceTTS"
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingUtterances = ArrayDeque<Pair<String, String>>()

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
        val timeMin = elapsedSeconds / 60
        val timeSec = elapsedSeconds % 60
        val timeText = if (timeMin > 0) "${timeMin}分${timeSec}秒" else "${timeSec}秒"

        val paceMin = paceSecondsPerKm / 60
        val paceSec = paceSecondsPerKm % 60
        val paceText = if (paceSecondsPerKm > 0) "配速${paceMin}分${paceSec}秒" else ""

        val hrText = if (heartRate > 0) "，当前心率${heartRate}" else ""
        val text = "已跑${km}公里，用时${timeText}${hrText}，${paceText}"

        enqueueOrSpeak(text, "km_$km")
    }

    fun speak(text: String) {
        enqueueOrSpeak(text, "custom_${System.currentTimeMillis()}")
    }

    fun shutdown() {
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
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS speak failed for $utteranceId result=$result")
        }
    }

    private fun flushPendingUtterances() {
        while (pendingUtterances.isNotEmpty()) {
            val (text, id) = pendingUtterances.removeFirst()
            enqueueOrSpeak(text, id)
        }
    }
}
