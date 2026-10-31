package dev.ggoggam.hangar.app.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun SystemBars(dark: Boolean?) {
    val window = LocalActivity.current?.window ?: return
    val isDark = dark ?: isSystemInDarkTheme()
    LaunchedEffect(window, isDark) {
        // The bars are transparent already (MainActivity and its theme), so only their icons, and
        // the scrim the system puts behind three-button navigation, follow the app's theme.
        WindowInsetsControllerCompat(window, window.decorView).run {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}
