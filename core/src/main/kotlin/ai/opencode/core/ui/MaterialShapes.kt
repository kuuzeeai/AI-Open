package ai.opencode.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive "cookie" and sun polygons, for avatars, tool icons and the occasional
 * project mark. Used sparingly: a shape here is a signal, not the default. Round everything else
 * with [OpenCodeRadius] / [OpenCodeShape].
 * These are @Composable because Material3's toShape() requires it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun cookie9Shape(): Shape = MaterialShapes.Cookie9Sided.toShape()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun cookie12Shape(): Shape = MaterialShapes.Cookie12Sided.toShape()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun sunnyShape(): Shape = MaterialShapes.Sunny.toShape()

/** A softly rounded square, the fallback when a polygon would be too fussy at small sizes. */
val softSquareShape: Shape = RoundedCornerShape(OpenCodeRadius.sm)