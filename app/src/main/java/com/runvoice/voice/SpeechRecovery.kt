package com.runvoice.voice

internal data class SpeechRequest(
    val text: String,
    val utteranceId: String,
    val priority: Boolean,
    val retryCount: Int = 0,
    val recoveryStatus: Boolean = false,
)

/** Bounded queues keep a long TTS outage from replaying hours of stale workout prompts. */
internal class SpeechRequestQueue(
    private val maxRoutineRequests: Int = 6,
    private val maxPriorityRequests: Int = 6,
) {
    private val routineRequests = ArrayDeque<SpeechRequest>()
    private val priorityRequests = ArrayDeque<SpeechRequest>()

    init {
        require(maxRoutineRequests > 0)
        require(maxPriorityRequests > 0)
    }

    val isEmpty: Boolean
        get() = routineRequests.isEmpty() && priorityRequests.isEmpty()

    val routineSize: Int
        get() = routineRequests.size

    val prioritySize: Int
        get() = priorityRequests.size

    fun addLast(request: SpeechRequest): Boolean {
        val target = targetFor(request)
        val limit = limitFor(request)
        if (target.size >= limit) {
            if (request.priority) return false // Never evict an already queued safety prompt.
            target.removeFirst() // Routine workout stats favor the newest snapshot.
        }
        target.addLast(request)
        return true
    }

    fun addFirst(request: SpeechRequest) {
        val target = targetFor(request)
        val limit = limitFor(request)
        // One in-flight retry may temporarily exceed the normal cap. This preserves both the
        // failed safety prompt and every safety prompt that arrived while it was speaking.
        if (target.size > limit) target.removeLast()
        target.addFirst(request)
    }

    fun removeNext(): SpeechRequest? = when {
        priorityRequests.isNotEmpty() -> priorityRequests.removeFirst()
        routineRequests.isNotEmpty() -> routineRequests.removeFirst()
        else -> null
    }

    fun clearRoutine() = routineRequests.clear()

    fun clear() {
        routineRequests.clear()
        priorityRequests.clear()
    }

    private fun targetFor(request: SpeechRequest): ArrayDeque<SpeechRequest> =
        if (request.priority) priorityRequests else routineRequests

    private fun limitFor(request: SpeechRequest): Int =
        if (request.priority) maxPriorityRequests else maxRoutineRequests
}

internal class SpeechRecoveryBackoff(
    private val delaysMillis: LongArray = longArrayOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L),
) {
    private var failureCount = 0

    init {
        require(delaysMillis.isNotEmpty())
        require(delaysMillis.all { it >= 0L })
    }

    fun nextDelayMillis(): Long {
        val delay = delaysMillis[failureCount.coerceAtMost(delaysMillis.lastIndex)]
        failureCount++
        return delay
    }

    fun reset() {
        failureCount = 0
    }
}

/** Initialization is not proof of recovery; only a completed utterance resets outage backoff. */
internal data class SpeechRecoveryCompletion(
    val outageEnded: Boolean,
    val requestFreshSnapshot: Boolean,
)

internal class SpeechRecoveryEpisode(
    private val backoff: SpeechRecoveryBackoff = SpeechRecoveryBackoff(),
) {
    var awaitingUtteranceConfirmation: Boolean = false
        private set

    private var everReady = false

    fun onEngineReady() {
        if (!everReady) backoff.reset()
        everReady = true
    }

    fun onEngineFailure() {
        if (everReady) awaitingUtteranceConfirmation = true
    }

    fun onUtteranceCompleted(recoveryStatus: Boolean = false): SpeechRecoveryCompletion {
        if (!awaitingUtteranceConfirmation) {
            return SpeechRecoveryCompletion(outageEnded = false, requestFreshSnapshot = false)
        }
        awaitingUtteranceConfirmation = false
        backoff.reset()
        return SpeechRecoveryCompletion(
            outageEnded = true,
            requestFreshSnapshot = !recoveryStatus,
        )
    }

    fun nextDelayMillis(): Long = backoff.nextDelayMillis()
}
