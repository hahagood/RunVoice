package com.runvoice.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.sin

/**
 * Hardware-timed metronome: writes a continuous PCM stream (tick + silence)
 * to AudioTrack in MODE_STREAM. Timing precision is driven by the audio DAC
 * clock, not by CPU thread scheduling.
 */
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

    // Pre-computed tick tone (50ms)
    private val tickSamples: ShortArray
    // Pre-computed silence buffer (reusable chunk)
    private val silenceChunk = ShortArray(1024)

    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null
    @Volatile private var stopRequested = false

    init {
        val numSamples = SAMPLE_RATE * TONE_DURATION_MS / 1000
        tickSamples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            var sample = sin(2.0 * PI * TONE_FREQ_HZ * i / SAMPLE_RATE)
            if (i < FADE_SAMPLES) {
                sample *= i.toDouble() / FADE_SAMPLES
            }
            val fromEnd = numSamples - 1 - i
            if (fromEnd < FADE_SAMPLES) {
                sample *= fromEnd.toDouble() / FADE_SAMPLES
            }
            tickSamples[i] = (sample * Short.MAX_VALUE * 0.3).toInt().toShort()
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
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
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start(@Suppress("UNUSED_PARAMETER") scope: kotlinx.coroutines.CoroutineScope) {
        if (_isPlaying.value) return
        _isPlaying.value = true
        stopRequested = false

        val track = createAudioTrack()
        audioTrack = track
        track.play()

        playThread = Thread({
            while (!stopRequested) {
                val intervalSamples = SAMPLE_RATE * 60 / _bpm.value
                val silenceSamples = intervalSamples - tickSamples.size

                // Write tick
                track.write(tickSamples, 0, tickSamples.size)

                // Write silence in chunks
                var remaining = silenceSamples
                while (remaining > 0 && !stopRequested) {
                    val n = minOf(remaining, silenceChunk.size)
                    track.write(silenceChunk, 0, n)
                    remaining -= n
                }
            }
        }, "Metronome").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        stopRequested = true
        playThread?.join(500)
        playThread = null
        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) {}
        audioTrack?.release()
        audioTrack = null
        _isPlaying.value = false
    }

    fun toggle(scope: kotlinx.coroutines.CoroutineScope) {
        if (_isPlaying.value) stop() else start(scope)
    }

    fun setBpm(value: Int) {
        _bpm.value = value.coerceIn(MIN_BPM, MAX_BPM)
    }

    fun release() {
        stop()
    }
}
