package ai.opencode.feature.voice

/**
 * UI tests post these to start a call while something covers the call button. On Android a debug
 * build can deliver them as broadcasts with the same names.
 *
 * The `VoiceLabTriggers` view modifier (it sends `SessionFeature` actions: `voiceTapped`,
 * `raisedToEar`, and for [endCall]
 * `voice(.presented(.endPressed))`) moved to `:feature:session`, which owns that store.
 */
enum class VoiceLabTrigger(val rawValue: String) {
    startCall("ai.opencode.lab.voice-start"),

    /** Raise to Talk's detector firing: the emulator has no proximity sensor. */
    raiseToEar("ai.opencode.lab.voice-raise"),

    /** A hang-up from outside the call's own controls, such as the ongoing notification's End or the agent ending the call. */
    endCall("ai.opencode.lab.voice-end"),

    /** An agent's `android_voice_play_audio` (Audio in Calls), with the lab's silent player. */
    playAudio("ai.opencode.lab.voice-play-audio"),

    /**
     * Android only: an agent's `android_voice_request_screen` and `android_voice_open_url`, so the
     * emulator can show their cards (session-lab has no client MCP socket).
     */
    requestScreen("ai.opencode.lab.voice-request-screen"),
    openLink("ai.opencode.lab.voice-open-link"),

    /**
     * Android only: the call's `read_screen` phone tool, run from wherever the user is, so the
     * emulator can read the screen outside the app without a server (`PhoneToolLab` in `:app`).
     */
    readScreen("ai.opencode.lab.phone-read-screen"),

    /** Android only: any phone tool, named by the broadcast's `tool` extra with its `args` JSON (`PhoneToolLab` in `:app`). */
    phoneTool("ai.opencode.lab.phone-tool"),
    ;

    /** `Notification.Name("OpenCodeLab." + rawValue)`. */
    val notification: String get() = "OpenCodeLab.$rawValue"
}
