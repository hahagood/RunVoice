package com.runvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementPolicyTest {
    @Test fun emitsQuarterPaceOnceAndKilometerAtBoundary() {
        val policy = AnnouncementPolicy()
        assertEquals(listOf(AnnouncementEvent.CurrentPace(360)), policy.eventsFor(250f, 360))
        assertTrue(policy.eventsFor(260f, 360).isEmpty())
        assertEquals(listOf(AnnouncementEvent.CurrentPace(355)), policy.eventsFor(750f, 355))
        assertEquals(listOf(AnnouncementEvent.Kilometer(1)), policy.eventsFor(1_000f, 350))
    }

    @Test fun waitsForValidPaceWithoutLosingQuarterBoundary() {
        val policy = AnnouncementPolicy()
        assertTrue(policy.eventsFor(250f, 0).isEmpty())
        assertEquals(listOf(AnnouncementEvent.CurrentPace(400)), policy.eventsFor(260f, 400))
    }

    @Test fun resetAllowsANewSessionToAnnounceAgain() {
        val policy = AnnouncementPolicy()
        policy.eventsFor(1_000f, 360)
        policy.reset()
        assertEquals(listOf(AnnouncementEvent.Kilometer(1)), policy.eventsFor(1_000f, 360))
    }
}
