package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.voice.AssistantPanelClient
import ai.opencode.feature.voice.PhoneTools
import ai.opencode.feature.voice.PhoneToolsClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.ClientToolsTarget
import ai.opencode.server.clienttools.JsonRpcID
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Android only: which session the side-button assistant's phone tools serve (no iOS counterpart). */
class AssistantClientToolsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private val server = OpenCodeServer(url = "https://studio.example.ts.net:4096", username = "opencode", password = "pw")
    private val session = OpenCodeSession(
        id = "ses_phone", serverTitle = "Phone tools", projectID = "proj", directory = "/work/app",
        updatedAt = Instant.ofEpochSecond(1_700_000_000),
    )
    private val phone = phoneClientTools

    private fun app(busy: Set<String> = emptySet()): AppFeature.State = shared.run {
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = busy))
        AppFeature.State.initial().copy(home = home.copy(sessionDetail = SessionFeature.State.session(server, session)))
    }

    private fun AppFeature.State.withVoice(voice: VoiceFeature.State): AppFeature.State {
        val home = home ?: return this
        val detail = home.sessionDetail ?: return this
        return copy(home = home.copy(sessionDetail = detail.copy(voice = voice)))
    }

    private fun voice(phase: VoiceFeature.State.Phase, assistant: Boolean): VoiceFeature.State =
        shared.run { VoiceFeature.State(server, session).withPhase(phase).copy(isAssistantCall = assistant) }

    @Test fun anOpenPanelOffersThePhoneToolsWithoutACall() {
        val state = app().copy(assistantScope = AssistantScope("ses_phone", "/work/app"))
        assertEquals(AssistantScope("ses_phone", "/work/app"), state.assistantSession)
        assertEquals(ClientToolsTarget(server, "/work/app", phone), state.clientToolsTarget)
    }

    @Test fun aClosedPanelKeepsThemUntilTheTurnIsOver() {
        val closed = AssistantScope("ses_phone", "/work/app", isOpen = false, inGrace = false)
        assertNull(app().copy(assistantScope = closed).clientToolsTarget)
        assertEquals(ClientToolsTarget(server, "/work/app", phone), app().copy(assistantScope = closed.copy(inGrace = true)).clientToolsTarget)
        assertEquals(ClientToolsTarget(server, "/work/app", phone), app(busy = setOf("ses_phone")).copy(assistantScope = closed).clientToolsTarget)
    }

    @Test fun onlyTheAssistantsCallGetsThem() {
        val assistantCall = app().withVoice(voice(VoiceFeature.State.Phase.Live, assistant = true))
        assertEquals("ses_phone", assistantCall.assistantSession?.sessionID)
        val target = assistantCall.clientToolsTarget
        assertEquals("/work/app", target?.directory)
        assertEquals(true, target?.tools?.containsAll(phone))

        // A call started in the app isn't the assistant's: no phone tools.
        val inApp = app().withVoice(voice(VoiceFeature.State.Phase.Live, assistant = false))
        assertNull(inApp.assistantSession)
        assertEquals(false, inApp.clientToolsTarget?.tools.orEmpty().any { it.isPhoneTool })
    }

    @Test fun allCallsOffersThemToInAppCallsWithTheScreen() {
        val inApp = app().copy(phoneToolsInAllCalls = true).withVoice(voice(VoiceFeature.State.Phase.Live, assistant = false))
        assertEquals(AssistantScope("ses_phone", "/work/app"), inApp.assistantSession)
        assertTrue(inApp.clientToolsTarget?.tools.orEmpty().containsAll(phone))

        // A side-button call keeps every tool, the screen included, under either setting.
        val assistantCall = app().copy(phoneToolsInAllCalls = true).withVoice(voice(VoiceFeature.State.Phase.Live, assistant = true))
        assertTrue(assistantCall.clientToolsTarget?.tools.orEmpty().containsAll(phone))

        // An ended call gets nothing, and Assistant Calls Only (the default) leaves in-app calls out.
        assertNull(app().copy(phoneToolsInAllCalls = true).withVoice(voice(VoiceFeature.State.Phase.Ended, assistant = false)).assistantSession)
        assertNull(app().withVoice(voice(VoiceFeature.State.Phase.Live, assistant = false)).assistantSession)
    }

    @Test fun phoneToolsAreTheSpecsEntries() {
        assertEquals(phone, ClientTool.entries.filter { it.isPhoneTool }.toSet())
        assertEquals(40, phone.size)
        assertTrue(ClientTool.sendText.description.startsWith("Text message (SMS)"))
        assertEquals("set_timer", ClientTool.setTimer.rawValue)
        assertEquals(listOf("seconds"), ClientTool.setTimer.inputSchema["required"].toString().removeSurrounding("[", "]").split(",").map { it.trim('"') })
    }

    @Test fun anotherSessionsCallIsRefusedAndTheAssistantsRuns() = runTest {
        val connection = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val target = ClientToolsTarget(server, "/work/app", phone)
        val state = app().copy(
            assistantScope = AssistantScope("ses_phone", "/work/app"),
            clientTools = ClientToolsFeature.State(
                target = target,
                status = ClientToolsFeature.State.Status.Attached,
                connection = connection,
            ),
        )
        val replies = Locked<List<ClientToolResult>>(emptyList())
        val ran = Locked<List<PhoneTools.Call>>(emptyList())
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(state, effects) {
            this[Clock] = ImmediateClock()
            this[ClientToolsClient] = ClientToolsClient(
                connect = { emptyFlow() },
                reply = { _, result ->
                    replies.update { it + result }
                    true
                },
            )
            this[PhoneToolsClient] = PhoneToolsClient(
                run = { call ->
                    ran.update { it + call }
                    PhoneTools.Result("Timer set for 5 minutes.")
                },
            )
            this[AssistantPanelClient] = AssistantPanelClient(confirm = { false }, acted = {})
        }
        val arguments = buildJsonObject { put("seconds", JsonPrimitive(300)) }
        fun call(id: Long, sessionID: String) = ClientToolCall(
            connection = connection, id = JsonRpcID.Number(id), tool = ClientTool.setTimer,
            sessionID = sessionID, arguments = arguments,
        )

        val elsewhere = call(1, "ses_other")
        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(elsewhere))))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Refuse(elsewhere, PhoneTools.NOT_HERE)))

        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(call(2, "ses_phone")))))
        eventually { replies.value.size == 2 }
        effects.skipInFlightEffects()

        assertEquals(listOf(PhoneTools.Call.SetTimer(300, null)), ran.value)
        assertEquals(
            listOf(
                ClientToolResult.failure(PhoneTools.NOT_HERE),
                ClientToolResult(listOf(ClientToolResult.Content.Text("Timer set for 5 minutes.")), isError = false),
            ),
            replies.value,
        )
    }
}
