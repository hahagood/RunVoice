package com.runvoice.history.archive

import com.runvoice.history.model.CompletedRunSnapshot
import com.runvoice.history.model.RunArchiveStatus
import com.runvoice.history.model.RunRecord
import com.runvoice.share.SummaryImageSaveResult
import com.runvoice.tracker.TraceSaveResult
import kotlinx.coroutines.CancellationException

fun interface RunRecordWriter {
    suspend fun upsert(record: RunRecord)
}

fun interface SummaryImageArchiver {
    suspend fun save(snapshot: CompletedRunSnapshot): SummaryImageSaveResult
}

enum class RunArchiveOutcome {
    Complete,
    Partial,
    Failed
}

data class RunArchiveResult(
    val outcome: RunArchiveOutcome,
    val record: RunRecord?,
    val imageResult: Result<SummaryImageSaveResult>,
    val traceResult: TraceSaveResult,
    val historyResult: Result<Unit>
) {
    val message: String
        get() = when (outcome) {
            RunArchiveOutcome.Complete -> "摘要海报、轨迹和历史记录均已保存"
            RunArchiveOutcome.Partial -> buildList {
                if (historyResult.isSuccess) add("历史记录已保存")
                else add("历史记录保存失败：${historyResult.exceptionOrNull()?.message ?: "未知错误"}")
                if (imageResult.isFailure) {
                    add("摘要海报保存失败：${imageResult.exceptionOrNull()?.message ?: "未知错误"}")
                }
                when (traceResult) {
                    is TraceSaveResult.Failed -> add("轨迹数据保存失败：${traceResult.message}")
                    TraceSaveResult.Discarded -> add("没有生成轨迹文件")
                    is TraceSaveResult.Saved -> Unit
                }
            }.joinToString("；")
            RunArchiveOutcome.Failed -> "保存失败：历史记录、海报和轨迹均未能保留"
        }
}

class RunArchiveCoordinator(
    private val recordWriter: RunRecordWriter,
    private val imageArchiver: SummaryImageArchiver,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun archive(
        snapshot: CompletedRunSnapshot,
        finalizeTrace: suspend () -> TraceSaveResult
    ): RunArchiveResult {
        val imageResult = runCatchingPreservingCancellation { imageArchiver.save(snapshot) }
        val traceResult = runCatchingPreservingCancellation { finalizeTrace() }
            .getOrElse { TraceSaveResult.Failed(it.message ?: "轨迹保存发生未知错误") }
        val allArtifactsSaved = imageResult.isSuccess && traceResult is TraceSaveResult.Saved
        val record = RunRecord(
            id = snapshot.id,
            startedAtEpochMillis = snapshot.startedAtEpochMillis,
            finishedAtEpochMillis = snapshot.finishedAtEpochMillis,
            elapsedSeconds = snapshot.elapsedSeconds,
            distanceMeters = snapshot.distanceMeters,
            averagePaceSecondsPerKm = snapshot.averagePaceSecondsPerKm,
            maxHeartRateBpm = snapshot.maxHeartRateBpm,
            traceLocalPath = when (traceResult) {
                is TraceSaveResult.Saved -> traceResult.localPath
                is TraceSaveResult.Failed -> traceResult.localPath ?: snapshot.traceWorkingPath
                TraceSaveResult.Discarded -> null
            },
            tracePublicReference = (traceResult as? TraceSaveResult.Saved)?.publicPath,
            posterReference = imageResult.getOrNull()?.reference,
            archiveStatus = if (allArtifactsSaved) RunArchiveStatus.Complete else RunArchiveStatus.Partial,
            createdAtEpochMillis = nowEpochMillis()
        )
        val historyResult = runCatchingPreservingCancellation { recordWriter.upsert(record) }
        val anySaved = historyResult.isSuccess || imageResult.isSuccess || traceResult is TraceSaveResult.Saved
        val outcome = when {
            allArtifactsSaved && historyResult.isSuccess -> RunArchiveOutcome.Complete
            anySaved -> RunArchiveOutcome.Partial
            else -> RunArchiveOutcome.Failed
        }
        return RunArchiveResult(
            outcome = outcome,
            record = record.takeIf { historyResult.isSuccess },
            imageResult = imageResult,
            traceResult = traceResult,
            historyResult = historyResult
        )
    }
}

private suspend fun <T> runCatchingPreservingCancellation(
    block: suspend () -> T
): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    Result.failure(failure)
}
