package com.runvoice.model

data class RunData(
    val elapsedSeconds: Long = 0L,
    val heartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val distanceMeters: Float = 0f,
    val paceSecondsPerKm: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val hrDeviceConnected: Boolean = false,
    val metronomeActive: Boolean = false,
    val metronomeBpm: Int = 180
) {
    val distanceKm: Float get() = distanceMeters / 1000f

    val averagePaceSecondsPerKm: Int
        get() = if (distanceMeters > 0f && elapsedSeconds > 0L) {
            ((elapsedSeconds * 1000f) / distanceMeters).toInt()
        } else {
            0
        }

    val averagePaceFormatted: String
        get() = formatPace(averagePaceSecondsPerKm)

    val timeFormatted: String
        get() {
            val h = elapsedSeconds / 3600
            val m = (elapsedSeconds % 3600) / 60
            val s = elapsedSeconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }

    val paceFormatted: String
        get() = formatPace(paceSecondsPerKm)

    val distanceFormatted: String
        get() = "%.2f".format(distanceKm)

    private fun formatPace(secondsPerKm: Int): String {
        if (secondsPerKm <= 0) return "--'--\""
        return "%d'%02d\"".format(secondsPerKm / 60, secondsPerKm % 60)
    }
}
