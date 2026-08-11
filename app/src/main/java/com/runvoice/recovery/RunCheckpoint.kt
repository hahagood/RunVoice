package com.runvoice.recovery

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

data class RunCheckpoint(
    val tracePath: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val elapsedSeconds: Long,
    val distanceMeters: Float,
    val maxHeartRate: Int,
    val lastLapElapsedSeconds: Long,
    val wasPaused: Boolean
) {
    fun isValid(): Boolean =
        tracePath.isNotBlank() &&
            startedAtEpochMillis > 0L &&
            updatedAtEpochMillis >= startedAtEpochMillis &&
            elapsedSeconds >= 0L &&
            distanceMeters.isFinite() &&
            distanceMeters >= 0f &&
            maxHeartRate >= 0 &&
            lastLapElapsedSeconds in 0L..elapsedSeconds
}

internal object RunCheckpointCodec {
    private const val MAGIC = 0x52564350
    private const val VERSION = 1

    fun write(checkpoint: RunCheckpoint, output: OutputStream) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeUTF(checkpoint.tracePath)
        data.writeLong(checkpoint.startedAtEpochMillis)
        data.writeLong(checkpoint.updatedAtEpochMillis)
        data.writeLong(checkpoint.elapsedSeconds)
        data.writeFloat(checkpoint.distanceMeters)
        data.writeInt(checkpoint.maxHeartRate)
        data.writeLong(checkpoint.lastLapElapsedSeconds)
        data.writeBoolean(checkpoint.wasPaused)
        data.flush()
    }

    fun read(input: InputStream): RunCheckpoint {
        val data = DataInputStream(input)
        check(data.readInt() == MAGIC) { "Invalid checkpoint header" }
        check(data.readInt() == VERSION) { "Unsupported checkpoint version" }
        return RunCheckpoint(
            tracePath = data.readUTF(),
            startedAtEpochMillis = data.readLong(),
            updatedAtEpochMillis = data.readLong(),
            elapsedSeconds = data.readLong(),
            distanceMeters = data.readFloat(),
            maxHeartRate = data.readInt(),
            lastLapElapsedSeconds = data.readLong(),
            wasPaused = data.readBoolean()
        ).also { check(it.isValid()) { "Invalid checkpoint values" } }
    }
}

class RunCheckpointStore(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("run-checkpoint-v1.bin"))

    @Synchronized
    fun load(): RunCheckpoint? =
        runCatching {
            file.openRead().use(RunCheckpointCodec::read)
        }.getOrNull()

    @Synchronized
    fun save(checkpoint: RunCheckpoint) {
        require(checkpoint.isValid())
        val output = file.startWrite()
        try {
            RunCheckpointCodec.write(checkpoint, output)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
