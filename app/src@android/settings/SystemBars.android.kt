package dev.ggoggam.hangar.app.settings

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
actual fun SystemBars(dark: Boolean?) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val isDark = dark ?: isSystemInDarkTheme()
    LaunchedEffect(activity, isDark) {
        // enableEdgeToEdge's own defaults, but told the theme rather than reading the system's.
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isDark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { isDark },
        )
    }
}

private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
