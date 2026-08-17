package com.runvoice.history.archive

import android.content.Context
import com.runvoice.history.model.CompletedRunSnapshot
import com.runvoice.model.RunData
import com.runvoice.share.RunSummaryImageSaver
import com.runvoice.share.SummaryImageSaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RunSummaryImageArchiver(context: Context) : SummaryImageArchiver {
    private val saver = RunSummaryImageSaver(context.applicationContext)

    override suspend fun save(snapshot: CompletedRunSnapshot): SummaryImageSaveResult =
        withContext(Dispatchers.IO) {
            saver.saveSummary(
                runData = RunData(
                    elapsedSeconds = snapshot.elapsedSeconds,
                    maxHeartRate = snapshot.maxHeartRateBpm,
                    distanceMeters = snapshot.distanceMeters
                ),
                finishedAtMillis = snapshot.finishedAtEpochMillis,
                traceCsvPath = snapshot.traceWorkingPath
            )
        }
}
