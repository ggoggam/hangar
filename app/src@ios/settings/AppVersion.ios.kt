package dev.ggoggam.hangar.app.settings

import androidx.compose.runtime.Composable
import platform.Foundation.NSBundle

@Composable
internal actual fun appVersion(): String {
    val info = NSBundle.mainBundle.infoDictionary
    return "${info?.get("CFBundleShortVersionString")} (${info?.get("CFBundleVersion")})"
}
