package com.runvoice.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecoveryTest {
    @Test fun failedRequestCanBeRetriedAheadOfRoutineQueue() {
        val queue = SpeechRequestQueue()
        val failed = request("km_70")
        queue.addLast(failed)
        queue.addLast(request("pace_1"))

        assertEquals(failed, queue.removeNext())
        queue.addFirst(failed.copy(retryCount = 1))

        val retried = queue.removeNext()
        assertEquals("km_70", retried?.utteranceId)
        assertEquals(1, retried?.retryCount)
        assertEquals("pace_1", queue.removeNext()?.utteranceId)
    }

    @Test fun recoveryCanDropStaleRoutinePromptsButKeepSafetyPrompts() {
        val queue = SpeechRequestQueue()
        queue.addLast(request("km_70"))
        queue.addLast(request("pace_1"))
        queue.addLast(request("tracking_alert", priority = true))

        queue.clearRoutine()

        assertEquals("tracking_alert", queue.removeNext()?.utteranceId)
        assertTrue(queue.isEmpty)
    }

    @Test fun outageQueueIsBoundedAndKeepsTheNewestRoutinePrompts() {
        val queue = SpeechRequestQueue(maxRoutineRequests = 3)
        (1..5).forEach { queue.addLast(request("pace_$it")) }

        assertEquals(3, queue.routineSize)
        assertEquals("pace_3", queue.removeNext()?.utteranceId)
        assertEquals("pace_4", queue.removeNext()?.utteranceId)
        assertEquals("pace_5", queue.removeNext()?.utteranceId)
    }

    @Test fun failedSafetyRetryDoesNotEvictQueuedSafetyPrompts() {
        val queue = SpeechRequestQueue(maxPriorityRequests = 2)
        queue.addLast(request("safety_1", priority = true))
        queue.addLast(request("safety_2", priority = true))
        queue.addFirst(request("failed_safety", priority = true).copy(retryCount = 1))
        queue.addLast(request("new_safety_while_full", priority = true))

        assertEquals(3, queue.prioritySize)
        assertEquals("failed_safety", queue.removeNext()?.utteranceId)
        assertEquals("safety_1", queue.removeNext()?.utteranceId)
        assertEquals("safety_2", queue.removeNext()?.utteranceId)
    }

    @Test fun recoveryBackoffIncreasesAndCapsUntilReset() {
        val backoff = SpeechRecoveryBackoff(longArrayOf(1L, 2L, 5L))

        assertEquals(listOf(1L, 2L, 5L, 5L), List(4) { backoff.nextDelayMillis() })
        backoff.reset()
        assertEquals(1L, backoff.nextDelayMillis())
    }

    @Test fun successfulRebindDoesNotResetBackoffUntilSpeechActuallyCompletes() {
        val episode = SpeechRecoveryEpisode(
            SpeechRecoveryBackoff(longArrayOf(1L, 2L, 5L))
        )
        episode.onEngineReady()
        episode.onEngineFailure()

        assertEquals(1L, episode.nextDelayMillis())
        episode.onEngineReady() // Binding works, but synthesis still fails.
        assertEquals(2L, episode.nextDelayMillis())
        episode.onEngineReady()
        val completion = episode.onUtteranceCompleted()
        assertTrue(completion.outageEnded)
        assertTrue(completion.requestFreshSnapshot)
        assertEquals(1L, episode.nextDelayMillis())
    }

    @Test fun successfulRecoverySnapshotDoesNotRequestAnotherSnapshot() {
        val episode = SpeechRecoveryEpisode()
        episode.onEngineReady()
        episode.onEngineFailure()

        val completion = episode.onUtteranceCompleted(recoveryStatus = true)

        assertTrue(completion.outageEnded)
        assertTrue(!completion.requestFreshSnapshot)
    }

    private fun request(id: String, priority: Boolean = false) = SpeechRequest(
        text = id,
        utteranceId = id,
        priority = priority,
    )
}
