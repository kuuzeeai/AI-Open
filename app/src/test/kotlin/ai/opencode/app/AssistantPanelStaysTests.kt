package ai.opencode.app

import ai.opencode.app.assistant.AssistantFlow
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.AssistantCallPanel
import ai.opencode.feature.voice.VoiceCallCommands
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** A call started from the side button always keeps the panel or the pill on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantPanelStaysTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun theLastCallsEndDoesNotCloseThePanelOfTheNextCall() = runTest {
        val commands = VoiceCallCommands()
        val flow = AssistantFlow(backgroundScope)
        withDependencies({ this[VoiceCallCommands] = commands }) {
            commands.activate(UUID.randomUUID())
            flow.showCallInProgress()
            flow.callEnded(failed = false)
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(flow.state.value.finished)
    }

    @Test
    fun finishingDuringALiveCallKeepsThePill() = runTest {
        val commands = VoiceCallCommands()
        val flow = AssistantFlow(backgroundScope)
        withDependencies({ this[VoiceCallCommands] = commands }) {
            commands.activate(UUID.randomUUID())
            flow.showCallInProgress()
            flow.finish()
        }
        assertFalse(flow.state.value.finished)
        assertEquals(AssistantCallPanel.Form.pill, flow.state.value.form)
    }

    @Test
    fun theEndClosesThePanelOnceTheCallIsGone() = runTest {
        val commands = VoiceCallCommands()
        val flow = AssistantFlow(backgroundScope)
        val call = UUID.randomUUID()
        withDependencies({ this[VoiceCallCommands] = commands }) {
            commands.activate(call)
            flow.showCallInProgress()
            commands.deactivate(call)
            flow.callEnded(failed = false)
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(flow.state.value.finished)
    }
}
