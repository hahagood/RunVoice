package com.runvoice.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class RunTimerTest {
    @Test fun restorePublishesAccumulatedTimeWithoutCountingOfflineGap() {
        val timer = RunTimer { 999_999L }

        timer.restore(7_200L)

        assertEquals(7_200L, timer.elapsedSeconds.value)
    }
}
