package com.runvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeyPolicyTest {

    private fun VolumeKeyPolicy.headsetPress(volume: Int, nowMillis: Long) =
        onVolumeChanged(volume = volume, screenInteractive = false, runActive = true, nowMillis = nowMillis)

    @Test fun headsetPressWithScreenOffAnnouncesAndRestoresTheLockedLevel() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)

        val decision = policy.headsetPress(132, 1_000L)

        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(140), decision)
    }

    @Test fun volumeUpAlsoAnnouncesSoEitherHeadsetKeyWorks() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)

        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(140), policy.headsetPress(149, 1_000L))
    }

    @Test fun repeatedPressesRestoreWithoutSpeakingAgain() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)

        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(140), policy.headsetPress(132, 1_000L))
        assertEquals(VolumeKeyPolicy.Decision.RestoreOnly(140), policy.headsetPress(132, 1_400L))
        assertEquals(VolumeKeyPolicy.Decision.RestoreOnly(140), policy.headsetPress(123, 2_400L))
        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(140), policy.headsetPress(132, 2_500L))
    }

    @Test fun ourOwnRestoreIsNotMistakenForAnotherPress() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)

        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(140), policy.headsetPress(132, 1_000L))
        policy.noteSelfWrite(140, 1_010L)

        assertEquals(VolumeKeyPolicy.Decision.Ignore, policy.headsetPress(140, 1_050L))
    }

    @Test fun aStaleSelfWriteNoLongerSuppressesRealPresses() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)
        policy.noteSelfWrite(132, 1_000L)

        // The echo never arrived; the same level much later is a genuine press.
        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(140), policy.headsetPress(132, 3_000L))
    }

    @Test fun screenOnMeansTheUserIsAdjustingVolumeOnPurpose() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)

        val decision = policy.onVolumeChanged(
            volume = 90,
            screenInteractive = true,
            runActive = true,
            nowMillis = 1_000L
        )

        assertEquals(VolumeKeyPolicy.Decision.AdoptAsLock(90), decision)
        // The new level is what later headset presses restore.
        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(90), policy.headsetPress(82, 2_000L))
    }

    @Test fun withoutAnActiveRunTheVolumeIsLeftAlone() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)

        val decision = policy.onVolumeChanged(
            volume = 100,
            screenInteractive = false,
            runActive = false,
            nowMillis = 1_000L
        )

        assertEquals(VolumeKeyPolicy.Decision.AdoptAsLock(100), decision)
    }

    @Test fun aDisarmedPolicyNeverTouchesVolume() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)
        policy.disarm()

        assertFalse(policy.isArmed)
        assertEquals(VolumeKeyPolicy.Decision.Ignore, policy.headsetPress(132, 1_000L))
    }

    @Test fun anUnchangedVolumeIsNotAPress() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)

        assertEquals(VolumeKeyPolicy.Decision.Ignore, policy.headsetPress(140, 1_000L))
    }

    @Test fun absoluteVolumeRoundingDriftIsNotAPress() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)

        // A restore can come back off by a step or two once it has round-tripped through the
        // headset's 0-127 scale; that must not look like another press and start an oscillation.
        assertEquals(VolumeKeyPolicy.Decision.Ignore, policy.headsetPress(139, 1_000L))
        assertEquals(VolumeKeyPolicy.Decision.Ignore, policy.headsetPress(142, 1_100L))
        // A real press moves a full step.
        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(140), policy.headsetPress(132, 1_200L))
    }

    @Test fun armingResetsTheDebounceSoTheFirstPressOfARunAlwaysSpeaks() {
        val policy = VolumeKeyPolicy()
        policy.arm(140)
        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(140), policy.headsetPress(132, 1_000L))

        policy.arm(140)
        assertTrue(policy.isArmed)
        assertEquals(VolumeKeyPolicy.Decision.AnnounceAndRestore(140), policy.headsetPress(132, 1_100L))
    }
}
