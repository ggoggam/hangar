package dev.ggoggam.hangar.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * A grey bar with a lighter band sweeping across it, standing in for text that hasn't loaded.
 * A round spinner is left to pull-to-refresh, so the two never read as the same thing.
 */
@Composable
fun ShimmerBar(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    val sweep =
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
            label = "shimmer",
        )
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).drawBehind {
            // Read in the draw phase so the sweep redraws without recomposing.
            val x = sweep.value * size.width
            val band = size.width / 2
            drawRect(
                Brush.linearGradient(
                    listOf(base, highlight, base),
                    start = Offset(x - band, 0f),
                    end = Offset(x + band, size.height),
                ),
            )
        },
    )
}
