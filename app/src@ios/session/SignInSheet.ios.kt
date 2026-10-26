package dev.ggoggam.hangar.app.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.koin.compose.koinInject

@Composable
actual fun rememberSignInSheet(): SignInSheet {
    val bridge = koinInject<IosSignInBridge>()
    return remember(bridge) {
        object : SignInSheet {
            override fun open(url: String) = bridge.open(url)
        }
    }
}
