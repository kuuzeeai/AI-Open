package ai.opencode.app.assistant

import ai.opencode.app.AppFeature
import ai.opencode.app.AssistantScope
import ai.opencode.app.MainActivity
import ai.opencode.app.SceneWindows
import ai.opencode.app.clientToolsTarget
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.AssistantExperiment
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.SystemActionOrigin
import ai.opencode.core.sharing.shared
import ai.opencode.feature.system.ScopedID
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemActionInbox
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.system.intents.AskOpenCodeIntent
import ai.opencode.feature.system.intents.SystemActionActivity
import ai.opencode.feature.system.intents.SystemPromptSender
import ai.opencode.feature.voice.AssistantCallPanel
import ai.opencode.feature.voice.AssistantVoiceReadiness
import ai.opencode.feature.voice.PhoneTools
import ai.opencode.feature.voice.VoiceCallCommands
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerID
import ai.opencode.server.clienttools.ClientTool
import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the side button's panel is doing, for [OpenCodeAssistantSession] to show: working out
 * whether a call can start and starting it with the session Settings › Assistant › Session
 * picks (new, most recent or pinned), or explaining why not (turned off, no server, no voice
 * model) with a way forward; then following the call it started or found until it ends, through
 * OpenCode coming on screen (which shows the call its own way) and the phone locking. One per process, so a new session window picks it up.
 * The call itself runs in the app's window, as every call does; the panel shows it.
 */
class AssistantFlow(private val scope: CoroutineScope) {
    enum class Stage {
        /** Working out whether a call can start, then starting it; the panel shows the call. */
        Call,

        /** The Assistant experiment is off in Settings › Experimental. */
        TurnedOff,

        /** No server to call. */
        NoServer,

        /** No voice model a call could reach: set one up, or type instead. */
        NeedsVoice,

        /** A phone call is on: OpenCode waits until it's over. */
        PhoneCall,
    }

