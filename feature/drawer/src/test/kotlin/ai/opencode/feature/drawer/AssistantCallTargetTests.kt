package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.VoiceHomeTestSupport.live
import ai.opencode.feature.drawer.VoiceHomeTestSupport.openingScreens
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceCall
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceHomeTest
import ai.opencode.feature.drawer.VoiceHomeTestSupport.withCall
import ai.opencode.feature.drawer.VoiceHomeTestSupport.copy
import ai.opencode.feature.drawer.VoiceHomeTestSupport.eventually
import ai.opencode.feature.drawer.VoiceHomeTestSupport.receiveVoice
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Android only: an assistant call (the side button) stays with its session while the user browses. */
class AssistantCallTargetTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val distantPast: Instant = HomeFeatureTestSupport.distantPast
    private val a = OpenCodeSession(id = "ses_assistant", serverTitle = "Timers", projectID = "p", directory = "/Users/kit/a", updatedAt = distantPast)
    private val b = OpenCodeSession(id = "ses_other", serverTitle = "Docs", projectID = "p", directory = "/Users/kit/b", updatedAt = distantPast)
    private val project = OpenCodeProject(id = "p", name = "project", directory = "/Users/kit/a")

    private fun run(isAssistantCall: Boolean): String? {
        var target: String? = null
        voiceHomeTest { h ->
            val home = h.state {
                var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(a, b), activeSessionIDs = emptySet()))
                home = home.openingDetail(a)
                val voice = VoiceFeature.State(server, a).live()
                    .copy(activeRoute = VoiceRoute.PhoneKey(VoiceEngine.realtime), isAssistantCall = isAssistantCall)
                home.withCall(voice)
            }
            val store = h.store(home) {
                this[Clock] = ImmediateClock()
                voiceCall { copy(updateInstructions = { _, _ -> }) }
                this[OpenCodeClient] = OpenCodeClient.testValue.openingScreens { id -> if (id == b.id) b else a }
            }
            store.exhaustive = false
            store.send(HomeFeature.Action.SessionTapped(b.id))
            if (isAssistantCall) {
                // Long enough for an in-app call to have moved.
                eventually { store.state.sessionDetail?.session?.id == b.id }
                delay(300)
                store.skipReceivedActions()
            } else {
                store.receiveVoice(VoiceFeature.Action.TargetChanged::class)
            }
            target = store.state.sessionDetail?.voice?.target?.sessionID
            store.skipInFlightEffects()
        }
        return target
    }

    @Test fun anAssistantCallStaysWithItsSessionWhenTheUserOpensAnother() {
        assertEquals(a.id, run(isAssistantCall = true))
    }

    @Test fun anInAppCallStillFollowsTheScreen() {
        assertEquals(b.id, run(isAssistantCall = false))
    }
}
