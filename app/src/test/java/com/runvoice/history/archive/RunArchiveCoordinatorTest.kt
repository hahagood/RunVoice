package com.runvoice.history.archive

import com.runvoice.history.model.CompletedRunSnapshot
import com.runvoice.history.model.RunArchiveStatus
import com.runvoice.history.model.RunRecord
import com.runvoice.share.SummaryImageSaveResult
import com.runvoice.tracker.TraceSaveResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunArchiveCoordinatorTest {
    private val snapshot = CompletedRunSnapshot(
        id = "run-1000-61000",
        startedAtEpochMillis = 1_000L,
        finishedAtEpochMillis = 61_000L,
        elapsedSeconds = 60L,
        distanceMeters = 250f,
        averagePaceSecondsPerKm = 240,
        maxHeartRateBpm = 172,
        traceWorkingPath = "/private/working.csv"
    )

    @Test
    fun allArtifactsAndHistorySucceed_returnsCompleteRecord() = runBlocking {
        var written: RunRecord? = null
        val coordinator = coordinator(
            writer = { written = it },
            image = { SummaryImageSaveResult("saved", "content://poster") }
        )

        val result = coordinator.archive(snapshot) {
            TraceSaveResult.Saved("/private/final.csv", "content://trace")
        }

        assertEquals(RunArchiveOutcome.Complete, result.outcome)
        assertEquals(RunArchiveStatus.Complete, written?.archiveStatus)
        assertEquals("/private/final.csv", written?.traceLocalPath)
        assertEquals("content://trace", written?.tracePublicReference)
        assertEquals("content://poster", written?.posterReference)
        assertEquals(written, result.record)
    }

    @Test
    fun imageFailure_stillFinalizesTraceAndWritesPartialHistory() = runBlocking {
        var written: RunRecord? = null
        var traceFinalized = false
        val coordinator = coordinator(
            writer = { written = it },
            image = { error("gallery unavailable") }
        )

        val result = coordinator.archive(snapshot) {
            traceFinalized = true
            TraceSaveResult.Saved("/private/final.csv", "content://trace")
        }

        assertTrue(traceFinalized)
        assertEquals(RunArchiveOutcome.Partial, result.outcome)
        assertEquals(RunArchiveStatus.Partial, written?.archiveStatus)
        assertNull(written?.posterReference)
        assertNotNull(result.record)
    }

    @Test
    fun traceFailure_preservesWorkingPathAndWritesPartialHistory() = runBlocking {
        var written: RunRecord? = null
        val coordinator = coordinator(writer = { written = it })

        val result = coordinator.archive(snapshot) {
            TraceSaveResult.Failed("export failed")
        }

        assertEquals(RunArchiveOutcome.Partial, result.outcome)
        assertEquals("/private/working.csv", written?.traceLocalPath)
        assertNull(written?.tracePublicReference)
    }

    @Test
    fun discardedTrace_doesNotClaimWorkingFileWasRetained() = runBlocking {
        var written: RunRecord? = null
        val coordinator = coordinator(writer = { written = it })

        val result = coordinator.archive(snapshot) { TraceSaveResult.Discarded }

        assertEquals(RunArchiveOutcome.Partial, result.outcome)
        assertNull(written?.traceLocalPath)
        assertNull(written?.tracePublicReference)
    }

    @Test
    fun historyFailure_doesNotHideSavedArtifacts() = runBlocking {
        val coordinator = coordinator(writer = { error("database full") })

        val result = coordinator.archive(snapshot) {
            TraceSaveResult.Saved("/private/final.csv", "content://trace")
        }

        assertEquals(RunArchiveOutcome.Partial, result.outcome)
        assertNull(result.record)
        assertTrue(result.imageResult.isSuccess)
        assertTrue(result.historyResult.isFailure)
    }

    @Test
    fun everyOperationFails_returnsFailedWithoutRecord() = runBlocking {
        val coordinator = coordinator(
            writer = { error("database full") },
            image = { error("gallery unavailable") }
        )

        val result = coordinator.archive(snapshot) { error("service disconnected") }

        assertEquals(RunArchiveOutcome.Failed, result.outcome)
        assertNull(result.record)
        assertTrue(result.traceResult is TraceSaveResult.Failed)
    }

    @Test
    fun cancellation_propagatesWithoutFinalizingTraceOrWritingHistory() = runBlocking {
        var traceFinalized = false
        var historyWritten = false
        val coordinator = coordinator(
            writer = { historyWritten = true },
            image = { throw CancellationException("screen closed") }
        )

        var cancellationSeen = false
        try {
            coordinator.archive(snapshot) {
                traceFinalized = true
                TraceSaveResult.Discarded
            }
        } catch (_: CancellationException) {
            cancellationSeen = true
        }

        assertTrue(cancellationSeen)
        assertTrue(!traceFinalized)
        assertTrue(!historyWritten)
    }

    private fun coordinator(
        writer: suspend (RunRecord) -> Unit,
        image: suspend (CompletedRunSnapshot) -> SummaryImageSaveResult = {
            SummaryImageSaveResult("saved", "content://poster")
        }
    ) = RunArchiveCoordinator(
        recordWriter = RunRecordWriter(writer),
        imageArchiver = SummaryImageArchiver(image),
        nowEpochMillis = { 99_000L }
    )
}
