package dev.ggoggam.hangar.app.settings

import androidx.compose.runtime.Composable

/**
 * Matches the status bar's icons to the app's theme when [dark] overrides the system's, so they
 * stay readable; null leaves the system in charge.
 */
@Composable
expect fun SystemBars(dark: Boolean?)
