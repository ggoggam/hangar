package dev.ggoggam.hangar.app.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * LocalAuthentication's device owner policy: Face ID or Touch ID, with the passcode as the way
 * around it. Face ID's prompt says why from NSFaceIDUsageDescription in `ios-app/src/Info.plist`.
 */
@Composable
actual fun rememberDeviceAuth(): DeviceAuth = remember { IosDeviceAuth() }

private class IosDeviceAuth : DeviceAuth {
    // A fresh context each time: one keeps the result of its last check.
    @OptIn(ExperimentalForeignApi::class)
    override val available: Boolean
        get() = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)

    override suspend fun authenticate(reason: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val context = LAContext()
            continuation.invokeOnCancellation { context.invalidate() }
            // The reply comes on a private queue; the app's state only changes on the main thread.
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = reason) { success, _ ->
                dispatch_async(dispatch_get_main_queue()) {
                    if (continuation.isActive) continuation.resume(success)
                }
            }
        }
}
