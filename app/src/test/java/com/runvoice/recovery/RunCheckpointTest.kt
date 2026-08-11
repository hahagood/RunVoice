package com.runvoice.recovery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCheckpointTest {
    @Test fun codecRoundTripsEveryRecoveryField() {
        val checkpoint = RunCheckpoint(
            tracePath = "/data/run.csv",
            startedAtEpochMillis = 1_700_000_000_000L,
            updatedAtEpochMillis = 1_700_000_010_000L,
            elapsedSeconds = 10L,
            distanceMeters = 42.5f,
            maxHeartRate = 181,
            lastLapElapsedSeconds = 8L,
            wasPaused = true
        )
        val output = ByteArrayOutputStream()

        RunCheckpointCodec.write(checkpoint, output)

        assertEquals(checkpoint, RunCheckpointCodec.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun validationRejectsImpossibleRecoveryValues() {
        val valid = RunCheckpoint(
            tracePath = "/data/run.csv",
            startedAtEpochMillis = 100L,
            updatedAtEpochMillis = 200L,
            elapsedSeconds = 10L,
            distanceMeters = 42.5f,
            maxHeartRate = 181,
            lastLapElapsedSeconds = 8L,
            wasPaused = false
        )

        assertTrue(valid.isValid())
        assertFalse(valid.copy(distanceMeters = Float.NaN).isValid())
        assertFalse(valid.copy(elapsedSeconds = -1L).isValid())
        assertFalse(valid.copy(lastLapElapsedSeconds = 11L).isValid())
    }
}
