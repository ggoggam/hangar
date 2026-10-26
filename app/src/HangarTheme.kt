package dev.ggoggam.hangar.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ggoggam.hangar.app.generated.resources.*
import org.jetbrains.compose.resources.Font

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

/** Pretendard throughout, in both languages, cut down for the app by fonts/build.py. */
@Composable
private fun hangarTypography(): Typography {
    val sans =
        FontFamily(
            Font(Res.font.hangar_sans_regular, FontWeight.Normal),
            Font(Res.font.hangar_sans_medium, FontWeight.Medium),
            Font(Res.font.hangar_sans_semibold, FontWeight.SemiBold),
            Font(Res.font.hangar_sans_bold, FontWeight.Bold),
        )

    // Headings in Bold, set a little tight the way Pretendard headings usually are.
    fun TextStyle.heading(
        size: Int,
        line: Int,
    ) = copy(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = line.sp, letterSpacing = (-0.02 * size).sp)

    // Material's tracking is tuned for Roboto; Pretendard is spaced for text already, and Hangul
    // reads worse spread out.
    fun TextStyle.text(tracking: Double = 0.0) = copy(fontFamily = sans, letterSpacing = tracking.sp)

    val m = Typography()
    return Typography(
        // The login's wordmark.
        displayLarge = m.displayLarge.heading(52, 60),
        displayMedium = m.displayMedium.heading(44, 52),
        displaySmall = m.displaySmall.heading(36, 44),
        headlineLarge = m.headlineLarge.heading(32, 40),
        headlineMedium = m.headlineMedium.heading(28, 36),
        headlineSmall = m.headlineSmall.heading(24, 32),
        // Top bar titles and metric figures.
        titleLarge = m.titleLarge.heading(24, 30),
        // Section titles.
        titleMedium = m.titleMedium.heading(18, 24),
        // Tabs.
        titleSmall = m.titleSmall.text().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = m.bodyLarge.text(),
        bodyMedium = m.bodyMedium.text(),
        bodySmall = m.bodySmall.text(),
        labelLarge = m.labelLarge.text(0.1),
        labelMedium = m.labelMedium.text(0.2),
        labelSmall = m.labelSmall.text(0.2),
    )
}

@Composable
fun HangarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = hangarTypography(), content = content)
    }
}
