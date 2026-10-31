package dev.ggoggam.hangar.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Which side of a bar the content scrolls under, and so where [barShadow] falls. */
enum class BarEdge { Top, Bottom }

/**
 * A slight shadow past a bar's [edge], over the content scrolling under it. Drawn as a gradient
 * rather than elevated, so it falls as softly above the bottom bar as below a top one, where an
 * elevation shadow would barely show past the top edge.
 */
fun Modifier.barShadow(edge: BarEdge): Modifier =
    drawWithContent {
        drawContent()
        val depth = 6.dp.toPx()
        val (y, colors) =
            when (edge) {
                BarEdge.Bottom -> size.height to listOf(SHADOW, Color.Transparent)
                BarEdge.Top -> -depth to listOf(Color.Transparent, SHADOW)
            }
        drawRect(Brush.verticalGradient(colors, startY = y, endY = y + depth), Offset(0f, y), Size(size.width, depth))
    }

private val SHADOW = Color.Black.copy(alpha = 0.06f)
