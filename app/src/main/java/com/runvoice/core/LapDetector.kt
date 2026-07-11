package com.runvoice.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LapCompletion(
    val lapNumber: Int,
    val lapDistanceMeters: Float,
    val totalDistanceMeters: Float
)

data class LapDetectionConfig(
    val finishRadiusMeters: Float = 35f,
    val armRadiusMeters: Float = 70f,
    val minimumLapDistanceMeters: Float = 300f
)

/** Detects completed closed laps by requiring a real departure before a return to the lap anchor. */
class LapDetector(private val config: LapDetectionConfig = LapDetectionConfig()) {
    private var anchorLatitude: Double? = null
    private var anchorLongitude: Double? = null
    private var lapStartDistanceMeters = 0f
    private var armed = false
    private var completedLaps = 0

    fun reset() {
        anchorLatitude = null
        anchorLongitude = null
        lapStartDistanceMeters = 0f
        armed = false
        completedLaps = 0
    }

    fun process(latitude: Double, longitude: Double, totalDistanceMeters: Float): LapCompletion? {
        if (!latitude.isFinite() || !longitude.isFinite() || !totalDistanceMeters.isFinite()) return null
        val anchorLat = anchorLatitude
        val anchorLon = anchorLongitude
        if (anchorLat == null || anchorLon == null) {
            anchorLatitude = latitude
            anchorLongitude = longitude
            lapStartDistanceMeters = totalDistanceMeters
            return null
        }

        val distanceFromAnchor = distanceMeters(anchorLat, anchorLon, latitude, longitude)
        val lapDistance = totalDistanceMeters - lapStartDistanceMeters
        if (!armed && distanceFromAnchor >= config.armRadiusMeters) armed = true
        if (!armed || lapDistance < config.minimumLapDistanceMeters ||
            distanceFromAnchor > config.finishRadiusMeters
        ) return null

        completedLaps++
        val completion = LapCompletion(completedLaps, lapDistance, totalDistanceMeters)
        anchorLatitude = latitude
        anchorLongitude = longitude
        lapStartDistanceMeters = totalDistanceMeters
        armed = false
        return completion
    }

    private fun distanceMeters(lat1Degrees: Double, lon1Degrees: Double, lat2Degrees: Double, lon2Degrees: Double): Float {
        val lat1 = lat1Degrees * PI / 180.0
        val lat2 = lat2Degrees * PI / 180.0
        val dLat = lat2 - lat1
        val dLon = (lon2Degrees - lon1Degrees) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return (2.0 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))).toFloat()
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