    data class State(
        val stage: Stage = Stage.Call,
        val form: AssistantCallPanel.Form = AssistantCallPanel.Form.bar,
        /** Where the pill sits: -1 start, 0 centre, 1 end. Kept for the process. */
        val side: Float = 0f,
        val typing: AssistantCallPanel.Typing = AssistantCallPanel.Typing(),
        /** The panel follows a call (started or found by a press), so its end closes the panel. */
        val hadCall: Boolean = false,
        /** The panel should go. */
        val finished: Boolean = true,
        /** OpenCode is on screen, showing the call itself. */
        val isAppShown: Boolean = false,
        val isLocked: Boolean = false,
        /** A phone call is ringing or on: the panel keeps clear of the phone app's controls. */
        val isOnPhoneCall: Boolean = false,
        /** OpenCode was opened only to start the call and steps aside once it runs. */
        val isAppStartingCall: Boolean = false,
        /** What the session did on the phone, newest last, for the chips. */
        val phoneActions: List<PhoneTools.Action> = emptyList(),
        /** A phone tool that needs a yes first (reading the screen or notifications, a call, a text): the panel asks. */
        val asking: PhoneTools.Confirm? = null,
        /** Counts presses and pill taps, so the quiet timer starts again from each. */
        val shows: Int = 0,
    ) {
        /** The panel shows a confirm card for a phone tool. */
        val isAsking: Boolean get() = asking != null

        /** A call the panel follows is still up: leaving OpenCode or unlocking brings the pill back. */
        val followsCall: Boolean get() = stage == Stage.Call && hadCall && !finished

        /** Out of the way: the phone is locked, or the call's panel gives way to a phone call. */
        val isAway: Boolean get() = isLocked || (stage == Stage.Call && isOnPhoneCall)
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var work: Job? = null
    private var sending: Job? = null
    private var ending: Job? = null
    private var cardAnswer: CompletableDeferred<Boolean>? = null

    /** When the system last hid the panel during its call, to stop showing it again in a loop. */
    private var reshows: List<Long> = emptyList()

    /**
     * The session the panel speaks for (Settings › Assistant › Session), and its project's folder
     * when known; the app offers it the phone's tools. Null until a new session is made.
     */
    private var panelSession: Pair<String, String?>? = null

    /** Where this press's call and messages go. */
    private var pick: AssistantSessionTarget.Pick? = null

    init {
        scope.launch { state.collect { syncSession() } }
    }

    /** A press of the side button: shows a running call again, or starts one. */
    fun begin(context: Context) {
        val commands = dependency(VoiceCallCommands)
        val current = state.value
        if (work?.isActive == true && current.stage == Stage.Call && !current.finished && !current.hadCall) {
            // A second press while the first is still starting the call. (Once that call has
            // been and gone, a press starts the next one.)
            AppLog.app.info("Assistant pressed again while starting")
            mutableState.update { it.copy(form = AssistantCallPanel.Form.bar) }
            return
        }
        ending?.cancel()
        if (current.isOnPhoneCall) {
            AppLog.app.notice("Assistant pressed during a phone call")
            val hasCall = commands.hasActiveCall
            mutableState.update {
                it.copy(stage = Stage.PhoneCall, hadCall = hasCall, finished = false, typing = AssistantCallPanel.Typing())
            }
            // The phone app's controls sit under the notice; it goes by itself.
            ending = scope.launch {
                delay(failureLinger)
                finish()
            }
            return
        }
        if (commands.hasActiveCall) {
            AppLog.app.info("Assistant showed the call in progress")
            showCallInProgress()
            return
        }
        work?.cancel()
        val fresh = State(
            finished = false,
            side = current.side,
            isAppShown = current.isAppShown,
            isLocked = current.isLocked,
            isOnPhoneCall = current.isOnPhoneCall,
        )
        if (!shared(AssistantExperiment.key).value) {
            AppLog.app.notice("Assistant is off")
            mutableState.value = fresh.copy(stage = Stage.TurnedOff)
            return
        }
        val directory = SessionDirectory.loadOrEmpty(SessionDirectory.file(dependency(AppContext).filesDir))
        if (directory.current == null) {
            AppLog.app.notice("Assistant has no server")
            mutableState.value = fresh.copy(stage = Stage.NoServer)
            return
        }
        mutableState.value = fresh
        pick = null
        panelSession = null
        val appContext = context.applicationContext
        val hasMicrophone = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        work = scope.launch {
            // A server that doesn't answer in time gets the call anyway, which explains itself.
            val ready = withTimeoutOrNull(readinessBudget) { AssistantVoiceReadiness.isReady() } ?: true
            val chosen = AssistantSessionTarget.resolve(directory, dependency(OpenCodeClient))
            pick = chosen
            panelSession = when (chosen) {
                is AssistantSessionTarget.Pick.Existing -> chosen.sessionID to chosen.directory
                is AssistantSessionTarget.Pick.Recent -> directory.current?.lastViewed?.let { session ->
                    session.id to directory.current?.project(session.projectID)?.directory
                }
                is AssistantSessionTarget.Pick.New, null -> null
            }
            syncSession()
            if (!ready) {
                AppLog.app.notice("Assistant needs voice set up")
                mutableState.update { it.copy(stage = Stage.NeedsVoice) }
                return@launch
            }
            val action = SystemAction(
                serverKey = chosen?.serverKey,
                destination = chosen?.let(AssistantSessionTarget::destination) ?: SystemAction.Destination.LastViewed,
                startsCall = true,
                origin = SystemActionOrigin.sideButton,
            )
            if (!hasMicrophone) {
                // The microphone is asked for in the app, as a first call from the call button does.
                // The panel follows the call, hidden while the app is on screen, so leaving the
                // app once the call runs brings the pill.
                AppLog.app.info("Assistant started the call in the app for the microphone")
                mutableState.update { it.copy(isAppShown = true) }
                SystemActionActivity.openApp(appContext)
                SystemActionInbox.shared.post(action)
                if (awaitCall(commands, permissionBudget)) mutableState.update { it.copy(hadCall = true) } else finish()
                return@launch
            }
            if (state.value.isAppShown) {
                // OpenCode on screen takes the call itself; the panel follows it, hidden, so
                // leaving the app brings the pill.
                AppLog.app.info("Assistant started the call in the app")
                SystemActionInbox.shared.post(action)
                if (awaitCall(commands)) mutableState.update { it.copy(hadCall = true) } else finish()
                return@launch
            }
            if (!SystemActionInbox.shared.isListening) {
                // The app isn't running: it starts behind the app on screen to take the call
                // (opening it in front flashed its launch screen). Launching behind makes a new
                // task, so a task left from before goes; its process is gone and it reopens as
                // it would anyway.
                startAppBehind(appContext)
            }
            SystemActionInbox.shared.post(action.copy(startsInBackground = true))
            if (awaitCall(commands)) {
                AppLog.voice.notice("Assistant call started")
                mutableState.update { it.copy(hadCall = true) }
                if (!OpenCodeAssistantSession.isShowing) {
                    // The window went while the call was starting (the app starting behind can
                    // take it): the panel comes back for the call.
                    AppLog.app.notice("Assistant panel gone while the call started; showing it again")
                    reshowAfterSystemHide()
                }
            } else {
                // Still routed: the app starts the call at most once.
                AppLog.voice.notice("Assistant call didn't start; opening the app")
                SystemActionActivity.openApp(appContext)
                finish()
            }
        }
    }

    /**
     * Starts OpenCode behind the app on screen, to take the call or the phone's tools; it steps
     * aside once running (opening it in front flashed its launch screen). Launching behind makes
     * a new task, so a task left from before goes; its process is gone and it reopens as it would
     * anyway.
     */
    private fun startAppBehind(appContext: Context) {
        mutableState.update { it.copy(isAppStartingCall = true) }
        for (task in appContext.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()) {
            task.finishAndRemoveTask()
        }
        appContext.startActivity(
            Intent(appContext, MainActivity::class.java)
                .setAction(MainActivity.ACTION_ASSISTANT_CALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            ActivityOptions.makeTaskLaunchBehind().toBundle(),
        )
    }

    /**
     * Tells the app which session the panel talks to, so it offers that session the phone's
     * tools; once the panel goes, the app keeps them until the session's turn is over.
     */
    private fun syncSession() {
        val store = SceneWindows.assistantWindow?.store ?: return
        val current = state.value
        val wanted = if (current.finished || (current.stage != Stage.Call && current.stage != Stage.NeedsVoice)) null else panelScope(store.state)
        val sent = store.state.assistantScope
        if (wanted == null) {
            if (sent?.isOpen == true) store.send(AppFeature.Action.AssistantPanelChanged(null))
            return
        }
        if (sent != wanted) store.send(AppFeature.Action.AssistantPanelChanged(wanted))
    }

    /**
     * The panel's session with its folder. A session outside any project (no git) has no folder in
     * the directory file, so the folder comes from Home's session list when the app has it.
     */
    private fun panelScope(app: AppFeature.State): AssistantScope? {
        val (sessionID, fallback) = panelSession ?: return null
        var folder = fallback
        val sessions = app.home?.sessions.orEmpty()
        for (session in sessions) {
            if (session.id == sessionID) folder = session.directory
        }
        return AssistantScope(sessionID, folder ?: return null)
    }

    /** The session ran one of the phone's tools: its chip shows on the panel. */
    fun acted(action: PhoneTools.Action) = mutableState.update {
        it.copy(phoneActions = (it.phoneActions + action).takeLast(maxPhoneActions))
    }

    /**
     * The session wants to run a phone tool that needs a yes first ([card]): true when Settings ›
     * Assistant lets that kind run without asking, else the panel asks (showing itself again if it
     * had gone) and this waits for the answer. The tool's own time limit ends an unanswered question.
     */
    suspend fun confirm(card: PhoneTools.Confirm): Boolean = withContext(Dispatchers.Main) {
        val setting = withoutAsking(card)
        if (setting != null && shared(setting).value) return@withContext true
        cardAnswer?.complete(false)
        val answer = CompletableDeferred<Boolean>()
        cardAnswer = answer
        mutableState.update { it.copy(asking = card) }
        if (!OpenCodeAssistantSession.isShowing) OpenCodeAssistantService.showCall()
        try {
            answer.await()
        } finally {
            // A newer card took over: it's on the panel now, even when it equals this one.
            if (cardAnswer === answer) {
                cardAnswer = null
                mutableState.update { it.copy(asking = null) }
            }
        }
    }

    /** The yes or no on the panel's card. */
    fun answer(yes: Boolean) {
        val kind = state.value.asking?.let { kind(it) } ?: "none"
        AppLog.app.info { "Assistant card answered kind=$kind yes=$yes" }
        cardAnswer?.complete(yes)
    }

    /** The Settings switch that skips [card]; null for cards that always ask. */
    private fun withoutAsking(card: PhoneTools.Confirm) = when (card) {
        is PhoneTools.Confirm.PhoneCall -> AssistantExperiment.callsWithoutAsking
        is PhoneTools.Confirm.Text -> AssistantExperiment.textsWithoutAsking
        PhoneTools.Confirm.Notifications -> AssistantExperiment.readsNotificationsWithoutAsking
        // A reply is a message the user sends, like a text.
        is PhoneTools.Confirm.NotificationReply -> AssistantExperiment.textsWithoutAsking
        // A button that could delete, send or call always asks.
        is PhoneTools.Confirm.NotificationAction -> null
        is PhoneTools.Confirm.Texts, is PhoneTools.Confirm.CallLog, is PhoneTools.Confirm.DeleteEvent -> null
    }

    private fun kind(card: PhoneTools.Confirm): String = when (card) {
        is PhoneTools.Confirm.PhoneCall -> "call"
        is PhoneTools.Confirm.Text -> "text"
        PhoneTools.Confirm.Notifications -> "notifications"
        is PhoneTools.Confirm.NotificationReply -> "reply"
        is PhoneTools.Confirm.NotificationAction -> "notification-action"
        is PhoneTools.Confirm.Texts -> "read-texts"
        is PhoneTools.Confirm.CallLog -> "call-log"
        is PhoneTools.Confirm.DeleteEvent -> "delete-event"
    }

    /**
     * A call is running and isn't ending, so the panel must keep a control for it on screen. The
     * call service lets go of an ended call a moment after the call says it ended.
     */
    private fun hasLiveCall(commands: VoiceCallCommands): Boolean {
        if (!commands.hasActiveCall) return false
        val voice = SceneWindows.assistantWindow?.store?.state?.home?.sessionDetail?.voice ?: return true
        return !voice.phase.isTerminal
    }

    /** Follows the call that's already running, as the bar. */
    internal fun showCallInProgress() = mutableState.update {
        it.copy(stage = Stage.Call, form = AssistantCallPanel.Form.bar, hadCall = true, finished = false, shows = it.shows + 1)
    }

    private suspend fun awaitCall(commands: VoiceCallCommands, budget: Duration = callBudget): Boolean {
        var waited = 0.milliseconds
        while (waited < budget) {
            if (commands.hasActiveCall) return true
            delay(callPoll)
            waited += callPoll
        }
        return commands.hasActiveCall
    }

    /** The call this panel follows has ended: it stays long enough to read, longer if it failed. */
    fun callEnded(failed: Boolean) {
        val current = state.value
        if (!current.hadCall || current.finished || ending?.isActive == true) return
        // The panel can still hold the last call's end when a press has just started the next.
        val commands = dependency(VoiceCallCommands)
        if (hasLiveCall(commands)) return
        ending = scope.launch {
            delay(if (failed) failureLinger else endLinger)
            if (hasLiveCall(commands)) return@launch
            finish(commands)
        }
    }

    /**
     * OpenCode came on screen or left it. While it's on screen it shows the call itself, so the
     * panel goes; leaving while the call is up brings the pill back.
     */
    fun appShown(shown: Boolean) {
        syncSession()
        val before = state.value
        if (before.isAppShown == shown) return
        mutableState.update { it.copy(isAppShown = shown) }
        if (shown) return
        if (before.isAppStartingCall) {
            // The app stepping aside after starting the call: the panel stays as it was.
            mutableState.update { it.copy(isAppStartingCall = false) }
            if (!before.finished && before.stage == Stage.Call) OpenCodeAssistantService.showCall()
            return
        }
        if (!before.followsCall) return
        if (!dependency(VoiceCallCommands).hasActiveCall) {
            // The call ended while OpenCode showed it.
            finish()
            return
        }
        AppLog.app.info("Assistant pill back after the app")
        minimise()
        OpenCodeAssistantService.showCall()
    }

    /**
     * Android or the phone's maker hid the panel's window while the call it follows is still on
     * and nothing else shows it: the pill comes back. If the window keeps going, the call opens in
     * OpenCode instead, so a call never runs without a control on screen.
     */
    fun hiddenBySystem(context: Context, now: Long) {
        val current = state.value
        val inApp = current.isAppShown && !current.isAppStartingCall
        if (!current.followsCall || current.isAway || inApp || !hasLiveCall(dependency(VoiceCallCommands))) return
        reshows = reshows.filter { now - it < reshowWindowMillis } + now
        if (reshows.size > maxReshows) {
            AppLog.app.notice("Assistant panel kept being hidden; opening the call in the app")
            reshows = emptyList()
            SystemActionActivity.openApp(context.applicationContext)
            return
        }
        AppLog.app.notice("Assistant panel hidden by the system; showing the pill again")
        minimise()
        reshowAfterSystemHide()
    }

    private fun reshowAfterSystemHide() {
        scope.launch {
            delay(reshowDelay)
            val current = state.value
            val inApp = current.isAppShown && !current.isAppStartingCall
            if (!current.followsCall || current.isAway || inApp) return@launch
            if (!OpenCodeAssistantService.showCall()) {
                // No assistant service to show it with: the call opens in OpenCode instead.
                AppLog.app.notice("Assistant couldn’t show the panel; opening the call in the app")
                SystemActionActivity.openApp(dependency(AppContext))
            }
        }
    }

    /** The phone locked or was unlocked: the panel stays out of the lock screen and comes back as the pill. */
    fun locked(isLocked: Boolean) {
        val before = state.value
        if (before.isLocked == isLocked) return
        mutableState.update { it.copy(isLocked = isLocked) }
        if (isLocked) {
            if (before.followsCall) minimise()
            return
        }
        if (!before.followsCall || before.isAppShown || before.isOnPhoneCall || !dependency(VoiceCallCommands).hasActiveCall) return
        AppLog.app.info("Assistant pill back after unlock")
        OpenCodeAssistantService.showCall()
    }

    /** A phone call rang or ended: the panel steps aside for it and the pill comes back after. */
    fun phoneCall(isOn: Boolean) {
        if (state.value.isOnPhoneCall == isOn) return
        mutableState.update { it.copy(isOnPhoneCall = isOn) }
        if (state.value.stage == Stage.PhoneCall && !state.value.finished && !isOn) finish()
        val now = state.value
        if (!now.followsCall) return
        if (isOn) {
            minimise()
            return
        }
        if (now.isLocked || now.isAppShown || !dependency(VoiceCallCommands).hasActiveCall) return
        AppLog.app.info("Assistant pill back after a phone call")
        OpenCodeAssistantService.showCall()
    }

    fun form(form: AssistantCallPanel.Form) = mutableState.update {
        val typing = if (form == AssistantCallPanel.Form.pill) it.typing.copy(isOpen = false) else it.typing
        val shows = if (form == AssistantCallPanel.Form.bar) it.shows + 1 else it.shows
        it.copy(form = form, typing = typing, shows = shows)
    }

    fun side(side: Float) = mutableState.update { it.copy(side = side) }

    fun minimise() = form(AssistantCallPanel.Form.pill)

    /** The bar is up for the call the panel follows, with no confirm card on it. */
    private fun isBarFollowingCall(state: State): Boolean =
        state.followsCall && !state.isAsking && state.form == AssistantCallPanel.Form.bar

    /** A touch on the app behind: the user has moved on, so the bar steps aside as the pill. */
    fun touchedOutside() {
        val state = mutableState.value
        if (!isBarFollowingCall(state)) return
        AppLog.app.info("Assistant shrank after a touch outside")
        form(AssistantCallPanel.Form.pill)
    }

    /**
     * A phone tool put another app, a system screen or a permission prompt up: shrink the bar to
     * the pill, or close the typed-message notice, so the panel doesn't cover it.
     */
    fun showedScreen() = scope.launch {
        val state = mutableState.value
        if (state.stage == Stage.NeedsVoice && !state.finished && state.typing.message.isBlank() && !state.typing.isSending) {
            // The typed message went; the notice would cover the app or prompt the tool put up.
            AppLog.app.info("Assistant notice closed for a screen a tool opened")
            finish()
            return@launch
        }
        if (!isBarFollowingCall(state) || state.typing.isOpen) return@launch
        AppLog.app.info("Assistant shrank for a screen a tool opened")
        form(AssistantCallPanel.Form.pill)
    }

    /** The call went quiet for a while with the bar up. */
    fun quiet() {
        val state = mutableState.value
        if (!isBarFollowingCall(state) || state.typing.isOpen) return
        AppLog.app.info("Assistant shrank after a quiet spell")
        form(AssistantCallPanel.Form.pill)
    }

    /** The call needs an answer or has failed: the pill grows back so the user sees why. */
    fun callNeedsUser() {
        val state = mutableState.value
        if (!state.followsCall || state.form != AssistantCallPanel.Form.pill) return
        AppLog.app.info("Assistant grew back for the call")
        form(AssistantCallPanel.Form.bar)
    }

    fun toggleTyping() = mutableState.update {
        val opening = !it.typing.isOpen
        // The message box belongs to the bar; from the pill it opens the bar.
        val form = if (opening && it.form == AssistantCallPanel.Form.pill) AssistantCallPanel.Form.bar else it.form
        it.copy(form = form, typing = it.typing.copy(isOpen = opening, result = null, failed = false))
    }

    fun closeTyping() = mutableState.update { it.copy(typing = it.typing.copy(isOpen = false)) }

    fun updateMessage(text: String) = mutableState.update {
        if (it.typing.isSending) it else it.copy(typing = it.typing.copy(message = text, result = null, failed = false))
    }

    fun finish() = finish(dependency(VoiceCallCommands))

    private fun finish(commands: VoiceCallCommands) {
        val current = state.value
        if (current.stage == Stage.PhoneCall && current.hadCall && !current.finished) {
            // The press during a phone call found OpenCode's own call: the panel goes on following it.
            mutableState.update { it.copy(stage = Stage.Call, form = AssistantCallPanel.Form.pill) }
            return
        }
        if (current.followsCall && hasLiveCall(commands)) {
            // A live call always keeps a control on screen: the panel stays, as the pill.
            AppLog.app.notice("Assistant kept the panel for the live call")
            minimise()
            return
        }
        work?.cancel()
        ending?.cancel()
        mutableState.update { it.copy(finished = true, isAppStartingCall = false, typing = AssistantCallPanel.Typing()) }
    }

    /** Sends the typed message to the assistant's session: the call's own, else the chosen one. */
    fun send() {
        val typing = state.value.typing
        if (!typing.canSend) return
        mutableState.update { it.copy(typing = it.typing.copy(isSending = true)) }
        // The phone's tools reach the session through the running app.
        if (!SystemActionInbox.shared.isListening) startAppBehind(dependency(AppContext))
        sending = scope.launch {
            val haptics = dependency(HapticsClient)
            try {
                val target = sendTarget()
                awaitPhoneTools()
                val sent = SystemPromptSender.send(typing.message, target, AskOpenCodeIntent.defaultDelivery.delivery)
                AppLog.send.info { "Assistant sent session=${target?.let(ScopedID::parse)?.id ?: "recent"} delivery=${sent.delivery.rawValue}" }
                haptics.play(Haptic.success)
                mutableState.update {
                    it.copy(typing = it.typing.copy(isSending = false, message = "", result = sent.confirmation, failed = false))
                }
                delay(confirmationLinger)
                if (state.value.stage == Stage.NeedsVoice) {
                    finish()
                } else if (state.value.typing.message.isEmpty()) {
                    // Back to the call once the confirmation's been read, unless they're typing again.
                    mutableState.update { it.copy(typing = AssistantCallPanel.Typing()) }
                }
            } catch (failure: SystemPromptSender.Failure) {
                AppLog.send.error("Assistant couldn’t send")
                haptics.play(Haptic.error)
                mutableState.update { it.copy(typing = it.typing.copy(isSending = false, result = failure.message, failed = true)) }
            }
        }
    }

    /**
     * The typed message's session as a `ScopedID`: the running assistant call's, else the chosen
     * one. New Session makes the session here, before sending, so its phone tools attach first;
     * later messages from the same panel go to it too. Null sends to the last viewed session.
     */
    private suspend fun sendTarget(): String? {
        val chosen = pick
        val app = SceneWindows.assistantWindow?.store?.state
        val voice = app?.home?.sessionDetail?.voice
        val serverKey = chosen?.serverKey ?: app?.home?.server?.persistenceID?.let(SessionDirectory::serverKey)
        val callSession = voice?.target?.sessionID
        if (voice != null && voice.isAssistantCall && !voice.phase.isTerminal && callSession != null && serverKey != null) {
            return ScopedID(serverKey, callSession).rawValue
        }
        val known = panelSession
        if (known != null && serverKey != null) return ScopedID(serverKey, known.first).rawValue
        if (chosen !is AssistantSessionTarget.Pick.New) return null
        val server = AssistantSessionTarget.server(chosen.serverKey) ?: throw SystemPromptSender.Failure.noServer
        // A project the directory file doesn't list (a folder without git) comes from Home's list.
        var folder = chosen.directory
        if (folder == null) folder = app?.home?.sessions?.maxByOrNull { it.updatedAt }?.directory
        if (folder == null) throw SystemPromptSender.Failure.noProject
        val created = try {
            dependency(OpenCodeClient).createSession(server, folder, ServerID.newSessionID())
        } catch (error: OpenCodeClientError) {
            throw SystemPromptSender.Failure("Couldn’t send to OpenCode. ${error.message}")
        }
        AppLog.app.info { "Assistant made a new session=${created.id}" }
        panelSession = created.id to created.directory
        return ScopedID(chosen.serverKey, created.id).rawValue
    }

    /**
     * Waits, briefly, until the session's phone tools are attached, so a typed “set a timer” finds
     * them on its first turn. Sends anyway when they don't come.
     */
    private suspend fun awaitPhoneTools() {
        val deadline = dependency(Clock).now() + toolsBudget
        while (dependency(Clock).now() < deadline) {
            syncSession()
            val app = SceneWindows.assistantWindow?.store?.state
            val target = app?.clientToolsTarget
            val attached = app?.clientTools?.status == ClientToolsFeature.State.Status.Attached &&
                app.clientTools.target == target
            if (target != null && target.tools.contains(ClientTool.setTimer) && attached) return
            delay(callPoll)
        }
        AppLog.app.notice("Assistant sent before the phone tools attached")
    }

    companion object {
        /** The process's one flow: the panel survives its session window being hidden and shown again. */
        val shared: AssistantFlow by lazy { AssistantFlow(MainScope()) }

        private const val maxPhoneActions = 3
        private val readinessBudget = 4.seconds
        private val callBudget = 10.seconds
        /** Answering the microphone prompt takes a person a while. */
        private val permissionBudget = 60.seconds
        private val callPoll = 100.milliseconds
        private val toolsBudget = 5.seconds
        private val reshowDelay = 300.milliseconds
        private const val reshowWindowMillis = 10_000L
        private const val maxReshows = 3

        /** Long enough to read the confirmation before the panel goes. */
        private val confirmationLinger = 1400.milliseconds

        /** Long enough to see the call end before the panel goes. */
        private val endLinger = 1200.milliseconds

        /** Long enough to read why the call failed. */
        private val failureLinger = 4.seconds
    }
}
