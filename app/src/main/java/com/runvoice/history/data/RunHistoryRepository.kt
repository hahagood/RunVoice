package com.runvoice.history.data

import com.runvoice.history.archive.RunRecordWriter
import com.runvoice.history.model.RunMonthSummary
import com.runvoice.history.model.RunRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RunHistoryRepository(
    private val dao: RunHistoryDao,
    private val fileCleaner: RunHistoryFileCleaner? = null
) : RunRecordWriter {
    fun observeBetween(startInclusive: Long, endExclusive: Long): Flow<List<RunRecord>> =
        dao.observeBetween(startInclusive, endExclusive).map { records ->
            records.map(RunRecordEntity::toModel)
        }

    fun observeById(id: String): Flow<RunRecord?> =
        dao.observeById(id).map { it?.toModel() }

    override suspend fun upsert(record: RunRecord) {
        dao.upsert(record.toEntity())
    }

    suspend fun deleteById(id: String): Result<Unit> = try {
        val record = dao.getById(id)?.toModel()
            ?: return Result.success(Unit)
        fileCleaner?.deletePrivateFiles(record)?.getOrThrow()
        check(dao.deleteById(id) == 1) { "历史记录删除失败" }
        Result.success(Unit)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    companion object {
        private const val FASTEST_PACE_MIN_DISTANCE_METERS = 1_000f

        fun summarize(records: List<RunRecord>): RunMonthSummary {
            val eligiblePaces = records.asSequence()
                .filter { it.distanceMeters >= FASTEST_PACE_MIN_DISTANCE_METERS }
                .map { it.averagePaceSecondsPerKm }
                .filter { it > 0 }
                .toList()
            return RunMonthSummary(
                runCount = records.size,
                totalDistanceMeters = records.sumOf { it.distanceMeters.toDouble() }.toFloat(),
                totalElapsedSeconds = records.sumOf { it.elapsedSeconds },
                longestDistanceMeters = records.maxOfOrNull { it.distanceMeters } ?: 0f,
                fastestAveragePaceSecondsPerKm = eligiblePaces.minOrNull()
            )
        }
    }
}
