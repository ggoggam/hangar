package dev.ggoggam.hangar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.ggoggam.hangar.core.FlyPoint

/**
 * A line over [points] scaled to fit, with a faint fill under it. The bottom is zero unless the
 * values go negative, so a flat line near the top really is near the peak.
 */
@Composable
fun Sparkline(
    points: List<FlyPoint>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val t0 = points.first().time
        val span = (points.last().time - t0).coerceAtLeast(1).toFloat()
        val low = minOf(0.0, points.minOf { it.value })
        val high = points.maxOf { it.value }.takeIf { it > low } ?: (low + 1)
        val inset = 2.dp.toPx()

        fun at(p: FlyPoint) =
            Offset(
                x = (p.time - t0) / span * size.width,
                y = inset + (1 - ((p.value - low) / (high - low)).toFloat()) * (size.height - 2 * inset),
            )

        val line = Path()
        points.forEachIndexed { i, p -> at(p).let { if (i == 0) line.moveTo(it.x, it.y) else line.lineTo(it.x, it.y) } }
        val area =
            Path().apply {
                addPath(line)
                lineTo(at(points.last()).x, size.height)
                lineTo(at(points.first()).x, size.height)
                close()
            }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.14f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
