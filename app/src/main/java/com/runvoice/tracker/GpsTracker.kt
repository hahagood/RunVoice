package com.runvoice.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.runvoice.core.LocationSample
import com.runvoice.core.TrackingDisposition
import com.runvoice.core.TrackingEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android location adapter; all acceptance, distance and pace rules live in [TrackingEngine]. */
class GpsTracker(context: Context, private val motionDetector: MotionDetector? = null) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val traceRecorder = GpsTraceRecorder(context)
    private val engine = TrackingEngine()
    private var heartRateProvider: () -> Int = { 0 }
    private var hrConnectedProvider: () -> Boolean = { false }

    private val _distanceMeters = MutableStateFlow(0f)
    val distanceMeters = _distanceMeters.asStateFlow()

    private val _paceSecondsPerKm = MutableStateFlow(0)
    val paceSecondsPerKm = _paceSecondsPerKm.asStateFlow()

    private val _stationaryDetected = MutableStateFlow(false)
    val stationaryDetected = _stationaryDetected.asStateFlow()

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        2_000L
    ).setMinUpdateIntervalMillis(1_000L).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::processLocation)
        }
    }

    private fun processLocation(location: Location) {
        val decision = engine.process(
            LocationSample(
                wallTimeMillis = location.time,
                elapsedRealtimeMillis = location.elapsedRealtimeNanos / 1_000_000L,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
                motionMoving = motionDetector?.isMoving?.value
            )
        )

        _distanceMeters.value = decision.totalDistanceMeters
        _paceSecondsPerKm.value = decision.paceSecondsPerKm
        _stationaryDetected.value = decision.stationaryDetected
        traceRecorder.record(
            location = location,
            motionState = motionDetector?.isMoving?.value,
            decision = if (decision.disposition == TrackingDisposition.Accepted) "accepted" else "ignored",
            reason = decision.reason,
            deltaMeters = decision.deltaMeters,
            totalDistanceMeters = decision.totalDistanceMeters,
            segmentDistanceMeters = decision.segmentDistanceMeters,
            paceSecondsPerKm = decision.paceSecondsPerKm,
            heartRate = heartRateProvider(),
            hrConnected = hrConnectedProvider()
        )
    }

    @SuppressLint("MissingPermission")
    fun start(): Result<Unit> = runCatching {
        engine.reset()
        publishResetState()
        traceRecorder.startSession()
        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    fun pause() {
        fusedClient.removeLocationUpdates(locationCallback)
    }

    @SuppressLint("MissingPermission")
    fun resume(): Result<Unit> = runCatching {
        engine.resumeTracking()
        _stationaryDetected.value = false
        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    fun stop(saveSession: Boolean = true): TraceSaveResult {
        stopUpdates()
        return closeTrace(saveSession)
    }

    fun stopUpdates() {
        fusedClient.removeLocationUpdates(locationCallback)
        engine.resumeTracking()
        _stationaryDetected.value = false
    }

    fun closeTrace(saveSession: Boolean): TraceSaveResult = traceRecorder.closeSession(save = saveSession)

    fun flushTrace() = traceRecorder.flush()

    fun currentTracePath(): String? = traceRecorder.currentPath()

    fun setHeartRateProviders(
        heartRateProvider: () -> Int,
        hrConnectedProvider: () -> Boolean
    ) {
        this.heartRateProvider = heartRateProvider
        this.hrConnectedProvider = hrConnectedProvider
    }

    private fun publishResetState() {
        _distanceMeters.value = 0f
        _paceSecondsPerKm.value = 0
        _stationaryDetected.value = false
    }
}
