package com.runvoice.history.model

enum class RunArchiveStatus {
    Complete,
    Partial
}

data class RunRecord(
    val id: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val elapsedSeconds: Long,
    val distanceMeters: Float,
    val averagePaceSecondsPerKm: Int,
    val maxHeartRateBpm: Int,
    val traceLocalPath: String?,
    val tracePublicReference: String?,
    val posterReference: String?,
    val archiveStatus: RunArchiveStatus,
    val createdAtEpochMillis: Long,
    val recordFormatVersion: Int = CURRENT_RECORD_FORMAT_VERSION
) {
    init {
        require(id.isNotBlank())
        require(startedAtEpochMillis > 0L)
        require(finishedAtEpochMillis >= startedAtEpochMillis)
        require(elapsedSeconds >= 0L)
        require(distanceMeters.isFinite() && distanceMeters >= 0f)
        require(averagePaceSecondsPerKm >= 0)
        require(maxHeartRateBpm >= 0)
        require(createdAtEpochMillis > 0L)
        require(recordFormatVersion > 0)
    }

    companion object {
        const val CURRENT_RECORD_FORMAT_VERSION = 1
    }
}

data class CompletedRunSnapshot(
    val id: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val elapsedSeconds: Long,
    val distanceMeters: Float,
    val averagePaceSecondsPerKm: Int,
    val maxHeartRateBpm: Int,
    val traceWorkingPath: String?
) {
    init {
        require(id.isNotBlank())
        require(startedAtEpochMillis > 0L)
        require(finishedAtEpochMillis >= startedAtEpochMillis)
        require(elapsedSeconds >= 0L)
        require(distanceMeters.isFinite() && distanceMeters >= 0f)
        require(averagePaceSecondsPerKm >= 0)
        require(maxHeartRateBpm >= 0)
    }

    companion object {
        fun stableId(startedAtEpochMillis: Long, finishedAtEpochMillis: Long): String =
            "run-$startedAtEpochMillis-$finishedAtEpochMillis"
    }
}

data class RunMonthSummary(
    val runCount: Int,
    val totalDistanceMeters: Float,
    val totalElapsedSeconds: Long,
    val longestDistanceMeters: Float,
    val fastestAveragePaceSecondsPerKm: Int?
)
