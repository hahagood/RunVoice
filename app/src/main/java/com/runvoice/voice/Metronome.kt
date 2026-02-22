package com.runvoice.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class Metronome {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val TONE_FREQ_HZ = 350.0
        private const val TONE_DURATION_MS = 50
        private const val FADE_SAMPLES = 200 // ~4.5ms fade in/out
        private const val DEFAULT_BPM = 180
        private const val MIN_BPM = 160
        private const val MAX_BPM = 220
    }

    private val _bpm = MutableStateFlow(DEFAULT_BPM)
    val bpm = _bpm.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val pcmBuffer: ShortArray
    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null

    init {
        val numSamples = SAMPLE_RATE * TONE_DURATION_MS / 1000
        pcmBuffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            var sample = sin(2.0 * PI * TONE_FREQ_HZ * i / SAMPLE_RATE)
            // Fade in
            if (i < FADE_SAMPLES) {
                sample *= i.toDouble() / FADE_SAMPLES
            }
            // Fade out
            val fromEnd = numSamples - 1 - i
            if (fromEnd < FADE_SAMPLES) {
                sample *= fromEnd.toDouble() / FADE_SAMPLES
            }
            pcmBuffer[i] = (sample * Short.MAX_VALUE * 0.3).toInt().toShort()
        }
    }

    private fun ensureAudioTrack(): AudioTrack {
        audioTrack?.let { return it }
        val bufSize = pcmBuffer.size * 2 // bytes
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcmBuffer, 0, pcmBuffer.size)
        audioTrack = track
        return track
    }

    fun start(scope: CoroutineScope) {
        if (_isPlaying.value) return
        _isPlaying.value = true
        val track = ensureAudioTrack()
        playJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                track.stop()
                track.reloadStaticData()
                track.play()
                delay(60_000L / _bpm.value)
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        audioTrack?.stop()
        _isPlaying.value = false
    }

    fun toggle(scope: CoroutineScope) {
        if (_isPlaying.value) stop() else start(scope)
    }

    fun setBpm(value: Int) {
        _bpm.value = value.coerceIn(MIN_BPM, MAX_BPM)
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
    }
}
