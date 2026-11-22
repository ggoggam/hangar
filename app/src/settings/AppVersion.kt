package dev.ggoggam.hangar.app.settings

import androidx.compose.runtime.Composable

/**
 * The installed app's version and build, as the stores show them: `0.4.1 (57)`. Read from the
 * package or bundle, so it's whatever the release workflow built with, not a copy kept in code.
 */
@Composable
internal expect fun appVersion(): String
