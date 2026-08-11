package com.runvoice.core

/**
 * Emits one alert after heart rate stays above [thresholdBpm] for
 * [requiredDurationMillis]. A reading at or below the threshold rearms the
 * policy for the next high-heart-rate episode.
 */
class HeartRateAlertPolicy(
    private val thresholdBpm: Int = 180,
    private val requiredDurationMillis: Long = 3_000L
) {
    init {
        require(thresholdBpm > 0)
        require(requiredDurationMillis > 0L)
    }

    private var highHeartRateStartedAtMillis: Long? = null
    private var alertedForCurrentEpisode = false

    fun shouldAlert(heartRateBpm: Int, nowMillis: Long): Boolean {
        if (heartRateBpm <= thresholdBpm) {
            reset()
            return false
        }

        val startedAt = highHeartRateStartedAtMillis
        if (startedAt == null) {
            highHeartRateStartedAtMillis = nowMillis
            return false
        }
        if (alertedForCurrentEpisode) return false
        if (nowMillis - startedAt < requiredDurationMillis) return false

        alertedForCurrentEpisode = true
        return true
    }

    fun reset() {
        highHeartRateStartedAtMillis = null
        alertedForCurrentEpisode = false
    }
}
