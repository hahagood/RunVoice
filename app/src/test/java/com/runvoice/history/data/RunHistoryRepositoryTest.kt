package com.runvoice.history.data

import com.runvoice.history.model.RunArchiveStatus
import com.runvoice.history.model.RunRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunHistoryRepositoryTest {
    @Test
    fun summarize_aggregatesRunsAndIgnoresShortRunsForFastestPace() {
        val records = listOf(
            record("short", distance = 500f, elapsed = 100L, pace = 200),
            record("longer", distance = 5_000f, elapsed = 1_500L, pace = 300),
            record("fast", distance = 1_000f, elapsed = 280L, pace = 280)
        )

        val summary = RunHistoryRepository.summarize(records)

        assertEquals(3, summary.runCount)
        assertEquals(6_500f, summary.totalDistanceMeters)
        assertEquals(1_880L, summary.totalElapsedSeconds)
        assertEquals(5_000f, summary.longestDistanceMeters)
        assertEquals(280, summary.fastestAveragePaceSecondsPerKm)
    }

    @Test
    fun summarize_emptyMonth_hasNeutralValues() {
        val summary = RunHistoryRepository.summarize(emptyList())

        assertEquals(0, summary.runCount)
        assertEquals(0f, summary.totalDistanceMeters)
        assertEquals(0L, summary.totalElapsedSeconds)
        assertEquals(0f, summary.longestDistanceMeters)
        assertNull(summary.fastestAveragePaceSecondsPerKm)
    }

    private fun record(
        id: String,
        distance: Float,
        elapsed: Long,
        pace: Int
    ) = RunRecord(
        id = id,
        startedAtEpochMillis = 1_000L,
        finishedAtEpochMillis = 2_000L,
        elapsedSeconds = elapsed,
        distanceMeters = distance,
        averagePaceSecondsPerKm = pace,
        maxHeartRateBpm = 0,
        traceLocalPath = null,
        tracePublicReference = null,
        posterReference = null,
        archiveStatus = RunArchiveStatus.Partial,
        createdAtEpochMillis = 3_000L
    )
}
