package com.runvoice.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class GpsTracker(context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    private val _distanceMeters = MutableStateFlow(0f)
    val distanceMeters = _distanceMeters.asStateFlow()

    private val _paceSecondsPerKm = MutableStateFlow(0)
    val paceSecondsPerKm = _paceSecondsPerKm.asStateFlow()

    private var lastLocation: Location? = null
    private var totalDistance = 0f

    // For pace calculation: keep recent segment data
    private var segmentDistance = 0f
    private var segmentStartTime = 0L

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 2000L
    ).setMinUpdateIntervalMillis(1000L).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            // Filter out inaccurate points
            if (loc.accuracy > 20f) return
            processLocation(loc)
        }
    }

    private fun processLocation(loc: Location) {
        val prev = lastLocation
        lastLocation = loc

        if (prev == null) {
            segmentStartTime = loc.time
            return
        }

        val d = prev.distanceTo(loc)
        // Ignore unreasonably large jumps (> 100m in ~2-3s = >120 km/h)
        if (d > 100f) return

        totalDistance += d
        segmentDistance += d
        _distanceMeters.value = totalDistance

        // Recalculate pace every time segment reaches 100m+
        if (segmentDistance >= 100f && segmentStartTime > 0) {
            val segmentTimeS = (loc.time - segmentStartTime) / 1000.0
            if (segmentTimeS > 0) {
                val speedMps = segmentDistance / segmentTimeS
                if (speedMps > 0.3) { // moving at least ~1 km/h
                    _paceSecondsPerKm.value = (1000.0 / speedMps).toInt()
                }
            }
            segmentDistance = 0f
            segmentStartTime = loc.time
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        lastLocation = null
        totalDistance = 0f
        segmentDistance = 0f
        segmentStartTime = 0L
        _distanceMeters.value = 0f
        _paceSecondsPerKm.value = 0
        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    fun pause() {
        fusedClient.removeLocationUpdates(locationCallback)
    }

    @SuppressLint("MissingPermission")
    fun resume() {
        segmentDistance = 0f
        segmentStartTime = 0L
        lastLocation = null
        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
        lastLocation = null
    }
}
