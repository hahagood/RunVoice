package com.runvoice.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMonitorTest {
    @Test fun parsesEightBitHeartRate() {
        assertEquals(142, HeartRateMonitor.parseHeartRate(byteArrayOf(0, 142.toByte())))
    }

    @Test fun parsesSixteenBitHeartRate() {
        assertEquals(300, HeartRateMonitor.parseHeartRate(byteArrayOf(1, 44, 1)))
    }

    @Test fun rejectsTruncatedPackets() {
        assertNull(HeartRateMonitor.parseHeartRate(byteArrayOf()))
        assertNull(HeartRateMonitor.parseHeartRate(byteArrayOf(0)))
        assertNull(HeartRateMonitor.parseHeartRate(byteArrayOf(1, 44)))
    }

    @Test fun reconnectBackoffCapsAtThirtySeconds() {
        assertEquals(2_000L, HeartRateMonitor.reconnectDelayMillis(0))
        assertEquals(5_000L, HeartRateMonitor.reconnectDelayMillis(1))
        assertEquals(10_000L, HeartRateMonitor.reconnectDelayMillis(2))
        assertEquals(20_000L, HeartRateMonitor.reconnectDelayMillis(3))
        assertEquals(30_000L, HeartRateMonitor.reconnectDelayMillis(4))
        assertEquals(30_000L, HeartRateMonitor.reconnectDelayMillis(20))
    }
}
