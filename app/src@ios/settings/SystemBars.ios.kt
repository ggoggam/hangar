package dev.ggoggam.hangar.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

@Composable
actual fun SystemBars(dark: Boolean?) {
    LaunchedEffect(dark) {
        // Unspecified when following the system, so the windows keep reporting its changes.
        val style =
            when (dark) {
                true -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
                false -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
                null -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            }
        UIApplication.sharedApplication.connectedScenes.forEach { scene ->
            (scene as? UIWindowScene)?.windows?.forEach { (it as? UIWindow)?.overrideUserInterfaceStyle = style }
        }
    }
}
