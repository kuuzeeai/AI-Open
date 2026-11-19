package ai.opencode.app.assistant

import ai.opencode.app.MainActivity
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.system.intents.AskOpenCodeIntent
import ai.opencode.feature.system.intents.SystemPromptSender
import ai.opencode.feature.voice.AssistantCallChips
import ai.opencode.feature.voice.AssistantCallMorph
import ai.opencode.feature.voice.AssistantCallPanel
import ai.opencode.feature.voice.AssistantGlow
import ai.opencode.feature.voice.AssistantNoticeButtons
import ai.opencode.feature.voice.AssistantNoticePanel
import ai.opencode.feature.voice.PhoneTools
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.rememberAssistantMorphState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** What the overlay asks of [OpenCodeAssistantSession] beyond the call itself. */
class AssistantOverlayActions(
    val panel: AssistantCallPanel.Actions,
    val openSettings: (String) -> Unit,
    val openApp: () -> Unit,
    val dismiss: () -> Unit,
    /** Yes or no on a phone tool's confirm card. */
    val answer: (Boolean) -> Unit,
    /** The parts of the window that take touches; none lets every touch through to the app behind. */
    val touchable: (List<Rect>) -> Unit,
    /** The bar has sat through a quiet spell. */
    val quiet: () -> Unit,
    /** The call needs an answer or failed while the panel was the pill. */
    val needsUser: () -> Unit,
)

/**
 * Everything the side button keeps on screen: a glow rising from the bottom edge in the call's
 * colour, and above it the call panel (bar or pill) or a notice when no call can start.
 * It rises from the bottom edge when shown and sinks back when it goes, while OpenCode itself
 * is on screen (which shows the call its own way), or while the phone is locked. Only the
 * panel takes touches; the rest of the window lets them through.
 */
