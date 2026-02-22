package com.runvoice.tracker

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class MotionDetector(context: Context) : SensorEventListener {

    companion object {
        private const val WINDOW_SIZE = 20        // ~1s at 50Hz
        private const val STILL_THRESHOLD = 0.4f  // m/s², below = stationary
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val buffer = FloatArray(WINDOW_SIZE)
    private var bufferIndex = 0
    private var bufferFilled = false

    private val _isMoving = MutableStateFlow(true)
    val isMoving = _isMoving.asStateFlow()

    fun start() {
        bufferIndex = 0
        bufferFilled = false
        _isMoving.value = true
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        _isMoving.value = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        val magnitude = sqrt(event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2])

        buffer[bufferIndex] = magnitude
        bufferIndex = (bufferIndex + 1) % WINDOW_SIZE
        if (bufferIndex == 0) bufferFilled = true

        val count = if (bufferFilled) WINDOW_SIZE else bufferIndex
        if (count == 0) return

        var sum = 0f
        for (i in 0 until count) sum += buffer[i]
        _isMoving.value = (sum / count) > STILL_THRESHOLD
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
