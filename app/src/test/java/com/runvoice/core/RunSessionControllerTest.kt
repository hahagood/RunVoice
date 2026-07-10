package com.runvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSessionControllerTest {
    @Test fun validLifecycleTransitionsInOrder() {
        val controller = RunSessionController()
        assertTrue(controller.dispatch(RunCommand.Start).accepted)
        assertTrue(controller.dispatch(RunCommand.Pause).accepted)
        assertTrue(controller.dispatch(RunCommand.Resume).accepted)
        assertTrue(controller.dispatch(RunCommand.BeginFinish).accepted)
        assertTrue(controller.dispatch(RunCommand.CompleteFinish).accepted)
        assertEquals(RunSessionState.Finished, controller.state)
    }

    @Test fun repeatedAndLateCommandsCannotResetOrReviveSession() {
        val controller = RunSessionController()
        controller.dispatch(RunCommand.Start)
        assertFalse(controller.dispatch(RunCommand.Start).accepted)
        assertEquals(RunSessionState.Running, controller.state)
        controller.dispatch(RunCommand.BeginFinish)
        controller.dispatch(RunCommand.CompleteFinish)
        assertFalse(controller.dispatch(RunCommand.Resume).accepted)
        assertEquals(RunSessionState.Finished, controller.state)
    }
}
