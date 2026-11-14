package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeHairline
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android only: the panel the side button keeps over whatever app is on screen for the whole
 * call (OpenCode as the phone's assistant). One surface takes two forms: the bar (the call's
 * orb, what it's doing, the last words said, Mute, End and a message box) and the pill it
 * shrinks to (just the orb, in a circle, as the in-app call rests). The surface follows a drag
 * between them and springs to the nearest when let go.
 */
object AssistantCallPanel {

    /** The panel's forms, in order; [value] is where each sits on the morph's progress. */
    enum class Form(val value: Float) {
        pill(0f),
        bar(1f),
        ;

        companion object {
            fun nearest(progress: Float): Form = if (progress < 0.5f) pill else bar

            /**
             * Where a released drag goes: a flick ([direction] -1 down, 1 up) goes that way,
             * anything slower to the nearest form.
             */
            fun settling(progress: Float, direction: Int): Form {
                if (direction > 0) return bar
                if (direction < 0) return pill
                return nearest(progress)
            }
        }
    }

    /** The message box: typing to the call's session instead of speaking. */
    data class Typing(
        val isOpen: Boolean = false,
        val message: String = "",
        val isSending: Boolean = false,
        val result: String? = null,
        val failed: Boolean = false,
    ) {
        val canSend: Boolean get() = !isSending && message.isNotBlank()
    }

    /** What the panel asks of its host. */
    class Actions(
        val call: (VoiceFeature.Action) -> Unit,
        /** The panel was dragged, tapped or swiped to [Form]. */
        val form: (Form) -> Unit,
        /** The pill was dragged to a side: -1 start, 0 centre, 1 end. */
        val side: (Float) -> Unit,
        val openCall: () -> Unit,
        val openAction: (PhoneTools.Action) -> Unit,
        val toggleTyping: () -> Unit,
        val message: (String) -> Unit,
        val send: () -> Unit,
    )

    /** The panel's corners: rounder than a dialog, as a surface floating over another app. */
    val radius: Dp = 16.dp
    val shape = RoundedCornerShape(radius)

    /** How far a drag travels between the bar and the pill. */
    internal val minimiseDistance: Dp = 96.dp

    /** How long a tool's chip stays above the bar. */
    internal const val chipMillis = 30_000L

    /** How long a tool's chip shows above the pill. */
    internal const val pillChipMillis = 4_000L

    /** A call quiet this long (no touch, nobody speaking, no new words) shrinks to the pill. */
    const val quietMillis = 10_000L

    /** Whether the call wants the user: it needs an answer, or it failed or dropped. */
    fun needsUser(state: VoiceFeature.State?): Boolean {
        if (state == null) return false
        if (mood(state) == FloatingCallMood.needsInput) return true
        return state.phase is VoiceFeature.State.Phase.Failed || state.phase == VoiceFeature.State.Phase.Dropped
    }

    /** Whether the call can shrink by itself: it's live and nothing on it wants the user. */
    fun canShrinkWhenQuiet(state: VoiceFeature.State?): Boolean =
        state != null && state.phase == VoiceFeature.State.Phase.Live && !state.isReconnecting && !needsUser(state)

    /** The call's mood, as the orb shows it. */
    fun mood(state: VoiceFeature.State?): FloatingCallMood = if (state == null) FloatingCallMood.connecting else floatingCallMood(state)

    /** The status in a word or two: where a call connects goes on the line under it. */
    fun status(state: VoiceFeature.State?): String = when {
        state == null || state.phase == VoiceFeature.State.Phase.Connecting -> "Connecting…"
        // The failure itself goes on the second line; the status just says the call is over.
        state.phase is VoiceFeature.State.Phase.Failed -> if (state.connectedAt == null) "Couldn’t connect" else "Call ended"
        else -> state.statusText
    }

    /** The bar's second line before anyone's said anything. */
    fun hint(state: VoiceFeature.State?): String {
        val place = state?.callPlace
        return if (state?.phase == VoiceFeature.State.Phase.Connecting && place != null) "Via ${place.name}" else "Go ahead"
    }

    fun orb(state: VoiceFeature.State?): LivingOrbConfig = LivingOrbConfig(
        mood = mood(state),
        isMuted = state?.isMuted == true,
        isSpeaking = state?.isAssistantSpeaking == true,
    )

    /** The glow's colour: the orb's own colour for what the call is doing now, grey once it ends. */
    fun glowColor(state: VoiceFeature.State?): Color {
        val config = orb(state)
        if (config.mood == FloatingCallMood.ended) return Color(0.55f, 0.55f, 0.57f)
        val (base, _) = WispModeMix.palette(config.wispState(0f), 0f)
        return Color(base.x.coerceIn(0f, 1f), base.y.coerceIn(0f, 1f), base.z.coerceIn(0f, 1f))
    }

    /** The glow behind a panel that isn't a call yet: the resting orb's colour. */
    fun idleGlowColor(): Color {
        val (base, _) = WispModeMix.palette(WispOrbState.connecting, 0f)
        return Color(base.x.coerceIn(0f, 1f), base.y.coerceIn(0f, 1f), base.z.coerceIn(0f, 1f))
    }

    /** The last line said, either way: the bar's second line. */
    fun lastLine(state: VoiceFeature.State?): CaptionTarget? =
        state?.captionTargets.orEmpty().lastOrNull { it.text.isNotBlank() }

    /** Why the call couldn't start or went, when it says. */
    fun failure(state: VoiceFeature.State?): String? = (state?.phase as? VoiceFeature.State.Phase.Failed)?.message

    /**
     * What TalkBack reads for the bar's main area or the pill, one element: the call, its status, how long
     * it's run ([seconds], null before it connects) and the line under the status.
     */
    fun barLabel(status: String, seconds: Long?, line: String): String {
        var label = "OpenCode call, $status"
        if (seconds != null) label += ", %d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
        if (line.isNotBlank()) label += ". $line"
        return label
    }
}

/** Whole seconds since [connectedAt], ticking each second; null while there's no start. */
@Composable
private fun rememberCallSeconds(connectedAt: Instant?): Long? {
    val date = remember { dependency(DateClient) }
    var seconds by remember(connectedAt) { mutableStateOf<Long?>(null) }
    LaunchedEffect(connectedAt) {
        if (connectedAt == null) return@LaunchedEffect
        while (true) {
            val millis = Duration.between(connectedAt, date.now()).toMillis()
            seconds = max(0L, millis / 1000)
            delay(1_000 - Math.floorMod(millis, 1_000L))
        }
    }
    return seconds
}

/**
 * Where the panel is between its forms. [progress] runs from 0 (pill) to 1 (bar); a drag moves
 * it directly and letting go springs it to a form, so the surface is one shape the whole time
 * and can be caught and reversed mid-way.
 */
@Stable
class AssistantMorphState(form: AssistantCallPanel.Form, side: Float) {
    val progress = Animatable(form.value)
    val side = Animatable(side)
    val typing = Animatable(0f)

    /** The form the panel is at or springing to. */
    var form by mutableStateOf(form)
        private set

    /** The surface's rectangle in the morph's own coordinates: all of the morph that takes touches. */
    var surface by mutableStateOf(Rect.Zero)
        internal set

    internal var radius by mutableStateOf(0f)
    /** Counts touches on the panel: each one restarts the quiet timer. */
    var touches by mutableStateOf(0)
        internal set
    internal var dragProgress = form.value
    internal var dragSide = side
    internal var sideTravel = 0f

    /** How much of the panel is up, 0 for the pill: the glow follows it. */
    val presence: Float get() = progress.value.coerceIn(0f, 1f)

    /** Jumps to [to] with no motion: the panel is about to rise into view already in that form. */
    suspend fun snap(to: AssistantCallPanel.Form) {
        form = to
        dragProgress = to.value
        progress.snapTo(to.value)
    }

    internal suspend fun settle(to: AssistantCallPanel.Form, velocity: Float, reduceMotion: Boolean, role: MotionRole) {
        form = to
        progress.animateTo(
            to.value,
            animationSpec = role.animation(reduceMotion = reduceMotion),
            initialVelocity = role.initialVelocity(velocity, reduceMotion),
        )
    }
}

@Composable
fun rememberAssistantMorphState(form: AssistantCallPanel.Form, side: Float): AssistantMorphState =
    remember { AssistantMorphState(form, side) }

/** A floating surface over another app: floating fill, a hairline, a soft shadow and 16 dp corners. */
@Composable
private fun AssistantSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = AssistantCallPanel.shape
    val isDark = OpenCodeTheme.colors.isDark
    Column(
        modifier
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = if (isDark) 0.5f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (isDark) 0.6f else 0.16f),
            )
            .background(OpenCodeSurfaces.color, shape)
            .openCodeHairline(shape)
            // Touches on the panel stay on it.
            .clickable(onClick = {}, indication = null, interactionSource = null),
        content = content,
    )
}

