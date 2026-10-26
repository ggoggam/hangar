package dev.ggoggam.hangar.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Neutral chrome with ink for emphasis; green, amber and red only for status.
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF171717),
        onPrimary = Color(0xFFFAFAFA),
        primaryContainer = Color(0xFFF5F5F5),
        onPrimaryContainer = Color(0xFF0A0A0A),
        secondaryContainer = Color(0xFFF5F5F5),
        onSecondaryContainer = Color(0xFF0A0A0A),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF0A0A0A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0A0A0A),
        surfaceVariant = Color(0xFFF5F5F5),
        onSurfaceVariant = Color(0xFF737373),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFAFAFA),
        surfaceContainer = Color(0xFFF5F5F5),
        surfaceContainerHigh = Color(0xFFF0F0F0),
        surfaceContainerHighest = Color(0xFFE9E9E9),
        outline = Color(0xFFE5E5E5),
        outlineVariant = Color(0xFFEDEDED),
        error = Color(0xFFDC2626),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFFAFAFA),
        onPrimary = Color(0xFF171717),
        primaryContainer = Color(0xFF262626),
        onPrimaryContainer = Color(0xFFFAFAFA),
        secondaryContainer = Color(0xFF262626),
        onSecondaryContainer = Color(0xFFFAFAFA),
        background = Color(0xFF0A0A0A),
        onBackground = Color(0xFFFAFAFA),
        surface = Color(0xFF0A0A0A),
        onSurface = Color(0xFFFAFAFA),
        surfaceVariant = Color(0xFF262626),
        onSurfaceVariant = Color(0xFFA3A3A3),
        surfaceContainerLowest = Color(0xFF0A0A0A),
        surfaceContainerLow = Color(0xFF111111),
        surfaceContainer = Color(0xFF171717),
        surfaceContainerHigh = Color(0xFF1F1F1F),
        surfaceContainerHighest = Color(0xFF262626),
        outline = Color(0xFF262626),
        outlineVariant = Color(0xFF1F1F1F),
        error = Color(0xFFF87171),
    )

/** Status colors for app and machine states. */
@Immutable
data class StatusColors(
    val ok: Color,
    val okTint: Color,
    val warning: Color,
    val warningTint: Color,
    val error: Color,
    val errorTint: Color,
)

private val LightStatus =
    StatusColors(
        ok = Color(0xFF15803D),
        okTint = Color(0xFFDCFCE7),
        warning = Color(0xFFB45309),
        warningTint = Color(0xFFFEF3C7),
        error = Color(0xFFDC2626),
        errorTint = Color(0xFFFEE2E2),
    )

private val DarkStatus =
    StatusColors(
        ok = Color(0xFF4ADE80),
        okTint = Color(0xFF12301E),
        warning = Color(0xFFFBBF24),
        warningTint = Color(0xFF33270C),
        error = Color(0xFFF87171),
        errorTint = Color(0xFF3B1418),
    )

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun HangarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
