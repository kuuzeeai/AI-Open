package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.server.ServerSharedKeys
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android only: whether a call started from the side button would reach a voice model, so the
 * assistant can offer setup instead of a call that fails. Voice has to be on in Settings, and
 * the route a call would take ([VoiceBroker.route], as [startCall] picks it) has to be
 * available. An unknown server is asked once.
 */
object AssistantVoiceReadiness {
    suspend fun isReady(): Boolean {
        if (VoiceLabFlags.isEnabled || VoiceLabFlags.hasStandInServer || VoiceLabFlags.labAPIKey != null) return true
        if (!shared(SharedKeys.voiceModeEnabled).value) return false
        val connected = shared(ServerSharedKeys.savedServer).value
        val engine = VoiceLabFlags.overrideEngine ?: shared(VoiceSharedKeys.voiceEngine).value
        val openAIKey = shared(SharedKeys.openAIAPIKey).value?.trim() ?: ""
        val xaiKey = shared(SharedKeys.xaiAPIKey).value?.trim() ?: ""
        val supportByServer = shared(VoiceSharedKeys.serverVoiceSupportByServer).value
        val (broker, _) = VoiceBroker.resolve(
            choice = shared(VoiceSharedKeys.voiceServerChoice).value,
            provider = shared(VoiceSharedKeys.serverVoiceProvider).value,
            connected = connected,
            saved = VoiceFeature.savedServers,
            answers = shared(VoiceSharedKeys.voiceModelsByServer).value,
            support = supportByServer,
            hasOpenAIKey = openAIKey.isNotEmpty(),
            engine = engine,
        )
        var support = broker.support(supportByServer)
        val brokerServer = broker.server
        if (broker.isEnabled && engine != VoiceEngine.grok && brokerServer != null && support != ServerVoiceSupport.supported) {
            support = try {
                dependency(ServerVoiceClient).checkSupport(brokerServer)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                ServerVoiceSupport.unknown
            }
        }
        val route = broker.route(engine, support, hasOpenAIKey = openAIKey.isNotEmpty(), hasXAIKey = xaiKey.isNotEmpty())
        return route !is VoiceRoute.Unavailable
    }
}
