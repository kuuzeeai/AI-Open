package ai.opencode.feature.voice

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Android only: the assistant call's safety net trigger (shots/assistant/eval/round2 `claims.py`). */
class VoiceActionPhoneClaimTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun phonePhrasesCount() {
        assertTrue(VoiceActionClaim.isPhoneClaim("Setting a ten minute timer."))
        assertTrue(VoiceActionClaim.isPhoneClaim("One sec. Spotify's up."))
        assertTrue(VoiceActionClaim.isPhoneClaim("Yeah, it's set for 8:30."))
        assertTrue(VoiceActionClaim.isPhoneClaim("I'll remind you."))
        assertTrue(VoiceActionClaim.isPhoneClaim("Okay, asking it to set a fifteen-minute reminder for you right now."))
        assertTrue(VoiceActionClaim.isPhoneClaim("I got you."))
    }

    @Test fun aLongReplyCannotHideAClaimAtItsStart() {
        val text = "On it. Pasta is a great call tonight, and ten minutes is usually about right for most dried shapes."
        assertFalse(VoiceActionClaim.isClaim(text))
        assertTrue(VoiceActionClaim.isPhoneClaim(text))
    }

    @Test fun questionsAndPlainTalkDoNot() {
        assertFalse(VoiceActionClaim.isPhoneClaim("Want me to set an alarm?"))
        assertFalse(VoiceActionClaim.isPhoneClaim("How long should the timer be?"))
        assertFalse(VoiceActionClaim.isPhoneClaim("Big morning coming"))
        assertFalse(VoiceActionClaim.isPhoneClaim("Sure thing, what time works for you"))
    }
}
