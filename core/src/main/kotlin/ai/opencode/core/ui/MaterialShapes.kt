package ai.opencode.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive "cookie" and sun polygons, for avatars, tool icons and the occasional
 * project mark. Used sparingly: a shape here is a signal, not the default. Round everything else
 * with [OpenCodeRadius] / [OpenCodeShape].
 */
object OpenCodeExpressiveShapes {
    /** A 9-sided cookie, the default mark for avatars and small tool glyphs. */
    val cookie9: Shape = MaterialShapes.Cookie9Sided.toShape()

    /** A 12-sided cookie, for larger marks where the 9-sided one reads too busy. */
    val cookie12: Shape = MaterialShapes.Cookie12Sided.toShape()

    /** A sunburst, for the agent activity glyph and highlights. */
    val sunny: Shape = MaterialShapes.Sunny.toShape()

    /** A softly rounded square, the fallback when a polygon would be too fussy at small sizes. */
    val softSquare: Shape = RoundedCornerShape(OpenCodeRadius.sm)
}