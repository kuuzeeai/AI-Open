package ai.opencode.app

import ai.opencode.app.assistant.AssistantSessionTarget
import ai.opencode.app.assistant.AssistantSessionTarget.Pick
import ai.opencode.core.experiments.AssistantExperiment.SessionChoice
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Android only: which session a new assistant call goes to (Settings › Assistant › Session). */
class AssistantSessionTargetTests {
    @get:Rule val isolation = TestIsolationRule()

    private val directory = SessionDirectory(
        currentServerKey = "key",
        servers = mapOf(
            "key" to SessionDirectory.Server(
                key = "key",
                name = "Laptop",
                lastViewedSessionID = "ses_viewed",
                pinnedIDs = emptyList(),
                sessions = listOf(SessionDirectory.Session("ses_viewed", "Viewed", "p_recent", updatedAt = Instant.EPOCH)),
                projects = listOf(
                    SessionDirectory.Project("p_recent", "recent", "/work/recent"),
                    SessionDirectory.Project("p_old", "old", "/work/old"),
                ),
            ),
        ),
    )
    private val pinned = Pick.Existing("key", "ses_pinned", "/work/old")

    @Test fun newSessionGoesToTheMostRecentProject() {
        val pick = AssistantSessionTarget.pick(SessionChoice.newSession, pinned = null, directory)
        assertEquals(Pick.New("key", "/work/recent"), pick)
        assertEquals(SystemAction.Destination.Draft(projectID = null), AssistantSessionTarget.destination(pick!!))
    }

    @Test fun mostRecentContinuesTheLastViewedSession() {
        val pick = AssistantSessionTarget.pick(SessionChoice.mostRecent, pinned = null, directory)
        assertEquals(Pick.Recent("key"), pick)
        assertEquals(SystemAction.Destination.LastViewed, AssistantSessionTarget.destination(pick!!))
    }

    @Test fun pinnedGoesToThePinnedSession() {
        val pick = AssistantSessionTarget.pick(SessionChoice.pinned, pinned, directory)
        assertEquals(pinned, pick)
        assertEquals(SystemAction.Destination.Session("ses_pinned"), AssistantSessionTarget.destination(pick!!))
    }

    @Test fun aMissingPinStartsANewSession() {
        val pick = AssistantSessionTarget.pick(SessionChoice.pinned, pinned = null, directory)
        assertEquals(Pick.New("key", "/work/recent"), pick)
    }

    @Test fun noServerPicksNothing() {
        assertNull(AssistantSessionTarget.pick(SessionChoice.newSession, pinned = null, SessionDirectory()))
    }

    @Test fun anUnknownSavedChoiceMeansNewSession() {
        assertEquals(SessionChoice.newSession, SessionChoice.fromRawValue("gone"))
        assertEquals(SessionChoice.pinned, SessionChoice.fromRawValue("pinned"))
    }
}
