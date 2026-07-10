package com.runvoice.core

enum class RunSessionState {
    Idle,
    Running,
    Paused,
    Finishing,
    Finished
}

enum class RunCommand {
    Start,
    Pause,
    Resume,
    BeginFinish,
    CompleteFinish
}

data class RunTransition(
    val previous: RunSessionState,
    val current: RunSessionState,
    val accepted: Boolean
)

/** Serial, platform-independent owner of valid run-session transitions. */
class RunSessionController(initialState: RunSessionState = RunSessionState.Idle) {
    var state: RunSessionState = initialState
        private set

    @Synchronized
    fun dispatch(command: RunCommand): RunTransition {
        val previous = state
        val next = when (command) {
            RunCommand.Start -> when (state) {
                RunSessionState.Idle, RunSessionState.Finished -> RunSessionState.Running
                else -> null
            }
            RunCommand.Pause -> RunSessionState.Paused.takeIf { state == RunSessionState.Running }
            RunCommand.Resume -> RunSessionState.Running.takeIf { state == RunSessionState.Paused }
            RunCommand.BeginFinish -> RunSessionState.Finishing.takeIf {
                state == RunSessionState.Running || state == RunSessionState.Paused
            }
            RunCommand.CompleteFinish -> RunSessionState.Finished.takeIf { state == RunSessionState.Finishing }
        }
        if (next != null) state = next
        return RunTransition(previous, state, next != null)
    }
}
