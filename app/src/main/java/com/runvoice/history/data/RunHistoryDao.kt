package com.runvoice.history.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RunHistoryDao {
    @Upsert
    suspend fun upsert(record: RunRecordEntity)

    @Query(
        """
        SELECT * FROM run_records
        WHERE finishedAtEpochMillis >= :startInclusive
          AND finishedAtEpochMillis < :endExclusive
        ORDER BY finishedAtEpochMillis DESC
        """
    )
    fun observeBetween(startInclusive: Long, endExclusive: Long): Flow<List<RunRecordEntity>>

    @Query("SELECT * FROM run_records WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<RunRecordEntity?>

    @Query("SELECT * FROM run_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RunRecordEntity?

    @Query("DELETE FROM run_records WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
