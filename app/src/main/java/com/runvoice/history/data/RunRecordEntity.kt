package com.runvoice.history.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.runvoice.history.model.RunArchiveStatus
import com.runvoice.history.model.RunRecord

@Entity(
    tableName = "run_records",
    indices = [Index(value = ["finishedAtEpochMillis"])]
)
data class RunRecordEntity(
    @PrimaryKey val id: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val elapsedSeconds: Long,
    val distanceMeters: Float,
    val averagePaceSecondsPerKm: Int,
    val maxHeartRateBpm: Int,
    val traceLocalPath: String?,
    val tracePublicReference: String?,
    val posterReference: String?,
    val archiveStatus: String,
    val createdAtEpochMillis: Long,
    val recordFormatVersion: Int
)

internal fun RunRecordEntity.toModel(): RunRecord = RunRecord(
    id = id,
    startedAtEpochMillis = startedAtEpochMillis,
    finishedAtEpochMillis = finishedAtEpochMillis,
    elapsedSeconds = elapsedSeconds,
    distanceMeters = distanceMeters,
    averagePaceSecondsPerKm = averagePaceSecondsPerKm,
    maxHeartRateBpm = maxHeartRateBpm,
    traceLocalPath = traceLocalPath,
    tracePublicReference = tracePublicReference,
    posterReference = posterReference,
    archiveStatus = RunArchiveStatus.entries.firstOrNull { it.name == archiveStatus }
        ?: RunArchiveStatus.Partial,
    createdAtEpochMillis = createdAtEpochMillis,
    recordFormatVersion = recordFormatVersion
)

internal fun RunRecord.toEntity(): RunRecordEntity = RunRecordEntity(
    id = id,
    startedAtEpochMillis = startedAtEpochMillis,
    finishedAtEpochMillis = finishedAtEpochMillis,
    elapsedSeconds = elapsedSeconds,
    distanceMeters = distanceMeters,
    averagePaceSecondsPerKm = averagePaceSecondsPerKm,
    maxHeartRateBpm = maxHeartRateBpm,
    traceLocalPath = traceLocalPath,
    tracePublicReference = tracePublicReference,
    posterReference = posterReference,
    archiveStatus = archiveStatus.name,
    createdAtEpochMillis = createdAtEpochMillis,
    recordFormatVersion = recordFormatVersion
)
