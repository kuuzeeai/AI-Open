package ai.opencode.app.assistant

import ai.opencode.app.AppFeature
import ai.opencode.app.MainActivity
import ai.opencode.app.SceneWindows
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.AssistantExperiment
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.assistant.AssistantSetupClient
import ai.opencode.feature.system.intents.SystemActionActivity
import ai.opencode.feature.voice.AssistantCallPanel
import ai.opencode.feature.voice.PhoneTools
import ai.opencode.feature.voice.ScreenInfo
import ai.opencode.feature.voice.ScreenRead
import ai.opencode.feature.voice.VoiceCallCommands
import ai.opencode.feature.voice.VoiceFeature
import android.app.KeyguardManager
import android.app.assist.AssistStructure
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The side button's panel. Each press shows this session's window over whatever app is on
 * screen: a panel at the bottom that starts the call and shows it until it ends, and the pill
 * it minimises to. Only the panel or pill takes touches, so the app behind stays usable, and
 * the window takes the keyboard only while the user types. While the phone is locked it asks
 * for an unlock first ([AssistantActivity]), then opens again. It also reads the screen for the
 * call's `read_screen` tool, fresh each time ([EXTRA_SCREEN]), without showing anything.
 */
class OpenCodeAssistantSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val scope = MainScope()
    private val flow = AssistantFlow.shared
    private val screen = StringBuilder()
    private var screenApp: String? = null
    private var screenPackage: String? = null
    private var screenActivity: String? = null
    private var screenUrl: String? = null
    private val touchable = android.graphics.Region()
    private var watching: Job? = null

    /** A `read_screen` asked Android for the screen ([EXTRA_SCREEN]); its parts are arriving. */
    private var readingScreen = false
    private var assistDone = false
    private var screenshotDone = false
    private var screenshot: Bitmap? = null
    private var readingTimeout: Job? = null

    /** The window opened only to read the screen: it stays invisible and untouchable, then goes. */
    private var screenOnly = false

    /** The panel is hiding its own window; any other hide came from the system. */
    private var hidingOnPurpose = false

    override fun onCreate() {
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate()
        val window = window.window ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // Touches outside the panel go to the app behind; the window still hears that one
        // happened (ACTION_OUTSIDE, with no position), which shrinks the bar to the pill.
        window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        window.decorView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) flow.touchedOutside()
            false
        }
        // Draw under the bars like Gemini's panel: no navigation bar scrim below the panel.
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.setDecorFitsSystemWindows(false)
        // The panel lifts itself over the keyboard (insets); the system panning the window as
        // well lifted it twice.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // The panel animates itself; the window's own slide doubled it when shown and hidden.
        window.setWindowAnimations(0)
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        fitBelowStatusBar()
    }

    /**
     * The window stops at the status bar: covering it, the window would set the status bar's
     * icons for the app underneath (light on light). It still covers the navigation bar, whose
     * buttons follow the panel's own theme.
     */
    private fun fitBelowStatusBar() {
        val window = window.window ?: return
        val metrics = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics ?: return
        val top = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
        ).top
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, metrics.bounds.height() - top)
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val light = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fitBelowStatusBar()
    }

    override fun onCreateContentView(): View {
        val view = ComposeView(context)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
        }
        view.setContent {
            OpenCodeAppTheme {
                val state by flow.state.collectAsState()
                val voice by produceState<VoiceFeature.State?>(null) {
                    while (true) {
                        val window = SceneWindows.assistantWindow
                        if (window == null) {
                            delay(200)
                            continue
                        }
                        withTimeoutOrNull(1_000) {
                            window.store.states.collect { app ->
                                val next = app.home?.sessionDetail?.voice
                                val last = value
                                // The session drops its call when it ends: keep showing that call, ended.
                                value = if (next == null && last != null && flow.state.value.hadCall) last.copy(phase = VoiceFeature.State.Phase.Ended) else next
                            }
                        }
                    }
                }
                val ended = voice?.phase?.isTerminal == true
                val failed = voice?.phase is VoiceFeature.State.Phase.Failed
                LaunchedEffect(ended, state.hadCall) { if (ended) flow.callEnded(failed) }
                AssistantOverlay(state, voice, overlayActions)
            }
        }
        return view
    }

    private val overlayActions = AssistantOverlayActions(
        panel = AssistantCallPanel.Actions(
            call = ::sendToCall,
            form = flow::form,
            side = flow::side,
            openCall = ::openApp,
            openAction = ::openAction,
            toggleTyping = flow::toggleTyping,
            message = flow::updateMessage,
            send = flow::send,
        ),
        openSettings = ::openSettings,
        openApp = ::openApp,
        dismiss = flow::finish,
        answer = flow::answer,
        touchable = ::updateTouchable,
        quiet = flow::quiet,
        needsUser = flow::callNeedsUser,
    )

    /** Settings › Assistant shows Side Button as set once holding it has opened the panel, where the phone's setting can't be read. */
    private fun markSideButtonUsed(args: Bundle?, showFlags: Int) {
        if (!AssistantSetupClient.isSideButtonPress(args, showFlags)) return
        val used = shared(AssistantExperiment.sideButtonUsed)
        if (used.value) return
        used.set(true)
        AppLog.app.info("Assistant side button used")
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args?.getBoolean(EXTRA_SCREEN) == true) {
            readScreen(showFlags)
            return
        }
        isShowing = true
        screenOnly = false
        window.window?.decorView?.alpha = 1f
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (args?.getBoolean(EXTRA_CALL) == true) {
            // Back after OpenCode or the lock screen, as it was left; only while the call is up.
            val state = flow.state.value
            val showsCall = !state.finished && state.stage == AssistantFlow.Stage.Call
            if (keyguard?.isKeyguardLocked == true || !(showsCall || state.isAsking)) {
                hideOnPurpose()
                return
            }
            AppLog.app.info("Assistant call shown again")
            watch()
            return
        }
        AppLog.app.notice("Assistant opened")
        markSideButtonUsed(args, showFlags)
        if (keyguard?.isKeyguardLocked == true) {
            if (OpenCodeAssistantService.isPhoneCall(context)) {
                AppLog.app.notice("Assistant pressed on lock screen during a phone call")
                hideOnPurpose()
                return
            }
            // The panel waits for an unlock; it opens again once the phone is unlocked.
            try {
                startAssistantActivity(Intent(context, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: RuntimeException) {
                AppLog.app.error("Assistant couldn’t ask to unlock")
            }
            hideOnPurpose()
            return
        }
        flow.locked(false)
        flow.begin(context)
        watch()
    }

    /** Follows the panel: its end hides the window, and the keyboard is the window's only while typing. */
    private fun watch() {
        watching?.cancel()
        watching = scope.launch {
            flow.state.collectLatest { state ->
                val wantsKeyboard = !state.finished && !state.isAsking && (state.stage == AssistantFlow.Stage.NeedsVoice || state.typing.isOpen)
                val window = window.window
                if (wantsKeyboard) {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                } else {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                }
                // OpenCode on screen shows the call itself; the pill comes back when it leaves.
                val inApp = state.isAppShown && !state.isAppStartingCall && state.stage == AssistantFlow.Stage.Call
                if (!state.isAsking && (state.finished || inApp || state.isAway)) {
                    // Lets the panel slide away first.
                    delay(hideDelay)
                    hideOnPurpose()
                }
            }
        }
    }

    /**
     * Asks nothing of the user: the system sends this session the screen's assist data and a
     * screenshot ([onHandleAssist], [onHandleScreenshot]) for [OpenCodeAssistantService.readScreen].
     * A panel already on screen stays as it is; otherwise the window stays invisible and goes
     * once the screen is in.
     */
    private fun readScreen(showFlags: Int) {
        if (!isShowing) {
            screenOnly = true
            window.window?.decorView?.alpha = 0f
        }
        readingScreen = true
        assistDone = false
        screenshotDone = false
        screenshot = null
        screen.clear()
        screenApp = null
        screenPackage = null
        screenActivity = null
        screenUrl = null
        AppLog.app.info { "Assistant screen asked flags=$showFlags" }
        readingTimeout?.cancel()
        readingTimeout = scope.launch {
            delay(screenWait)
            AppLog.app.notice { "Assistant screen partly in assist=$assistDone screenshot=$screenshotDone" }
            finishReading()
        }
    }

    override fun onHandleAssist(state: AssistState) {
        if (!readingScreen) return
        val structure = state.assistStructure
        // The lock screen isn't the user's screen; what it shows stays out of the call.
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        var length = 0
        if (structure != null && !locked) {
            val text = ScreenText.from(structure)
            length = text.length
            if (screenPackage == null) {
                screenApp = ScreenText.app(structure, context.packageManager)
                screenPackage = structure.activityComponent?.packageName
                screenActivity = structure.activityComponent?.className
            }
            if (screenUrl == null) screenUrl = ScreenText.url(state.assistContent?.webUri, structure)
            if (text.isNotBlank()) {
                if (screen.isNotEmpty()) screen.append("\n\n")
                screen.append(text)
            }
        }
        AppLog.app.info { "Assistant screen ${state.index + 1}/${state.count} chars=$length" }
        // With no data Android still calls once, with nothing in it.
        if (state.index + 1 >= state.count) {
            assistDone = true
            if (screenshotDone) finishReading()
        }
    }

    /** Android sends none when the user turned screenshots off for the assistant. */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (!readingScreen) return
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        this.screenshot = if (locked) null else screenshot
        screenshotDone = true
        AppLog.app.info { "Assistant screenshot in=${this.screenshot != null}" }
        if (assistDone) finishReading()
    }

    private fun finishReading() {
        if (!readingScreen) return
        readingScreen = false
        readingTimeout?.cancel()
        readingTimeout = null
        val hasMeta = screenApp != null || screenPackage != null || screenUrl != null
        val info = if (screen.isBlank() && !hasMeta) null else ScreenInfo(screenApp, screenPackage, screenActivity, screenUrl, screen.toString())
        val read = ScreenRead(info, screenshot)
        screenshot = null
        screen.clear()
        pendingScreen?.complete(read)
        pendingScreen = null
        if (screenOnly) hideOnPurpose()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val decor = window.window?.decorView ?: return
        outInsets.contentInsets.top = decor.height
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        if (screenOnly) {
            outInsets.touchableRegion.setEmpty()
        } else {
            outInsets.touchableRegion.set(touchable)
        }
    }

    /**
     * The window takes touches only where the panel is. The system reads this on the window's
     * next frame, so a change only asks for a redraw: relaying out the window mid-animation
     * made the panel jump.
     */
    private fun updateTouchable(rects: List<Rect>) {
        val next = android.graphics.Region()
        for (rect in rects) {
            next.union(android.graphics.Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()))
        }
        if (next == touchable) return
        touchable.set(next)
        window.window?.decorView?.postInvalidateOnAnimation()
    }

    /** Home and the recents button leave a call as the pill; anything else goes. */
    override fun onCloseSystemDialogs() {
        if (flow.state.value.stage == AssistantFlow.Stage.Call && dependency(VoiceCallCommands).hasActiveCall) {
            flow.minimise()
        } else {
            flow.finish()
        }
    }

    /** Back closes the message box, then minimises a call (or closes anything else). */
    override fun onBackPressed() {
        if (flow.state.value.typing.isOpen) {
            flow.closeTyping()
        } else {
            onCloseSystemDialogs()
        }
    }

    private fun hideOnPurpose() {
        hidingOnPurpose = true
        hide()
    }

    override fun onHide() {
        if (readingScreen) finishReading()
        if (screenOnly) {
            // Only the screen was read: nothing of the panel was shown, so nothing to follow.
            screenOnly = false
            hidingOnPurpose = false
            window.window?.decorView?.alpha = 1f
            super.onHide()
            return
        }
        isShowing = false
        super.onHide()
        watching?.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        val onPurpose = hidingOnPurpose
        hidingOnPurpose = false
        if (!onPurpose) flow.hiddenBySystem(context, SystemClock.elapsedRealtime())
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun sendToCall(action: VoiceFeature.Action) {
        val window = SceneWindows.assistantWindow ?: return
        window.store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.voicePresented(action))))
    }

    /** Opens OpenCode, which shows a call its own way: the panel sinks while it's on screen and the pill returns after. */
    private fun openApp() {
        SystemActionActivity.openApp(context)
        if (!flow.state.value.followsCall) flow.finish()
    }

    private fun openSettings(page: String) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SETTINGS)
            .putExtra(MainActivity.EXTRA_SETTINGS_PAGE, page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        flow.finish()
    }

    /** A tool's row opens what it touched: the Clock's timers or alarms, or the app it opened. */
    private fun openAction(action: PhoneTools.Action) {
        val intent = when (action.kind) {
            PhoneTools.Action.Kind.timer -> Intent(AlarmClock.ACTION_SHOW_TIMERS)
            PhoneTools.Action.Kind.alarm -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            PhoneTools.Action.Kind.app -> action.packageName?.let { context.packageManager.getLaunchIntentForPackage(it) }
            PhoneTools.Action.Kind.screen, PhoneTools.Action.Kind.other -> null
        } ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AppLog.app.info { "Assistant opened a ${action.kind.name} row" }
            flow.minimise()
        } catch (error: ActivityNotFoundException) {
            AppLog.app.notice { "Assistant couldn’t open a ${action.kind.name} row" }
        }
    }

    companion object {
        /** Asks [onShow] to show the call it follows again, not a press of the side button. */
        const val EXTRA_CALL = "ai.opencode.assistant.CALL"

        /** Asks [onShow] only to read the screen, for `read_screen`; see [readScreen]. */
        const val EXTRA_SCREEN = "ai.opencode.assistant.SCREEN"

        /** The read [OpenCodeAssistantService.readScreen] is waiting for. */
        internal var pendingScreen: CompletableDeferred<ScreenRead>? = null

        /** How long Android gets to send the screen's parts before a read goes with what came. */
        private val screenWait = 2_000L

        /** The panel's window is on screen. */
        var isShowing = false
            private set

        /** Lets the panel sink before its window goes. */
        private const val hideDelay = 420L
    }
}