/**
 * A calm light rising from the bottom edge in the call's colour (OpenCode's take on the
 * assistant glow). It breathes slowly unless Reduce Motion is on; [presence] (0 to 1, read
 * while drawing) fades it with the panel.
 */
@Composable
fun AssistantGlow(color: Color, presence: () -> Float, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val tint by animateColorAsState(color, tween(if (reduceMotion) 0 else 700), label = "assistantGlowTint")
    val breath = if (reduceMotion) {
        null
    } else {
        val transition = rememberInfiniteTransition(label = "assistantGlowBreath")
        transition.animateFloat(
            initialValue = 0.78f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "assistantGlowBreathValue",
        )
    }
    val isDark = OpenCodeTheme.colors.isDark
    // Light pages need a deeper colour to show at all; dark ones a softer one to stay calm.
    val glow = if (isDark) tint else Color(tint.red * tint.red, tint.green * tint.green, tint.blue * tint.blue)
    Canvas(modifier) {
        val strength = (if (isDark) 0.38f else 0.5f) * presence().coerceIn(0f, 1f) * (breath?.value ?: 1f)
        if (strength <= 0f) return@Canvas
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to glow.copy(alpha = strength * 0.45f)))
        // A wide ellipse centred just below the screen's edge.
        scale(scaleX = 1.9f, scaleY = 1f, pivot = Offset(size.width / 2f, size.height)) {
            val radius = size.height * 0.95f
            drawCircle(
                Brush.radialGradient(
                    0f to glow.copy(alpha = strength),
                    0.55f to glow.copy(alpha = strength * 0.35f),
                    1f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height * 1.08f),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(size.width / 2f, size.height * 1.08f),
            )
        }
    }
}

