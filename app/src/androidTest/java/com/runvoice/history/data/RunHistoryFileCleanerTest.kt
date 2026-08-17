package com.runvoice.history.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.runvoice.history.model.RunArchiveStatus
import com.runvoice.history.model.RunRecord
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunHistoryFileCleanerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deletePrivateFiles_deletesAppTraceButLeavesReferencesOutsideAllowedRoots() {
        val traceDir = requireNotNull(context.getExternalFilesDir("gps-traces"))
        val privateTrace = File(traceDir, "cleaner-test.csv").apply { writeText("trace") }
        val outsideFile = File(context.cacheDir, "cleaner-public-copy.csv").apply { writeText("public") }
        val record = record(
            traceLocalPath = privateTrace.absolutePath,
            posterReference = outsideFile.absolutePath
        )

        val result = RunHistoryFileCleaner(context).deletePrivateFiles(record)

        assertTrue(result.isSuccess)
        assertFalse(privateTrace.exists())
        assertTrue(outsideFile.exists())
        outsideFile.delete()
    }

    @Test
    fun deletePrivateFiles_doesNotDeleteContentUriPublicCopy() {
        val record = record(
            traceLocalPath = null,
            posterReference = "content://media/external/images/media/42"
        )

        assertTrue(RunHistoryFileCleaner(context).deletePrivateFiles(record).isSuccess)
    }

    private fun record(traceLocalPath: String?, posterReference: String?) = RunRecord(
        id = "run-cleaner-test",
        startedAtEpochMillis = 1_000L,
        finishedAtEpochMillis = 2_000L,
        elapsedSeconds = 1L,
        distanceMeters = 1f,
        averagePaceSecondsPerKm = 1,
        maxHeartRateBpm = 0,
        traceLocalPath = traceLocalPath,
        tracePublicReference = "Documents/RunVoice/gps-traces/public.csv",
        posterReference = posterReference,
        archiveStatus = RunArchiveStatus.Partial,
        createdAtEpochMillis = 3_000L
    )
}