/** The words on the screen behind the assistant, for `read_screen`: the app's name, and each view's text in order. */
internal object ScreenText {
    fun app(structure: AssistStructure, packageManager: PackageManager): String? {
        val packageName = structure.activityComponent?.packageName ?: return null
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
        return if (label.isNullOrBlank()) null else label
    }

    /** The page's address: what the app shared, else the web domain a browser view reports. */
    fun url(shared: android.net.Uri?, structure: AssistStructure): String? {
        if (shared != null) return shared.toString()
        for (index in 0 until structure.windowNodeCount) {
            val domain = webDomain(structure.getWindowNodeAt(index).rootViewNode)
            if (domain != null) return domain
        }
        return null
    }

    private fun webDomain(node: AssistStructure.ViewNode?): String? {
        node ?: return null
        val domain = node.webDomain
        if (!domain.isNullOrBlank()) {
            val scheme = node.webScheme ?: "https"
            return "$scheme://$domain"
        }
        for (child in 0 until node.childCount) {
            val found = webDomain(node.getChildAt(child))
            if (found != null) return found
        }
        return null
    }

    fun from(structure: AssistStructure): String {
        val lines = mutableListOf<String>()
        for (index in 0 until structure.windowNodeCount) {
            collect(structure.getWindowNodeAt(index).rootViewNode, lines)
        }
        return lines.joinToString("\n")
    }

    private fun collect(node: AssistStructure.ViewNode?, lines: MutableList<String>) {
        node ?: return
        if (node.visibility != View.VISIBLE) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            if (lines.lastOrNull() != text) lines.add(text)
        } else {
            val description = node.contentDescription?.toString()?.trim()
            if (!description.isNullOrEmpty() && node.childCount == 0 && lines.lastOrNull() != description) {
                lines.add(description)
            }
        }
        for (child in 0 until node.childCount) {
            collect(node.getChildAt(child), lines)
        }
    }
}
