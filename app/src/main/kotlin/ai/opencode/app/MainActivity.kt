package ai.opencode.app

import ai.opencode.app.assistant.AssistantFlow
import ai.opencode.feature.system.LeftToRight
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemActionInbox
import ai.opencode.feature.system.share.ShareConversation
import ai.opencode.server.LocalNetworkPermission
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.diagnostics.PerfFrameSampler
import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.ThemeSharedKeys
import ai.opencode.feature.composer.DictationPermissionClient
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.NotificationPermissionClient
import ai.opencode.feature.voice.VoiceMicrophonePermission
import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import ai.opencode.feature.settings.SettingsPage
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import ai.opencode.devtools.DevTools
import ai.opencode.devtools.DevToolsOverlay
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.system.SessionWindowTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The app's window. Its [Store] lives in a retained [RootStore] so configuration changes keep state
 * and in-flight effects. [route] turns intents into [AppFeature.Action]s.
 */
class MainActivity : ComponentActivity() {
    private val root: RootStore by viewModels()
    private val store get() = root.store

    /** The microphone prompt in flight (one at a time; a second ask joins it). */
    private var microphoneRequest: CompletableDeferred<Boolean>? = null
    private val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneRequest?.complete(granted)
        microphoneRequest = null
    }
    private var localNetworkRequest: CompletableDeferred<Boolean>? = null
    private val localNetworkLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localNetworkRequest?.complete(granted)
        localNetworkRequest = null
    }
    private var removeRequesters: List<() -> Unit> = emptyList()

    /** Started by the assistant and see-through until it steps aside (`hideForAssistant`). */
    private var isSteppingAside = false
    private var windowAnimations = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(LeftToRight.configuration())
    }

    private class TouchModeExit(val at: Long, val hadFocus: Boolean)
    private val typedNavigationKeys = setOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    private var touchModeLeft: TouchModeExit? = null

    /**
     * A typed key with nothing focused would give key focus to the first control (Home's ☰), so the
     * key that leaves touch mode takes that focus back. Space and Return are consumed on press to
     * leave touch mode, so for those the release does.
     */
    private fun dropsTypedFocus(event: KeyEvent): Boolean {
        val exit = touchModeLeft ?: return false
        val consumedPress = event.action == KeyEvent.ACTION_UP && event.keyCode in typedNavigationKeys
        if (event.action != KeyEvent.ACTION_DOWN && !consumedPress) return false
        touchModeLeft = null
        val focused = currentFocus
        if (event.downTime > exit.at || exit.hadFocus || focused == null) return false
        val content = findViewById<ViewGroup>(android.R.id.content)
        val focusability = content.descendantFocusability
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        focused.clearFocus()
        content.descendantFocusability = focusability
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Debug lab hooks run before anything reads the store.
        if (savedInstanceState == null) LabLaunch.apply(intent)
        // The window's store: the first window's, or a further window's on its own session.
        root.resolve(this, RootStore.target(intent, savedInstanceState))
        val splash = installSplashScreen()
        LaunchBloomController.start()
        splash.setKeepOnScreenCondition { LaunchBloomController.keepsCover(store.state.isColdLaunchContentReady) }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.action == ACTION_ASSISTANT_CALL) hideForAssistant()
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        store.send(AppFeature.Action.ForegroundChanged(true))
                        AssistantFlow.shared.appShown(true)
                    }
                    // Take what the share sheet left.
                    Lifecycle.Event.ON_RESUME -> {
                        SceneWindows.resumed(root)
                        store.send(AppFeature.Action.PendingSharesRequested)
                        registerPermissionRequesters()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        removeRequesters.forEach { it() }
                        removeRequesters = emptyList()
                    }
                    Lifecycle.Event.ON_STOP -> if (!isChangingConfigurations) {
                        store.send(AppFeature.Action.ForegroundChanged(false))
                        AssistantFlow.shared.appShown(false)
                        if (isSteppingAside) showAfterAssistant()
                    }
                    else -> Unit
                }
            },
        )
        // Developer tools (summon gesture, Tuning Panel, perf HUD): inert outside debug builds unless Developer Mode is on.
        if (savedInstanceState == null) {
            DevTools.handleLaunchIntent(intent)
            PerfTimeline.appLaunched()
            PerfFrameSampler.startIfRequested()
        }
        keepCallOnBack()
        setContent { DevToolsOverlay { AppView(store) } }
        window.decorView.viewTreeObserver.addOnTouchModeChangeListener { inTouchMode ->
            touchModeLeft = if (inTouchMode) null else TouchModeExit(SystemClock.uptimeMillis(), currentFocus != null)
        }
        val callback = window.callback
        window.callback = object : Window.Callback by callback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = dropsTypedFocus(event) || callback.dispatchKeyEvent(event)
        }
        if (root.follows) followShownSession()
        followAppearanceNightMode()
        // A recreated activity already handled its launch intent.
        if (savedInstanceState == null) handle(intent)
    }

    /**
     * Back with a call live puts the window behind the others instead of closing it, so the call is
     * still there on coming back. Added before the content, so it runs first.
     */
    private fun keepCallOnBack() {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
        lifecycleScope.launch {
            store.states.map { it.hasLiveCall }.distinctUntilChanged().collect { callback.isEnabled = it }
        }
    }

    /**
     * Calls and dictation ask for the microphone as they start; a call's first ask also asks for
     * notifications, for its ongoing notification.
     */
    private fun registerPermissionRequesters() {
        removeRequesters.forEach { it() }
        removeRequesters = listOf(
            dependency(VoiceMicrophonePermission).register {
                val granted = requestMicrophone()
                val notifications = dependency(NotificationPermissionClient)
                if (granted && notifications.status() == NotificationPermission.NotDetermined) notifications.request()
                granted
            },
            dependency(DictationPermissionClient).register { requestMicrophone() },
            dependency(LocalNetworkPermission).register { requestLocalNetwork() },
        )
    }

    private suspend fun requestLocalNetwork(): Boolean {
        val pending = localNetworkRequest ?: CompletableDeferred<Boolean>().also {
            localNetworkRequest = it
            localNetworkLauncher.launch(LocalNetworkPermission.PERMISSION)
        }
        return pending.await()
    }

    private suspend fun requestMicrophone(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        val pending = microphoneRequest ?: CompletableDeferred<Boolean>().also {
            microphoneRequest = it
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        return pending.await()
    }

    /** The splash's day/night colour follows the app's night mode, so that mirrors Appearance. */
    private fun followAppearanceNightMode() {
        val uiModeManager = getSystemService(UiModeManager::class.java)
        lifecycleScope.launch {
            shared(ThemeSharedKeys.appearanceColorScheme).flow.collect { scheme ->
                val mode = when (scheme.isDark) {
                    true -> UiModeManager.MODE_NIGHT_YES
                    false -> UiModeManager.MODE_NIGHT_NO
                    null -> UiModeManager.MODE_NIGHT_AUTO
                }
                uiModeManager.setApplicationNightMode(mode)
            }
        }
    }

    /** On a tablet the window's restorable value follows the session it shows. */
    private fun followShownSession() {
        lifecycleScope.launch {
            store.states.collect { state ->
                val pending = root.pendingTarget ?: return@collect
                val home = state.home ?: return@collect
                if (root.shown?.sessionID == pending.sessionID) {
                    root.pendingTarget = null
                    return@collect
                }
                if (!home.snapshotLoaded || home.server.persistenceID != pending.serverID) return@collect
                root.pendingTarget = null
                store.send(AppFeature.Action.Home(HomeFeature.Action.SessionTapped(pending.sessionID)))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!root.follows) return
        val shown = root.shown ?: root.pendingTarget ?: return
        outState.putString(SessionWindowTarget.EXTRA_SERVER_ID, shown.serverID)
        outState.putString(SessionWindowTarget.EXTRA_SESSION_ID, shown.sessionID)
    }

    override fun onDestroy() {
        if (isFinishing) root.activityFinished()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A window asked again for its own session shows it.
        if (intent.action == SessionWindowTarget.ACTION && root.follows) {
            val next = RootStore.target(intent, null) ?: return
            if (next != root.shown) store.send(AppFeature.Action.Home(HomeFeature.Action.SessionTapped(next.sessionID)))
            return
        }
        // Opened for something else while still see-through from the assistant's start.
        if (intent.action != ACTION_ASSISTANT_CALL) showAfterAssistant()
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ASSISTANT_CALL) {
            stepAsideForAssistant()
            return
        }
        val action = route(intent) ?: return
        LaunchBloomController.completeColdLaunch()
        if (action is AppFeature.Action.NotificationOpened) {
            // The window showing that session, else the one in use on its server, else the one
            // doing the app-wide work.
            val window = SceneWindows.window(action.destination, receiving = root)
            if (window !== root) SceneWindows.bringToFront(window, this)
            window.store.send(action)
            return
        }
        store.send(action)
    }

    /**
     * Android only: the assistant started a call while the app wasn't running, so the app opens
     * to take it (its call needs a window) and goes back behind the app the user was in once the
     * call is running; the assistant's panel stays on screen over it.
     */
    private fun stepAsideForAssistant() {
        lifecycleScope.launch {
            // The store only has to be up to take the call; the panel over the other app keeps
            // the microphone, so the app goes as soon as the call is handed over.
            var waited = 0L
            while (!SystemActionInbox.shared.isListening && waited < ASSISTANT_CALL_BUDGET_MS) {
                delay(50)
                waited += 50
            }
            if (SystemActionInbox.shared.isListening && isSteppingAside) {
                AppLog.app.info("Assistant call handed over; app stepped aside")
                moveTaskToBack(true)
            } else {
                showAfterAssistant()
            }
        }
    }

    /**
     * Started only to make the call's store, the app stays see-through and still: the panel over
     * the app the user was in is what they see, not the app opening and leaving.
     */
    private fun hideForAssistant() {
        isSteppingAside = true
        windowAnimations = window.attributes.windowAnimations
        window.setWindowAnimations(0)
        window.attributes = window.attributes.also { it.alpha = 0f }
    }

    private fun showAfterAssistant() {
        if (!isSteppingAside) return
        isSteppingAside = false
        window.attributes = window.attributes.also { it.alpha = 1f }
        window.setWindowAnimations(windowAnimations)
    }

    companion object {
        /** Android only: the assistant opens the app to start its call, then the app steps aside. */
        const val ACTION_ASSISTANT_CALL = "ai.opencode.action.ASSISTANT_CALL"
        private const val ASSISTANT_CALL_BUDGET_MS = 10_000L

        /** Android only: the assistant panel opens Settings at the page it needs (`EXTRA_SETTINGS_PAGE`). */
        const val ACTION_OPEN_SETTINGS = "ai.opencode.action.OPEN_SETTINGS"
        const val EXTRA_SETTINGS_PAGE = "ai.opencode.extra.SETTINGS_PAGE"
        const val SETTINGS_PAGE_VOICE = "voice"
        const val SETTINGS_PAGE_EXPERIMENTAL = "experimental"

        private fun settingsPage(name: String?): SettingsPage? = when (name) {
            SETTINGS_PAGE_VOICE -> SettingsPage.Voice
            SETTINGS_PAGE_EXPERIMENTAL -> SettingsPage.Experimental()
            else -> null
        }

        /** A notification tap, an `opencode://` system action link, or a pairing link. */
        fun route(intent: Intent?): AppFeature.Action? {
            intent ?: return null
            if (intent.action == NotificationDestination.ACTION_OPEN) {
                val server = intent.getStringExtra(NotificationDestination.EXTRA_SERVER) ?: return null
                val session = intent.getStringExtra(NotificationDestination.EXTRA_SESSION) ?: return null
                return AppFeature.Action.NotificationOpened(NotificationDestination(server, session))
            }
            if (intent.action == ACTION_OPEN_SETTINGS) {
                return settingsPage(intent.getStringExtra(EXTRA_SETTINGS_PAGE))?.let { AppFeature.Action.SettingsPageRequested(it) }
            }
            if (intent.action != Intent.ACTION_VIEW) return null
            val url = intent.dataString ?: return null
            val host = intent.data?.host?.lowercase()
            if (host == "scrawl" || host == "tether") return null
            // Continue in OpenCode: the share waiting in Shares/ says where to go.
            if (ShareConversation.isContinueURL(url)) return AppFeature.Action.PendingSharesRequested
            SystemAction.fromUrl(url)?.let { return AppFeature.Action.SystemActionReceived(it) }
            if (!OpenCodePairingLink.shouldHandleOpenURL(url)) return null
            return AppFeature.Action.PairingURLOpened(url)
        }
    }
}

