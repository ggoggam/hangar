package dev.ggoggam.hangar.ios

import androidx.compose.ui.window.ComposeUIViewController
import dev.ggoggam.hangar.app.App
import dev.ggoggam.hangar.app.di.initHangar
import dev.ggoggam.hangar.app.session.IosSecureStore
import dev.ggoggam.hangar.app.session.IosSignInBridge
import dev.ggoggam.hangar.app.session.iosPlatformModule
import dev.ggoggam.hangar.app.watch.registerBackgroundChecks
import platform.UIKit.UIViewController

/**
 * Starts Hangar's services and registers its background task. Called from the app's init, since a
 * background launch to run the task never makes a view controller, and iOS wants the task
 * registered before launching finishes. [store] and [signIn] are the Swift `KeychainBridge` and
 * `SignInBridge`.
 */
fun startHangar(
    store: IosSecureStore,
    signIn: IosSignInBridge,
) {
    initHangar(iosPlatformModule(store, signIn))
    registerBackgroundChecks()
}

/** The app's root view controller, after [startHangar]. */
@Suppress("ktlint:standard:function-naming")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
