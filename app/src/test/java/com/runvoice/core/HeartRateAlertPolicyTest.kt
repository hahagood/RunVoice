package com.runvoice.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateAlertPolicyTest {
    @Test fun alertsAfterHeartRateStaysAboveThresholdForThreeSeconds() {
        val policy = HeartRateAlertPolicy()

        assertFalse(policy.shouldAlert(181, 1_000L))
        assertFalse(policy.shouldAlert(190, 3_999L))
        assertTrue(policy.shouldAlert(190, 4_000L))
    }

    @Test fun heartRateAtThresholdDoesNotStartAnAlert() {
        val policy = HeartRateAlertPolicy()

        assertFalse(policy.shouldAlert(180, 1_000L))
        assertFalse(policy.shouldAlert(180, 5_000L))
    }

    @Test fun alertsOnlyOnceDuringOneContinuousHighEpisode() {
        val policy = HeartRateAlertPolicy()

        assertFalse(policy.shouldAlert(181, 1_000L))
        assertTrue(policy.shouldAlert(181, 4_000L))
        assertFalse(policy.shouldAlert(190, 8_000L))
        assertFalse(policy.shouldAlert(181, 12_000L))
    }

    @Test fun readingAtOrBelowThresholdRearmsTheNextEpisode() {
        val policy = HeartRateAlertPolicy()

        assertFalse(policy.shouldAlert(181, 1_000L))
        assertTrue(policy.shouldAlert(181, 4_000L))
        assertFalse(policy.shouldAlert(180, 4_500L))
        assertFalse(policy.shouldAlert(182, 5_000L))
        assertTrue(policy.shouldAlert(182, 8_000L))
    }

    @Test fun briefHighReadingDoesNotCountTowardALaterEpisode() {
        val policy = HeartRateAlertPolicy()

        assertFalse(policy.shouldAlert(190, 1_000L))
        assertFalse(policy.shouldAlert(170, 3_000L))
        assertFalse(policy.shouldAlert(190, 3_500L))
        assertFalse(policy.shouldAlert(190, 6_499L))
        assertTrue(policy.shouldAlert(190, 6_500L))
    }

    @Test fun explicitResetClearsAnInProgressEpisode() {
        val policy = HeartRateAlertPolicy()

        assertFalse(policy.shouldAlert(190, 1_000L))
        policy.reset()
        assertFalse(policy.shouldAlert(190, 4_000L))
        assertTrue(policy.shouldAlert(190, 7_000L))
    }
}
