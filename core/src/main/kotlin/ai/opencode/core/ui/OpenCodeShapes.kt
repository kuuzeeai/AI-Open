package ai.opencode.core.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Corner radii for the Material 3 Expressive refresh. The desktop's flat 4–16 dp scale is replaced
 * by the expressive one: controls and cards are capsules or deeply rounded panels, floating
 * surfaces (composer, sheets) wear 28–32 dp.
 */
object OpenCodeRadius {
    /** Tiny marks: inline code, checkbox insets. */
    val xs: Dp = 8.dp
    /** Small fills and thumbnails. */
    val sm: Dp = 12.dp
    /** Tertiary-fill fields and rows. */
    val fill: Dp = 16.dp
    /** Inline controls and trays. */
    val control: Dp = 16.dp
    /** Cards and glass cards. */
    val card: Dp = 24.dp
    /** Attachment chips, clipped media, settings tiles. */
    val tile: Dp = 20.dp
    /** Large panels: connect, server details, What's New cards. */
    val panel: Dp = 28.dp
    /** Sheets, scanner frame, model list, voice panel. */
    val sheet: Dp = 32.dp
}

/** [capsule] and [circle] stay true. */
object OpenCodeShape {
    val capsule = RoundedCornerShape(percent = 50)

    /** Buttons, pills and fields. */
    val control = RoundedCornerShape(OpenCodeRadius.control)
    val circle = CircleShape
    val card = RoundedCornerShape(OpenCodeRadius.card)
    val panel = RoundedCornerShape(OpenCodeRadius.panel)
    val sheet = RoundedCornerShape(OpenCodeRadius.sheet)
    val fill = RoundedCornerShape(OpenCodeRadius.fill)

    /** Top-only rounding for bottom sheets. */
    val sheetTop = RoundedCornerShape(topStart = OpenCodeRadius.sheet, topEnd = OpenCodeRadius.sheet)
}

/**
 * Material 3 Expressive shape scale: extraSmall 8, small 12, medium 20, large 28,
 * extraLarge 36.
 */
val OpenCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(OpenCodeRadius.xs),
    small = RoundedCornerShape(OpenCodeRadius.sm),
    medium = RoundedCornerShape(OpenCodeRadius.panel),
    large = RoundedCornerShape(OpenCodeRadius.card),
    extraLarge = RoundedCornerShape(OpenCodeRadius.sheet),
)

/**
 * Port of `GlassGeometry`: a rounded rectangle's radius is at most half its height. Clamp
 * hand-computed animated shapes with this.
 */
object OpenCodeShapeGeometry {
    fun radius(radius: Float, height: Float): Float = min(max(0f, radius), max(0f, height) / 2)
}

/** Spacing values, in dp. Prefer these to literals so layouts stay on the same grid. */
object OpenCodeSpacing {
    val s1: Dp = 1.dp
    val s2: Dp = 2.dp
    val s4: Dp = 4.dp
    val s6: Dp = 6.dp
    val s8: Dp = 8.dp
    val s10: Dp = 10.dp
    val s12: Dp = 12.dp
    val s14: Dp = 14.dp
    val s16: Dp = 16.dp
    val s18: Dp = 18.dp
    val s20: Dp = 20.dp
    val s24: Dp = 24.dp
    val s32: Dp = 32.dp

    /** Screen and list-row horizontal inset. */
    val screenHorizontal: Dp = s16

    /** Default stack spacing between related items. */
    val stack: Dp = s8

    /** Between groups/sections. */
    val group: Dp = s12
}
