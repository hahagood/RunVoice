package com.runvoice.tracker

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RunTimer {

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds = _elapsedSeconds.asStateFlow()

    private var timerJob: Job? = null
    private var accumulated = 0L
    private var startTimeMillis = 0L

    fun start(scope: CoroutineScope) {
        if (timerJob?.isActive == true) return
        startTimeMillis = System.currentTimeMillis()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _elapsedSeconds.value = accumulated +
                    (System.currentTimeMillis() - startTimeMillis) / 1000
            }
        }
    }

    fun pause() {
        timerJob?.cancel()
        timerJob = null
        accumulated = _elapsedSeconds.value
    }

    fun reset() {
        timerJob?.cancel()
        timerJob = null
        accumulated = 0L
        _elapsedSeconds.value = 0L
    }
}
