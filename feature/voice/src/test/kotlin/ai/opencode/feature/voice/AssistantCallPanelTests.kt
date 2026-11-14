package ai.opencode.feature.voice

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** What TalkBack reads for the assistant bar's main area, the one element that opens the call in OpenCode. */
class AssistantCallPanelTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun barLabelReadsTheStatusTimeAndLine() {
        assertEquals(
            "OpenCode call, Speaking, 0:42. Opening Maps",
            AssistantCallPanel.barLabel("Speaking", 42, "Opening Maps"),
        )
    }

    @Test
    fun barLabelPadsSecondsPastAMinute() {
        assertEquals(
            "OpenCode call, Listening, 12:05. Say something",
            AssistantCallPanel.barLabel("Listening", 725, "Say something"),
        )
    }

    @Test
    fun barLabelLeavesOutTheTimeBeforeTheCallConnects() {
        assertEquals("OpenCode call, Connecting", AssistantCallPanel.barLabel("Connecting", null, ""))
    }
}