@Composable
fun AssistantOverlay(
    flow: AssistantFlow.State,
    voice: VoiceFeature.State?,
    actions: AssistantOverlayActions,
) {
    val reduceMotion = rememberReduceMotion()
    // A phone tool's confirm card shows over everything else, even once the panel had gone.
    val isConfirming = flow.isAsking && !flow.isLocked
    val isCall = flow.stage == AssistantFlow.Stage.Call && !isConfirming
    val hidden = !isConfirming && (flow.finished || flow.isAway || (isCall && flow.isAppShown && !flow.isAppStartingCall))
    val appear = remember { Animatable(0f) }
    val morph = rememberAssistantMorphState(flow.form, flow.side)
    LaunchedEffect(hidden) {
        // Out of sight the panel takes the form it should rise in, so it never morphs on the way up.
        if (!hidden && appear.value < 0.05f) morph.snap(flow.form)
        val role = if (hidden) Motion.panelClose else Motion.panelOpen
        appear.animateTo(if (hidden) 0f else 1f, role.animation(reduceMotion = reduceMotion))
    }
    // A quiet call shrinks to the pill: no touch on the panel, nobody speaking, no new words.
    // Not while typing (or the keyboard is up), connecting, or showing what went wrong.
    val imeShown = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val canShrink = isCall && !hidden && flow.form == AssistantCallPanel.Form.bar && !flow.typing.isOpen &&
        !imeShown && AssistantCallPanel.canShrinkWhenQuiet(voice) && voice?.isAssistantSpeaking != true
    val lastWords = AssistantCallPanel.lastLine(voice)?.text
    LaunchedEffect(canShrink, morph.touches, flow.shows, lastWords, voice?.statusText) {
        if (!canShrink) return@LaunchedEffect
        delay(AssistantCallPanel.quietMillis)
        actions.quiet()
    }
    // The call wanting the user brings the bar back; replies, speaking and thinking don't.
    val needsUser = isCall && AssistantCallPanel.needsUser(voice)
    // Checked again each time the panel comes back (after the app, unlock or a phone call), not on a
    // manual minimise, so the user can still put it away.
    LaunchedEffect(needsUser, hidden) {
        if (needsUser && !hidden) actions.needsUser()
    }
    // TalkBack hears the panel change size once, not each status after.
    val view = LocalView.current
    var announcedForm by remember { mutableStateOf(flow.form) }
    LaunchedEffect(flow.form, hidden) {
        if (hidden) {
            announcedForm = flow.form
            return@LaunchedEffect
        }
        if (isCall && flow.form != announcedForm) {
            val text = if (flow.form == AssistantCallPanel.Form.pill) "OpenCode call minimised" else "OpenCode call expanded"
            @Suppress("DEPRECATION")
            view.announceForAccessibility(text)
        }
        announcedForm = flow.form
    }

    var panelHeight by remember { mutableStateOf(0f) }
    var morphOrigin by remember { mutableStateOf(Offset.Zero) }
    var chips by remember { mutableStateOf(Rect.Zero) }
    var notice by remember { mutableStateOf(Rect.Zero) }

    // The window takes touches only on the panel, and only once it has risen. The flow reads
    // the current values: a value captured when it started would keep a stale region.
    val isHidden by rememberUpdatedState(hidden)
    val isCallShown by rememberUpdatedState(isCall)
    LaunchedEffect(Unit) {
        snapshotFlow {
            val rects = mutableListOf<Rect>()
            if (appear.value > 0.95f && !isHidden) {
                if (isCallShown) {
                    if (!morph.surface.isEmpty) rects.add(morph.surface.translate(morphOrigin))
                    if (morph.form == AssistantCallPanel.Form.bar && !chips.isEmpty) rects.add(chips)
                } else if (!notice.isEmpty) {
                    rects.add(notice)
                }
            }
            rects.toList()
        }.collect { actions.touchable(it) }
    }

    val glow = if (isCall) AssistantCallPanel.glowColor(voice) else AssistantCallPanel.idleGlowColor()
    Box(Modifier.fillMaxSize()) {
        AssistantGlow(
            color = glow,
            presence = { appear.value * (if (isCall) morph.presence else 1f) },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.4f).heightIn(max = 300.dp),
        )
        Column(
            Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime).union(WindowInsets.displayCutout))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .onGloballyPositioned { panelHeight = it.size.height.toFloat() }
                .graphicsLayer {
                    val shown = appear.value
                    // Rises from just below the screen's edge; under Reduce Motion it only fades.
                    translationY = if (reduceMotion) 0f else (1f - shown) * (panelHeight + 48.dp.toPx())
                    alpha = if (reduceMotion) shown else (shown * 2f).coerceAtMost(1f)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val card = flow.asking
            if (isConfirming && card != null) {
                val copy = ConfirmCopy.of(card)
                AssistantNoticePanel(
                    copy.symbol,
                    copy.title,
                    copy.body,
                    Modifier.onGloballyPositioned { notice = it.boundsInWindow() },
                ) {
                    AssistantNoticeButtons {
                        OpenCodeSecondaryButton(copy.no, onClick = { actions.answer(false) })
                        OpenCodePrimaryButton(copy.yes, onClick = { actions.answer(true) })
                    }
                }
            } else when (flow.stage) {
                AssistantFlow.Stage.Call -> {
                    AssistantCallChips(
                        flow.phoneActions,
                        morph,
                        actions.panel,
                        Modifier.onGloballyPositioned { chips = it.boundsInWindow() },
                    )
                    Spacer(Modifier.height(8.dp))
                    AssistantCallMorph(
                        state = voice,
                        morph = morph,
                        form = flow.form,
                        typing = flow.typing,
                        actions = actions.panel,
                        modifier = Modifier.onGloballyPositioned { morphOrigin = it.positionInWindow() },
                    )
                }
                AssistantFlow.Stage.TurnedOff -> AssistantNoticePanel(
                    SFSymbol.Power,
                    "Assistant Is Off",
                    "Talking to OpenCode from the side button is an experiment. Turn on Assistant in Settings › Experimental.",
                    Modifier.onGloballyPositioned { notice = it.boundsInWindow() },
                ) {
                    AssistantNoticeButtons {
                        OpenCodeSecondaryButton("Cancel", onClick = actions.dismiss)
                        OpenCodePrimaryButton("Open Settings", onClick = { actions.openSettings(MainActivity.SETTINGS_PAGE_EXPERIMENTAL) })
                    }
                }
                AssistantFlow.Stage.NoServer -> AssistantNoticePanel(
                    SFSymbol.Waveform,
                    "Connect a Server",
                    SystemPromptSender.Failure.noServer.message,
                    Modifier.onGloballyPositioned { notice = it.boundsInWindow() },
                ) {
                    AssistantNoticeButtons {
                        OpenCodeSecondaryButton("Cancel", onClick = actions.dismiss)
                        OpenCodePrimaryButton("Open OpenCode", onClick = actions.openApp)
                    }
                }
                AssistantFlow.Stage.PhoneCall -> AssistantNoticePanel(
                    SFSymbol.Phone,
                    "You’re on a Phone Call",
                    "Talk to OpenCode once the call is over.",
                    Modifier.onGloballyPositioned { notice = it.boundsInWindow() },
                ) {
                    AssistantNoticeButtons {
                        OpenCodePrimaryButton("OK", onClick = actions.dismiss)
                    }
                }
                AssistantFlow.Stage.NeedsVoice -> NeedsVoice(
                    flow.typing,
                    actions,
                    Modifier.onGloballyPositioned { notice = it.boundsInWindow() },
                )
            }
        }
    }
}

