package com.runvoice.tracker

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RunTimer(private val nowMillis: () -> Long = SystemClock::elapsedRealtime) {

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds = _elapsedSeconds.asStateFlow()

    private var timerJob: Job? = null
    private var accumulated = 0L
    private var startTimeMillis = 0L

    fun start(scope: CoroutineScope) {
        if (timerJob?.isActive == true) return
        startTimeMillis = nowMillis()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _elapsedSeconds.value = accumulated +
                    (nowMillis() - startTimeMillis).coerceAtLeast(0L) / 1000
            }
        }
    }

    fun pause() {
        timerJob?.cancel()
        timerJob = null
        accumulated = _elapsedSeconds.value
    }

    fun restore(elapsedSeconds: Long) {
        require(elapsedSeconds >= 0L)
        timerJob?.cancel()
        timerJob = null
        accumulated = elapsedSeconds
        _elapsedSeconds.value = elapsedSeconds
    }

    fun reset() {
        timerJob?.cancel()
        timerJob = null
        accumulated = 0L
        _elapsedSeconds.value = 0L
    }
}
