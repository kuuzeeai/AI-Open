package ai.opencode.core.experiments

import ai.opencode.core.sharing.SharedKey

/**
 * Android only (iOS can't replace Siri): OpenCode as the phone's digital assistant. Holding the
 * side button starts a call, and calls get phone tools (timers, alarms, opening apps, reading
 * the screen). Its switch sits in Settings › Experimental below iOS's groups rather than in
 * [ExperimentalFeature], whose list mirrors iOS's. Off by default, and All Experiments leaves
 * it alone because it reads the screen.
 */
object AssistantExperiment {
    val key: SharedKey<Boolean> = SharedKey.appStorage("experimental-assistant", false)
    const val TITLE = "Assistant"
    const val SYMBOL = "mic.fill"

    /**
     * Settings › Assistant › Phone Tools: on, every voice call started in the app also offers the
     * phone's tools to its session; off, only side-button calls do.
     */
    val phoneToolsInAllCalls: SharedKey<Boolean> = SharedKey.appStorage("assistant-phone-tools-in-all-calls", false)

    /**
     * Settings › Assistant › Permissions › Make Calls: the session may place phone calls without the
     * panel asking first.
     */
    val callsWithoutAsking: SharedKey<Boolean> = SharedKey.appStorage("assistant-calls-without-asking", false)

    /** Settings › Assistant › Permissions › Send Texts: the session may send texts without the panel asking first. */
    val textsWithoutAsking: SharedKey<Boolean> = SharedKey.appStorage("assistant-texts-without-asking", false)

    /**
     * Settings › Assistant › Permissions › Read Notifications: the session may read notifications
     * without the panel asking first.
     */
    val readsNotificationsWithoutAsking: SharedKey<Boolean> =
        SharedKey.appStorage("assistant-reads-notifications-without-asking", false)

    /** Settings › Assistant › Session: which session a new assistant call or message goes to. */
    enum class SessionChoice(val rawValue: String, val title: String) {
        /** A fresh session in the most recently used project for each new call. */
        newSession("new", "New Session"),
        /** The last session viewed in the app. */
        mostRecent("recent", "Most Recent Session"),
        /** One session the user picked, kept until they pick another. */
        pinned("pinned", "Pinned Session");

        companion object {
            fun fromRawValue(raw: String): SessionChoice {
                for (choice in entries) {
                    if (choice.rawValue == raw) return choice
                }
                return newSession
            }
        }
    }

    /** A [SessionChoice]'s raw value; New Session by default. */
    val sessionChoice: SharedKey<String> = SharedKey.appStorage("assistant-session-choice", SessionChoice.newSession.rawValue)

    /** The pinned session as `<server key>:<session ID>` (a `ScopedID`); empty when none is picked. */
    val pinnedSession: SharedKey<String> = SharedKey.appStorage("assistant-pinned-session", "")

    /** The pinned session's title, for its Settings row only. */
    val pinnedSessionTitle: SharedKey<String> = SharedKey.appStorage("assistant-pinned-session-title", "")

    /** The last call couldn't load the pinned session and started a new one; Settings says so. */
    val pinnedSessionMissing: SharedKey<Boolean> = SharedKey.appStorage("assistant-pinned-session-missing", false)

    /**
     * Holding the side button has opened the assistant at least once. Settings › Assistant shows
     * Side Button as set from this where the phone's own setting can't be read (Samsung).
     */
    val sideButtonUsed: SharedKey<Boolean> = SharedKey.appStorage("assistant-side-button-used", false)
}
