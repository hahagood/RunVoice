package com.runvoice.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceAnnouncer(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                ready = true
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
        if (!ready) return

        val timeMin = elapsedSeconds / 60
        val timeSec = elapsedSeconds % 60
        val timeText = if (timeMin > 0) "${timeMin}分${timeSec}秒" else "${timeSec}秒"

        val paceMin = paceSecondsPerKm / 60
        val paceSec = paceSecondsPerKm % 60
        val paceText = if (paceSecondsPerKm > 0) "配速${paceMin}分${paceSec}秒" else ""

        val hrText = if (heartRate > 0) "，当前心率${heartRate}" else ""
        val text = "已跑${km}公里，用时${timeText}${hrText}，${paceText}"

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "km_$km")
    }

    fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "custom")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
