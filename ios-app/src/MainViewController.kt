package dev.ggoggam.hangar.ios

import androidx.compose.ui.window.ComposeUIViewController
import dev.ggoggam.hangar.app.App
import dev.ggoggam.hangar.app.di.initHangar
import dev.ggoggam.hangar.app.session.IosSecureStore
import dev.ggoggam.hangar.app.session.IosSignInBridge
import dev.ggoggam.hangar.app.session.iosPlatformModule
import platform.UIKit.UIViewController

/** The app's root view controller. [store] and [signIn] are the Swift `KeychainBridge` and `SignInBridge`. */
@Suppress("ktlint:standard:function-naming")
fun MainViewController(
    store: IosSecureStore,
    signIn: IosSignInBridge,
): UIViewController {
    initHangar(iosPlatformModule(store, signIn))
    return ComposeUIViewController { App() }
}
