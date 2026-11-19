package ai.opencode.app.assistant

import ai.opencode.core.log.AppLog
import ai.opencode.feature.voice.ScreenRead
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android only: OpenCode as the phone's assistant. The system keeps this bound while OpenCode
 * holds the assistant role and opens an [OpenCodeAssistantSession] each time the side button is
 * held; there's no hotword. Being bound by the system is also what lets the call's phone tools
 * open the Clock or another app while OpenCode itself is in the background.
 */
class OpenCodeAssistantService : VoiceInteractionService() {
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Watches for the unlock while the screen is on and locked. USER_PRESENT alone isn't enough:
     * it's a queued broadcast that can arrive many seconds after the unlock.
     */
    private val unlockCheck = object : Runnable {
        override fun run() {
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (keyguard?.isKeyguardLocked == true) {
                handler.postDelayed(this, unlockPoll)
            } else {
                AssistantFlow.shared.locked(false)
            }
        }
    }

    /** Keeps the panel off the lock screen: it sinks when the screen goes off and the pill returns on unlock. */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val flow = AssistantFlow.shared
            handler.removeCallbacks(unlockCheck)
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> flow.locked(true)
                Intent.ACTION_USER_PRESENT -> flow.locked(false)
                Intent.ACTION_SCREEN_ON -> {
                    // Only a call the panel follows needs the unlock.
                    if (flow.state.value.followsCall) unlockCheck.run()
                }
            }
        }
    }

    /** A ringing or answered phone call puts the phone app's controls where the pill sits. */
    private val audioMode = AudioManager.OnModeChangedListener { mode ->
        AssistantFlow.shared.phoneCall(isPhoneCall(mode))
    }

    override fun onReady() {
        super.onReady()
        current = WeakReference(this)
        getSystemService(AudioManager::class.java)?.let { audio ->
            audio.addOnModeChangedListener(mainExecutor, audioMode)
            AssistantFlow.shared.phoneCall(isPhoneCall(audio.mode))
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
        PhoneToolLab.register(this)
        AppLog.app.info("Assistant service ready")
    }

    override fun onShutdown() {
        handler.removeCallbacks(unlockCheck)
        runCatching { unregisterReceiver(screen) }
        getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(audioMode)
        current = null
        super.onShutdown()
    }

    companion object {
        private var current: WeakReference<OpenCodeAssistantService>? = null
        private const val unlockPoll = 250L

        /** Active phone call modes where cellular audio holds the microphone and the phone UI is up. */
        internal fun isPhoneCall(mode: Int): Boolean = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_CALL_SCREENING ||
            mode == AudioManager.MODE_CALL_REDIRECT

        /** Active or ringing phone call (used to avoid popping an unlock prompt over an incoming call). */
        internal fun isCallActiveOrRinging(context: Context): Boolean {
            val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
            return mode == AudioManager.MODE_RINGTONE || isPhoneCall(mode)
        }

        /** Opens the assistant's panel again, as a press of the side button would (after unlocking). */
        fun show(): Boolean {
            val service = current?.get() ?: return false
            return runCatching { service.showSession(Bundle(), 0) }.isSuccess
        }

        /** Shows the panel for the call again; false when there's no service to show it with. */
        fun showCall(): Boolean {
            if (OpenCodeAssistantSession.isShowing) return true
            val service = current?.get() ?: return false
            val args = Bundle().apply { putBoolean(OpenCodeAssistantSession.EXTRA_CALL, true) }
            return runCatching { service.showSession(args, 0) }
                .onFailure { AppLog.app.error("Assistant couldn’t show the call again") }
                .isSuccess
        }

        /**
         * The screen right now, from Android's assist data, for `read_screen`: no prompt and
         * nothing shown. Null when OpenCode isn't the assistant or Android sent nothing in time;
         * a part is missing when Android withholds it (Samsung sends third-party assistants none).
         */
        suspend fun readScreen(): ScreenRead? {
            val service = current?.get() ?: return null
            val read = CompletableDeferred<ScreenRead>()
            OpenCodeAssistantSession.pendingScreen = read
            val args = Bundle().apply { putBoolean(OpenCodeAssistantSession.EXTRA_SCREEN, true) }
            val flags = VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
            val asked = runCatching { service.showSession(args, flags) }.isSuccess
            if (!asked) AppLog.app.error("Assistant couldn’t ask for the screen")
            val result = if (asked) withTimeoutOrNull(screenWait) { read.await() } else null
            if (OpenCodeAssistantSession.pendingScreen === read) OpenCodeAssistantSession.pendingScreen = null
            return result
        }

        /** A little longer than the session gives Android, so the session answers first. */
        private const val screenWait = 3_000L
    }
}

/** Opens a session for each press of the side button. */
class OpenCodeAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = OpenCodeAssistantSession(this)
}

/**
 * The speech service every assistant has to name. OpenCode's voice is the call itself, so this
 * only declines; `selectableAsDefault="false"` keeps it out of the phone's speech settings.
 */
class OpenCodeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