@Composable
private fun NeedsVoice(typing: AssistantCallPanel.Typing, actions: AssistantOverlayActions, modifier: Modifier) {
    val sent = typing.result != null && !typing.failed
    AssistantNoticePanel(
        SFSymbol.MicSlash,
        "Set Up Voice",
        if (sent) null else "Calls need a voice model. Set one up in Settings › Voice, or type a message to your session instead.",
        modifier,
    ) {
        val result = typing.result
        if (sent && result != null) {
            Text(
                result,
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@AssistantNoticePanel
        }
        OpenCodeTextField(
            value = typing.message,
            onValueChange = actions.panel.message,
            placeholder = AskOpenCodeIntent.REQUEST_VALUE_DIALOG,
            minLines = 2,
            maxLines = 5,
            enabled = !typing.isSending,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        if (result != null) {
            Text(
                result,
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        AssistantNoticeButtons {
            OpenCodeSecondaryButton("Set Up Voice", onClick = { actions.openSettings(MainActivity.SETTINGS_PAGE_VOICE) })
            OpenCodePrimaryButton("Send", onClick = actions.panel.send, enabled = typing.canSend)
        }
    }
}

/** What a phone tool's confirm card says: the exact person, number and message, before anything happens. */
internal data class ConfirmCopy(val symbol: SFSymbol, val title: String, val body: String, val no: String, val yes: String) {
    companion object {
        fun of(card: PhoneTools.Confirm): ConfirmCopy = when (card) {
            is PhoneTools.Confirm.PhoneCall -> ConfirmCopy(
                SFSymbol.Phone,
                "Call ${card.contact ?: card.number}?",
                if (card.contact == null) "OpenCode wants to call this number." else card.number,
                "Cancel",
                "Call",
            )
            is PhoneTools.Confirm.Text -> ConfirmCopy(
                SFSymbol.BubbleLeft,
                "Text ${card.contact ?: card.number}?",
                if (card.contact == null) "“${card.message}”" else "${card.number} · “${card.message}”",
                "Don’t Send",
                "Send",
            )
            PhoneTools.Confirm.Notifications -> ConfirmCopy(
                SFSymbol.Bell,
                "Share Your Notifications?",
                "OpenCode wants to read the notifications on your phone for the session.",
                "Not Now",
                "Share",
            )
            is PhoneTools.Confirm.NotificationReply -> ConfirmCopy(
                SFSymbol.BubbleLeft,
                if (card.conversation == null) "Reply in ${card.app}?" else "Reply to ${card.conversation}?",
                if (card.conversation == null) "“${card.text}”" else "${card.app} · “${card.text}”",
                "Don’t Send",
                "Send",
            )
            is PhoneTools.Confirm.NotificationAction -> ConfirmCopy(
                SFSymbol.HandTap,
                "Tap “${card.button}”?",
                if (card.title == null) "On a ${card.app} notification." else "${card.app} · ${card.title}",
                "Cancel",
                "Tap",
            )
            is PhoneTools.Confirm.Texts -> ConfirmCopy(
                SFSymbol.TextBubble,
                if (card.from == null) "Read Your Texts?" else "Read Texts From ${card.from}?",
                if (card.from == null) {
                    "OpenCode wants to read the texts on your phone for the session."
                } else {
                    "OpenCode wants to read your texts with ${card.from} for the session."
                },
                "Not Now",
                "Allow",
            )
            is PhoneTools.Confirm.CallLog -> ConfirmCopy(
                SFSymbol.Phone,
                if (card.from == null) "Read Your Recent Calls?" else "Read Calls With ${card.from}?",
                "OpenCode wants to read the call log on your phone for the session.",
                "Not Now",
                "Allow",
            )
            is PhoneTools.Confirm.DeleteEvent -> ConfirmCopy(
                SFSymbol.Trash,
                if (card.title == null) "Delete This Event?" else "Delete “${card.title}”?",
                card.shown ?: "OpenCode wants to delete an event from your calendar.",
                "Keep",
                "Delete",
            )
        }
    }
}
