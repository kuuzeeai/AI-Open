package ai.opencode.app.assistant

import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.system.LeftToRight
import ai.opencode.feature.voice.AssistantCallPanel
import ai.opencode.feature.voice.AssistantGlow
import ai.opencode.feature.voice.AssistantNoticeButtons
import ai.opencode.feature.voice.AssistantNoticePanel
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Android only: the side button pressed while the phone is locked. The assistant isn't used
 * on the lock screen, so this shows Unlock to Talk over it and asks for the unlock; once the
 * phone is unlocked the assistant's panel opens again ([OpenCodeAssistantService.show]) and
 * starts the call.
 */
class AssistantActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(LeftToRight.configuration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true
        if (savedInstanceState == null) unlock()
        setContent {
            OpenCodeAppTheme {
                val reduceMotion = rememberReduceMotion()
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, Motion.panelOpen.animation(reduceMotion = reduceMotion)) }
                Box(Modifier.fillMaxSize().clickable(onClick = ::finish, indication = null, interactionSource = null)) {
                    AssistantGlow(
                        AssistantCallPanel.idleGlowColor(),
                        presence = { appear.value },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(300.dp),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .graphicsLayer {
                                val shown = appear.value
                                alpha = shown.coerceIn(0f, 1f)
                                if (!reduceMotion) translationY = (1f - shown) * 48.dp.toPx()
                            },
                    ) {
                        AssistantNoticePanel(SFSymbol.LockFill, "Unlock to Talk", "Unlock your phone to start a call with OpenCode.") {
                            AssistantNoticeButtons {
                                OpenCodeSecondaryButton("Cancel", onClick = ::finish)
                                OpenCodePrimaryButton("Unlock", onClick = ::unlock)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun unlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            reopen()
            return
        }
        AppLog.app.notice("Assistant asked to unlock")
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = reopen()
            },
        )
    }

    private fun reopen() {
        if (!OpenCodeAssistantService.show()) AppLog.app.error("Assistant couldn’t open after unlocking")
        finish()
    }
}