/**
 * The call panel: one surface that is the bar or the pill, following [form] (the host's wish:
 * the side button, Home, a touch on the app behind, a quiet call) and the user's finger.
 * Dragging the bar down makes it the pill: the surface shrinks into a circle and the orb grows
 * to fill it, in the in-app resting orb's card, edge and shadow. The pill drags to either side,
 * and taps or drags back up to the bar.
 */
@Composable
fun AssistantCallMorph(
    state: VoiceFeature.State?,
    morph: AssistantMorphState,
    form: AssistantCallPanel.Form,
    typing: AssistantCallPanel.Typing,
    actions: AssistantCallPanel.Actions,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Pixels of drag between the bar and the pill.
    val step = with(density) { AssistantCallPanel.minimiseDistance.toPx() }
    suspend fun springTo(to: AssistantCallPanel.Form) {
        val role = if (to.value > morph.progress.value) Motion.panelOpen else Motion.panelClose
        morph.settle(to, 0f, reduceMotion, role)
    }
    LaunchedEffect(form) {
        if (morph.form == form) return@LaunchedEffect
        springTo(form)
    }
    LaunchedEffect(typing.isOpen) {
        morph.typing.animateTo(if (typing.isOpen) 1f else 0f, Motion.layout.animation(reduceMotion = reduceMotion))
    }

    fun go(to: AssistantCallPanel.Form) {
        actions.form(to)
        scope.launch { springTo(to) }
    }

    // Deltas can arrive faster than snaps land, so the drag keeps its own running position.
    val vertical = rememberDraggableState { delta ->
        morph.dragProgress = (morph.dragProgress - delta / step).coerceIn(0f, 1f)
        val next = morph.dragProgress
        scope.launch { morph.progress.snapTo(next) }
    }
    val horizontal = rememberDraggableState { delta ->
        val travel = morph.sideTravel
        if (travel > 0f) {
            morph.dragSide = (morph.dragSide + delta / travel).coerceIn(-1f, 1f)
            val next = morph.dragSide
            scope.launch { morph.side.snapTo(next) }
        }
    }
    val isPill = morph.form == AssistantCallPanel.Form.pill
    val colors = OpenCodeTheme.colors
    val isDark = colors.isDark
    // The pill wears the in-app resting orb's card, edge and shadow; the bar the floating panel's.
    val pillFill = VoiceCallSurfaceDefaults.card
    val barFill = OpenCodeSurfaces.color
    val pillEdge = VoiceCallSurfaceDefaults.cardBorder
    val barEdge = colors.border
    val pillEdgeWidth = with(density) { 1.dp.toPx() }
    val barEdgeWidth = with(density) { OpenCodeSurfaces.hairline.toPx() }
    val pillShadow = with(density) { (FloatingCallTunables.restShadow.value / 2).dp.toPx() }
    val barShadow = with(density) { 16.dp.toPx() }
    // The orb is drawn big enough that its lens fills the pill (the circle clips its glow), and
    // scaled down to the bar's orb, glow and all.
    val orbView = pillSize * orbViewToLens()
    // A press on the bar's main area shades the whole surface a touch: the bar is one button.
    val barPress = remember { MutableInteractionSource() }
    val isBarPressed by barPress.collectIsPressedAsState()
    val pressShade by animateFloatAsState(
        if (isBarPressed) 1f else 0f,
        tween(if (isBarPressed) 60 else 180),
        label = "assistantBarPress",
    )

    Layout(
        contents = listOf(
            {
                BarRow(
                    state,
                    typing,
                    actions,
                    enabled = !isPill,
                    interactionSource = barPress,
                    onMinimise = { go(AssistantCallPanel.Form.pill) },
                )
            },
            { if (typing.isOpen || morph.typing.value > 0f) TypingRow(typing, actions, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) },
            { PillBody(state, enabled = isPill, onRestore = { go(AssistantCallPanel.Form.bar) }) },
            {
                // The orb takes no touches of its own: in the bar a tap falls through to the bar's
                // main area, in the pill to the pill. TalkBack hears it as part of those too.
                Box(Modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                    LivingOrbView(AssistantCallPanel.orb(state), Modifier.size(orbView))
                }
            },
        ),
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            // Any touch on the panel counts as attention, whatever it lands on.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) morph.touches += 1
                    }
                }
            }
            .graphicsLayer {
                shape = SurfaceShape(morph.surface, morph.radius)
                clip = true
                shadowElevation = lerp(pillShadow, barShadow, morph.presence)
                ambientShadowColor = Color.Black.copy(alpha = if (isDark) 0.5f else 0.12f)
                spotShadowColor = Color.Black.copy(alpha = if (isDark) 0.6f else 0.18f)
            }
            .drawWithContent {
                val rect = morph.surface
                if (rect.isEmpty) return@drawWithContent
                val a = morph.presence
                val corner = CornerRadius(morph.radius)
                drawRoundRect(lerpColor(pillFill, barFill, a), rect.topLeft, rect.size, corner)
                if (pressShade > 0f) {
                    drawRoundRect(colors.text.copy(alpha = (if (isDark) 0.08f else 0.05f) * pressShade), rect.topLeft, rect.size, corner)
                }
                drawContent()
                // The edge goes over the content: the pill's orb fills it to the rim.
                val edge = lerp(pillEdgeWidth, barEdgeWidth, a)
                val inset = edge / 2f
                drawRoundRect(
                    lerpColor(pillEdge, barEdge, a),
                    Offset(rect.left + inset, rect.top + inset),
                    Size(rect.width - edge, rect.height - edge),
                    CornerRadius((morph.radius - inset).coerceAtLeast(0f)),
                    style = Stroke(edge),
                )
            }
            .draggable(
                vertical,
                Orientation.Vertical,
                onDragStarted = {
                    morph.progress.stop()
                    morph.dragProgress = morph.progress.value
                },
                onDragStopped = { velocity ->
                    val progress = morph.progress.value
                    val speed = -velocity / step
                    val fling = with(density) { flingVelocity.toPx() }
                    val direction = when {
                        velocity < -fling -> 1
                        velocity > fling -> -1
                        else -> 0
                    }
                    val to = AssistantCallPanel.Form.settling(progress, direction)
                    actions.form(to)
                    // The panel's own scope: the drag's ends when the gesture detector restarts.
                    scope.launch { morph.settle(to, speed, reduceMotion, Motion.release) }
                },
            )
            .draggable(
                horizontal,
                Orientation.Horizontal,
                enabled = isPill,
                onDragStarted = {
                    morph.side.stop()
                    morph.dragSide = morph.side.value
                },
                onDragStopped = { velocity ->
                    val travel = morph.sideTravel
                    val speed = if (travel > 0f) velocity / travel else 0f
                    val to = (morph.side.value + speed * 0.18f).roundToInt().coerceIn(-1, 1).toFloat()
                    actions.side(to)
                    scope.launch {
                        morph.side.animateTo(to, Motion.release.animation(reduceMotion = reduceMotion), Motion.release.initialVelocity(speed, reduceMotion))
                    }
                },
            ),
    ) { slots, constraints ->
        val barSlot = slots[0]
        val typingSlot = slots[1]
        val pillSlot = slots[2]
        val orbSlot = slots[3]
        val width = constraints.maxWidth
        val a = morph.presence
        val typed = morph.typing.value.coerceIn(0f, 1f)
        val rowHeight = rowHeight.roundToPx()

        val pill = pillSlot.firstOrNull()?.measure(Constraints(maxWidth = width))
        val pillWidth = pill?.width ?: 0
        val pillHeight = pill?.height ?: 0
        val surfaceWidth = lerp(pillWidth.toFloat(), width.toFloat(), a)
        // The bar keeps a usable width while it grows out of the pill; the surface clips the rest.
        val barWidth = max(surfaceWidth.roundToInt(), min(barMinWidth.roundToPx(), width))
        val bar = barSlot.first().measure(Constraints.fixed(barWidth, rowHeight))
        val typingRow = typingSlot.firstOrNull()?.measure(Constraints(minWidth = width, maxWidth = width))
        val orbPx = orbView.roundToPx()
        val orb = orbSlot.first().measure(Constraints.fixed(orbPx, orbPx))

        val rowSlot = lerp(pillHeight.toFloat(), rowHeight.toFloat(), a)
        val surfaceHeight = rowSlot + (typingRow?.height ?: 0) * typed
        val travel = (width - pillWidth) / 2f
        morph.sideTravel = travel
        val pillLeft = travel * (1f + morph.side.value)
        val left = lerp(pillLeft, 0f, a)
        val height = surfaceHeight.roundToInt()
        layout(width, height) {
            morph.surface = Rect(left, 0f, left + surfaceWidth, surfaceHeight)
            morph.radius = lerp(pillHeight / 2f, AssistantCallPanel.radius.toPx(), a)
            val x = left.roundToInt()
            bar.placeWithLayer(x, ((rowSlot - rowHeight) / 2f).roundToInt()) { alpha = smooth(0.45f, 1f, a) }
            typingRow?.placeWithLayer(x, rowSlot.roundToInt()) { alpha = typed }
            pill?.place(x, ((rowSlot - pillHeight) / 2f).roundToInt())
            // One orb the whole way: it shrinks and moves from filling the pill to the bar's spot.
            val scale = lerp(1f, orbSize / orbView, a)
            val centreX = left + lerp(pillWidth / 2f, barOrbCentre.toPx(), a)
            val centreY = rowSlot / 2f
            orb.placeWithLayer((centreX - orbPx / 2f).roundToInt(), (centreY - orbPx / 2f).roundToInt()) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

/**
 * The chips of the phone tools the session just used. Above the bar: the latest two, each for
 * [AssistantCallPanel.chipMillis], and they open their app. Above the pill: the newest one for
 * [AssistantCallPanel.pillChipMillis], centred on the pill, then it fades.
 */
@Composable
fun AssistantCallChips(
    phoneActions: List<PhoneTools.Action>,
    morph: AssistantMorphState,
    actions: AssistantCallPanel.Actions,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val seen = remember { mutableStateMapOf<String, Long>() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    for (action in phoneActions) {
        if (action.id !in seen) seen[action.id] = System.currentTimeMillis()
    }
    val onBar = phoneActions.filter { now - seen.getValue(it.id) < AssistantCallPanel.chipMillis }.takeLast(2)
    val onPill = phoneActions.lastOrNull()?.takeIf { now - seen.getValue(it.id) < AssistantCallPanel.pillChipMillis }
    LaunchedEffect(onBar.map { it.id }, onPill?.id) {
        val times = mutableListOf<Long>()
        for (action in onBar) times.add(seen.getValue(action.id) + AssistantCallPanel.chipMillis)
        if (onPill != null) times.add(seen.getValue(onPill.id) + AssistantCallPanel.pillChipMillis)
        val next = times.minOrNull() ?: return@LaunchedEffect
        delay((next - System.currentTimeMillis()).coerceAtLeast(0L) + 50L)
        now = System.currentTimeMillis()
    }
    LaunchedEffect(phoneActions.size) { now = System.currentTimeMillis() }

    // The pill's chip stays composed while it fades out.
    var pillChip by remember { mutableStateOf<PhoneTools.Action?>(null) }
    if (onPill != null) pillChip = onPill
    val pillShown by animateFloatAsState(
        if (onPill != null) 1f else 0f,
        tween(if (reduceMotion) 0 else 240),
        label = "assistantPillChip",
    )
    val lastPillChip = pillChip
    if (onBar.isEmpty() && (lastPillChip == null || (onPill == null && pillShown == 0f))) return

    Layout(
        contents = listOf(
            {
                Row(
                    Modifier
                        .graphicsLayer { alpha = smooth(0.5f, 1f, morph.progress.value) }
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (action in onBar) ActionChip(action, enabled = morph.form == AssistantCallPanel.Form.bar, actions)
                }
            },
            {
                if (lastPillChip != null) {
                    Box(
                        Modifier.graphicsLayer { alpha = pillShown * (1f - smooth(0f, 0.5f, morph.progress.value)) }
                            // Read out when it appears; the pill itself isn't announced again.
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        ActionChip(lastPillChip, enabled = false, actions)
                    }
                }
            },
        ),
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth(),
    ) { slots, constraints ->
        val width = constraints.maxWidth
        val row = slots[0].first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val chip = slots[1].firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val height = max(row.height, chip?.height ?: 0)
        layout(width, height) {
            row.placeRelative(0, height - row.height)
            if (chip != null) {
                // Over the pill, wherever it's parked, but never off the screen.
                val centre = if (morph.surface.isEmpty) width / 2f else morph.surface.center.x
                val x = (centre - chip.width / 2f).roundToInt().coerceIn(0, max(0, width - chip.width))
                chip.placeWithLayer(x, height - chip.height)
            }
        }
    }
}

/**
 * A panel the side button shows instead of a call (unlock first, set up voice, turned off):
 * the same floating surface as the call, with a title, a line of help and its buttons.
 */
@Composable
fun AssistantNoticePanel(
    symbol: SFSymbol,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AssistantSurface(modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(36.dp).background(VoiceCallSurfaceDefaults.controlFill, CircleShape)
                        .border(1.dp, VoiceCallSurfaceDefaults.controlBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(symbol, null, size = 18.dp, tint = OpenCodeTheme.colors.text)
                }
                Text(
                    title,
                    style = OpenCodeTextStyles.headline,
                    color = OpenCodeTheme.colors.text,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (body != null) {
                Text(body, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
            }
            content()
        }
    }
}

/** A notice panel's buttons, at its trailing edge. */
@Composable
fun AssistantNoticeButtons(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = content)
}

/** The orb in the bar, its glow included. */
private val orbSize = 48.dp

/** The pill: a circle the orb's lens fills, as the in-app call rests. */
private val pillSize = 52.dp
private val rowHeight = 72.dp

/** A release faster than this is a flick to the next form. */
private val flingVelocity = 400.dp
private val barMinWidth = 300.dp

/** The orb's centre from the bar's leading edge (10 dp in, a 52 dp slot). */
private val barOrbCentre = 10.dp + 26.dp

/** How much bigger the orb's view is than its lens: the glow around the lens takes the rest. */
private fun orbViewToLens(): Float {
    val diameter = WispOrbTuning.current.clamped().diameter
    return (diameter + 2 * WispOrb.bleed) / diameter
}

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/** 0 below [from], 1 above [to], eased in between. */
private fun smooth(from: Float, to: Float, value: Float): Float {
    val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The surface's outline inside the morph: shadow and clip follow it. */
private class SurfaceShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * The bar's one row: its main area (room for the orb, what the call is doing and its last line),
 * which opens the call in OpenCode, then the controls, each its own 48 dp button. Around the
 * controls nothing answers, so a tap that misses one never opens the app.
 */
@Composable
private fun BarRow(
    state: VoiceFeature.State?,
    typing: AssistantCallPanel.Typing,
    actions: AssistantCallPanel.Actions,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onMinimise: () -> Unit,
) {
    val status = AssistantCallPanel.status(state)
    val failure = AssistantCallPanel.failure(state)
    val last = AssistantCallPanel.lastLine(state)
    val line = failure ?: last?.text ?: AssistantCallPanel.hint(state)
    val since = liveSince(state)
    val seconds = rememberCallSeconds(since)
    val label = AssistantCallPanel.barLabel(status, seconds, line)
    // The main area is one TalkBack element, so the status is announced here rather than as its own live region.
    val view = LocalView.current
    var spokenStatus by remember { mutableStateOf(status) }
    LaunchedEffect(status) {
        if (status == spokenStatus) return@LaunchedEffect
        spokenStatus = status
        @Suppress("DEPRECATION")
        view.announceForAccessibility(status)
    }
    Row(
        Modifier.fillMaxSize().padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .dragIsNeverATap()
                // Navigation, so no haptic; the press shades the whole surface instead of a ripple.
                // Only in the bar: in the pill this row sits under it and the pill takes the tap.
                .then(
                    if (enabled) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Open in OpenCode",
                            onClick = actions.openCall,
                        )
                    } else {
                        Modifier
                    },
                )
                .clearAndSetSemantics {
                    // In the pill the bar is out of sight: TalkBack hears the pill instead.
                    if (!enabled) return@clearAndSetSemantics
                    contentDescription = label
                    role = Role.Button
                    onClick(label = "Open in OpenCode") { actions.openCall(); true }
                    customActions = listOf(CustomAccessibilityAction("Minimise") { onMinimise(); true })
                }
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(52.dp))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                StatusLine(status, since)
                Text(
                    line,
                    style = OpenCodeTextStyles.subheadline,
                    color = when {
                        failure != null -> OpenCodeTheme.colors.danger
                        last == null || last.isUser -> OpenCodeTheme.colors.secondaryText
                        else -> OpenCodeTheme.colors.text
                    },
                    // A failure is the one line worth reading in full.
                    maxLines = if (failure != null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A sliver that answers nothing between the main area and the first control.
        Spacer(Modifier.width(4.dp))
        IconControl(SFSymbol.Keyboard, if (typing.isOpen) "Close the message box" else "Type a message", isOn = typing.isOpen, onClick = actions.toggleTyping)
        IconControl(
            if (state?.isMuted == true) SFSymbol.MicSlashFill else SFSymbol.MicFill,
            if (state?.isMuted == true) "Unmute" else "Mute",
            isOn = state?.isMuted == true,
            enabled = state?.phase == VoiceFeature.State.Phase.Live,
            onClick = { actions.call(VoiceFeature.Action.MuteTapped) },
        )
        FloatingCallEndButton(diameter = 40f, isEnabled = state?.phase?.isTerminal != true, onEnd = { actions.call(VoiceFeature.Action.EndPressed) })
    }
}

/**
 * A finger that moves past the touch slop is a drag, never a tap, whichever way it goes. The
 * panel's own drags already cancel a click on the way down, but the bar doesn't drag sideways.
 * Consuming the move in the final pass cancels the click below. The panel's drag sees each move
 * first, in the main pass, so the swipe down still works.
 */
private fun Modifier.dragIsNeverATap(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        var isDrag = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!isDrag && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) isDrag = true
            if (isDrag) change.consume()
        }
    }
}

