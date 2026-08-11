package com.runvoice.tracker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTraceCsvTest {
    @Test fun preservesCompleteTrailingRecordAndAddsMissingNewline() {
        val file = temporaryTrace(
            GPS_TRACE_CSV_HEADER + "\n" +
                row(timestamp = 100L, distance = 12.5f, heartRate = 170, decision = "accepted")
        )

        val recovery = RecoveryTraceCsv.repairAndRead(file)

        assertEquals(12.5f, recovery.totalDistanceMeters)
        assertEquals(170, recovery.maxHeartRate)
        assertEquals(1, recovery.acceptedPoints.size)
        assertTrue(file.readText().endsWith("\n"))
        file.delete()
    }

    @Test fun removesPartialTrailingRecordAfterPowerLoss() {
        val complete = row(timestamp = 100L, distance = 25f, heartRate = 180, decision = "accepted")
        val file = temporaryTrace(
            GPS_TRACE_CSV_HEADER + "\n" +
                complete + "\n" +
                "101,30.0,114.0,4.0"
        )

        val recovery = RecoveryTraceCsv.repairAndRead(file)

        assertEquals(25f, recovery.totalDistanceMeters)
        assertEquals(180, recovery.maxHeartRate)
        assertEquals(GPS_TRACE_CSV_HEADER + "\n" + complete + "\n", file.readText())
        file.delete()
    }

    @Test fun restoresMaximumHeartRateAcrossAcceptedAndIgnoredRows() {
        val file = temporaryTrace(
            GPS_TRACE_CSV_HEADER + "\n" +
                row(timestamp = 100L, distance = 10f, heartRate = 170, decision = "accepted") + "\n" +
                row(timestamp = 101L, distance = 10f, heartRate = 201, decision = "ignored") + "\n"
        )

        val recovery = RecoveryTraceCsv.repairAndRead(file)

        assertEquals(10f, recovery.totalDistanceMeters)
        assertEquals(201, recovery.maxHeartRate)
        assertEquals(1, recovery.acceptedPoints.size)
        file.delete()
    }

    private fun temporaryTrace(content: String): File =
        kotlin.io.path.createTempFile("runvoice-recovery-", ".csv").toFile().apply {
            writeText(content)
        }

    private fun row(
        timestamp: Long,
        distance: Float,
        heartRate: Int,
        decision: String
    ): String = listOf(
        timestamp,
        30.0,
        114.0,
        4.0,
        2.5,
        90.0,
        20.0,
        "\"fused\"",
        true,
        decision,
        "\"distance_accumulated\"",
        2.5,
        distance,
        0.0,
        400,
        heartRate,
        true
    ).joinToString(",")
}
