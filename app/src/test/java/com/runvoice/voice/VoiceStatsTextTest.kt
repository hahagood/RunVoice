package com.runvoice.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceStatsTextTest {
    @Test fun formatsPaceAsCompactMinutesAndTwoDigitSeconds() {
        assertEquals("三四六", VoiceStatsText.pace(3 * 60 + 46))
        assertEquals("四〇六", VoiceStatsText.pace(4 * 60 + 6))
        assertEquals("幺〇〇〇", VoiceStatsText.pace(10 * 60))
        assertEquals("幺二三五", VoiceStatsText.pace(12 * 60 + 35))
    }

    @Test fun formatsHeartRateAsIndividualDigits() {
        assertEquals("幺五五", VoiceStatsText.heartRate(155))
        assertEquals("二零五", VoiceStatsText.heartRate(205))
    }
}
