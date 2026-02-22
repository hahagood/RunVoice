package com.runvoice.model

data class RunData(
    val elapsedSeconds: Long = 0L,
    val heartRate: Int = 0,
    val distanceMeters: Float = 0f,
    val paceSecondsPerKm: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val hrDeviceConnected: Boolean = false,
    val lastKmAnnounced: Int = 0,
    val metronomeActive: Boolean = false,
    val metronomeBpm: Int = 180
) {
    val distanceKm: Float get() = distanceMeters / 1000f

    val timeFormatted: String
        get() {
            val h = elapsedSeconds / 3600
            val m = (elapsedSeconds % 3600) / 60
            val s = elapsedSeconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }

    val paceFormatted: String
        get() {
            if (paceSecondsPerKm <= 0) return "--'--\""
            val m = paceSecondsPerKm / 60
            val s = paceSecondsPerKm % 60
            return "%d'%02d\"".format(m, s)
        }

    val distanceFormatted: String
        get() = "%.2f".format(distanceKm)
}
