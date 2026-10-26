package dev.ggoggam.hangar.app.session

import androidx.compose.runtime.Composable

/**
 * Fly.io's approval page in the system's sign-in browser (a Custom Tab, ASWebAuthenticationSession),
 * not a WebView: it shares the browser's fly.io session and password manager, shows the real
 * address, and Google and GitHub sign-in work in it.
 */
interface SignInSheet {
    fun open(url: String)
}

@Composable
expect fun rememberSignInSheet(): SignInSheet