/**
 * The pill's button, under the orb that fills it. Nothing else shows, so TalkBack hears what the
 * call is doing and for how long.
 */
@Composable
private fun PillBody(state: VoiceFeature.State?, enabled: Boolean, onRestore: () -> Unit) {
    val status = AssistantCallPanel.status(state)
    val seconds = rememberCallSeconds(liveSince(state))
    Box(
        Modifier
            // Only while it's the pill: in the bar it sits under the bar's main area.
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClickLabel = "Show the call", onClick = onRestore) else Modifier)
            .size(pillSize)
            .clearAndSetSemantics { if (enabled) contentDescription = AssistantCallPanel.barLabel(status, seconds, "") },
    )
}

/** When the call went live, or null while it isn't live: the call's time counts from it. */
private fun liveSince(state: VoiceFeature.State?): Instant? =
    if (state?.phase == VoiceFeature.State.Phase.Live) state.connectedAt else null

@Composable
private fun StatusLine(status: String, since: Instant?) {
    // The status comes first: with large text the call's time gives way rather than squeezing it.
    Layout(
        content = {
            Text(
                status,
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (since != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        " · ",
                        style = OpenCodeTextStyles.footnote,
                        color = OpenCodeTheme.colors.tertiaryText,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    FloatingCallDuration(since, status)
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0)
        val text = measurables[0].measure(loose)
        val time = measurables.getOrNull(1)?.measure(loose.copy(maxWidth = Constraints.Infinity))
        val shownTime = time?.takeIf { text.width + it.width <= constraints.maxWidth }
        val width = text.width + (shownTime?.width ?: 0)
        val height = maxOf(text.height, shownTime?.height ?: 0)
        layout(width, height) {
            text.placeRelative(0, (height - text.height) / 2)
            if (shownTime != null) shownTime.placeRelative(text.width, (height - shownTime.height) / 2)
        }
    }
}

/** A round neutral control in the bar. */
@Composable
private fun IconControl(
    symbol: SFSymbol,
    name: String,
    isOn: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    Box(
        Modifier.size(48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).alpha(if (enabled) 1f else 0.4f)
                .background(if (isOn) colors.text else VoiceCallSurfaceDefaults.controlFill, CircleShape)
                .border(1.dp, if (isOn) Color.Transparent else VoiceCallSurfaceDefaults.controlBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon(symbol, null, size = 20.dp, tint = if (isOn) colors.canvas else colors.text)
        }
    }
}

private fun PhoneTools.Action.symbol(): SFSymbol = when (kind) {
    PhoneTools.Action.Kind.timer -> SFSymbol.Timer
    PhoneTools.Action.Kind.alarm -> SFSymbol.Alarm
    PhoneTools.Action.Kind.app -> SFSymbol.ArrowUpForwardApp
    PhoneTools.Action.Kind.screen -> SFSymbol.DocText
    PhoneTools.Action.Kind.other -> SFSymbol.Checkmark
}

private val PhoneTools.Action.opens: Boolean get() = kind != PhoneTools.Action.Kind.screen && kind != PhoneTools.Action.Kind.other

@Composable
private fun ActionChip(action: PhoneTools.Action, enabled: Boolean, actions: AssistantCallPanel.Actions) {
    val shape = RoundedCornerShape(OpenCodeRadius.control + 4.dp)
    Row(
        Modifier
            .background(OpenCodeSurfaces.color, shape)
            .openCodeHairline(shape)
            .clickable(enabled = enabled && action.opens, role = Role.Button, onClick = { actions.openAction(action) })
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OpenCodeIcon(action.symbol(), null, size = 16.dp, tint = OpenCodeTheme.colors.secondaryText)
        Text(action.text, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.text, maxLines = 1)
    }
}

@Composable
private fun TypingRow(typing: AssistantCallPanel.Typing, actions: AssistantCallPanel.Actions, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(typing.isOpen) {
        if (!typing.isOpen) return@LaunchedEffect
        // The host makes its window focusable when typing opens; give that a frame to land.
        delay(120)
        focus.requestFocus()
        keyboard?.show()
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpenCodeTextField(
                value = typing.message,
                onValueChange = actions.message,
                placeholder = "Message the session",
                singleLine = true,
                enabled = !typing.isSending && typing.isOpen,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { actions.send() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconControl(SFSymbol.ArrowUp, "Send", isOn = typing.canSend, enabled = typing.canSend, onClick = actions.send)
        }
        val result = typing.result
        if (result != null) {
            Text(
                result,
                style = OpenCodeTextStyles.footnote,
                color = if (typing.failed) OpenCodeTheme.colors.danger else OpenCodeTheme.colors.secondaryText,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
