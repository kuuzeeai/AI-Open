package ai.opencode.app.assistant

import ai.opencode.core.experiments.AssistantExperiment
import ai.opencode.core.experiments.AssistantExperiment.SessionChoice
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.feature.system.ScopedID
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemAction
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeSession
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android only: the session a new assistant call or typed message goes to, from Settings ›
 * Assistant › Session. A running assistant call keeps its own session; this picks one only when
 * a press starts something new.
 */
object AssistantSessionTarget {
    sealed interface Pick {
        val serverKey: String

        /** A session that exists. [loaded] is set when it was fetched by ID (a pinned session). */
        data class Existing(
            override val serverKey: String,
            val sessionID: String,
            val directory: String?,
            val loaded: OpenCodeSession? = null,
        ) : Pick

        /** The last session viewed in the app (else the newest), as before the choice existed. */
        data class Recent(override val serverKey: String) : Pick

        /** A fresh session in the most recently used project, made when it's first needed. */
        data class New(override val serverKey: String, val directory: String?) : Pick
    }

    /** How long a press waits to load the pinned session before starting a new one instead. */
    private val pinBudget = 4.seconds

    /**
     * Picks from the choice alone. [pinned] is the pinned session when it loaded, else null: a
     * pin that's gone (or was never set) starts a new session.
     */
    fun pick(choice: SessionChoice, pinned: Pick.Existing?, directory: SessionDirectory): Pick? {
        val key = directory.currentServerKey ?: return null
        when (choice) {
            SessionChoice.mostRecent -> return Pick.Recent(key)
            SessionChoice.pinned -> if (pinned != null) return pinned
            SessionChoice.newSession -> Unit
        }
        val project = directory.current?.projects?.firstOrNull()
        return Pick.New(key, project?.directory)
    }

    /** The call's destination for a pick. */
    fun destination(pick: Pick): SystemAction.Destination = when (pick) {
        is Pick.Existing -> SystemAction.Destination.Session(pick.sessionID, loaded = pick.loaded)
        is Pick.Recent -> SystemAction.Destination.LastViewed
        // Home's draft goes to the most recently used project.
        is Pick.New -> SystemAction.Destination.Draft(projectID = null)
    }

    /** The saved choice, loading the pinned session (briefly) to check it's still there. */
    suspend fun resolve(directory: SessionDirectory, client: OpenCodeClient): Pick? {
        val choice = SessionChoice.fromRawValue(shared(AssistantExperiment.sessionChoice).value)
        var pinned: Pick.Existing? = null
        if (choice == SessionChoice.pinned) {
            pinned = loadPinned(client)
            shared(AssistantExperiment.pinnedSessionMissing).set(pinned == null)
            if (pinned == null) AppLog.app.notice("Assistant couldn’t load its pinned session; starting a new one")
        }
        val pick = pick(choice, pinned, directory)
        val sessionID = (pick as? Pick.Existing)?.sessionID ?: "none"
        AppLog.app.info { "Assistant session mode=${choice.rawValue} session=$sessionID" }
        return pick
    }

    private suspend fun loadPinned(client: OpenCodeClient): Pick.Existing? {
        val scoped = ScopedID.parse(shared(AssistantExperiment.pinnedSession).value) ?: return null
        val server = server(scoped.serverKey) ?: return null
        val session = try {
            withTimeoutOrNull(pinBudget) { client.getSession(server, scoped.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        return Pick.Existing(scoped.serverKey, session.id, session.directory, loaded = session)
    }

    /** A saved server by its directory key. */
    fun server(key: String): OpenCodeServer? {
        val servers = shared(ServerSharedKeys.savedServers).value + listOfNotNull(shared(ServerSharedKeys.savedServer).value)
        for (server in servers) {
            if (SessionDirectory.serverKey(server.persistenceID) == key) return server
        }
        return null
    }
}
