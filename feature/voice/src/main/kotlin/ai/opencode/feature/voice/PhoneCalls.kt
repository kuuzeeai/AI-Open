package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.annotation.SuppressLint
import android.media.AudioManager
import android.telecom.TelecomManager
import kotlinx.coroutines.delay

/**
 * Answers and ends phone calls for [PhoneToolsClient] (Android only), through Telecom with the
 * Answer Calls permission. Android lets an app read the call state only with Phone permission,
 * so the audio mode (ringing, in a call) tells whether something rang and whether it was answered.
 */
internal object PhoneCalls {
    private const val ANSWER_WAIT_MILLIS = 2_000L
    private const val POLL_MILLIS = 100L

    // acceptRingingCall is deprecated for in-call apps, not for apps holding Answer Calls; it still works.
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    suspend fun answer(): PhoneTools.Result {
        val context = dependency(AppContext)
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return PhoneTools.Result.failure("This phone can’t answer calls.")
        PhoneAccess.require(context, PhoneAccess.answerCalls.permissions, "answer calls")?.let { return it }
        val audio = context.getSystemService(AudioManager::class.java)
        val wasInCall = isInCall(audio)
        telecom.acceptRingingCall()
        if (wasInCall) {
            // A second call waiting: the mode stays “in a call” either way, so the phone can't tell.
            return PhoneTools.Result(
                "Asked the phone to answer the waiting call, if one was ringing. The other call is now on hold.",
                action = PhoneTools.Action.other("Answered the call"),
            )
        }
        var waited = 0L
        while (waited < ANSWER_WAIT_MILLIS) {
            if (isInCall(audio)) {
                return PhoneTools.Result("Answered the call.", action = PhoneTools.Action.other("Answered the call"))
            }
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        return PhoneTools.Result.failure("No call was answered: nothing is ringing.")
    }

    // endCall is deprecated for in-call apps, not for apps holding Answer Calls; it still works.
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun end(): PhoneTools.Result {
        val context = dependency(AppContext)
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return PhoneTools.Result.failure("This phone can’t end calls.")
        PhoneAccess.require(context, PhoneAccess.answerCalls.permissions, "end calls")?.let { return it }
        val wasInCall = isInCall(context.getSystemService(AudioManager::class.java))
        if (!telecom.endCall()) {
            return PhoneTools.Result.failure(
                "No call was ended: there’s no phone call OpenCode can end. It can’t end emergency calls, or calls in other apps such as WhatsApp.",
            )
        }
        if (wasInCall) return PhoneTools.Result("Hung up the call.", action = PhoneTools.Action.other("Hung up"))
        return PhoneTools.Result("Declined the ringing call.", action = PhoneTools.Action.other("Declined the call"))
    }

    /** A phone call is up. Not MODE_IN_COMMUNICATION: that's VoIP, including OpenCode's own voice calls. */
    private fun isInCall(audio: AudioManager?): Boolean {
        val mode = audio?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL
    }
}
