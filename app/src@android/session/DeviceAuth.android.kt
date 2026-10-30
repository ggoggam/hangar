package dev.ggoggam.hangar.app.session

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The framework's BiometricPrompt rather than androidx.biometric, which needs a FragmentActivity: a
 * face or fingerprint, with the screen lock's PIN, pattern or password as the way around it.
 */
@Composable
actual fun rememberDeviceAuth(): DeviceAuth {
    val context = LocalContext.current
    return remember(context) { AndroidDeviceAuth(context) }
}

private class AndroidDeviceAuth(
    private val context: Context,
) : DeviceAuth {
    override val available: Boolean
        get() =
            context.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTHENTICATORS) ==
                BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun authenticate(reason: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            // No negative button: with the screen lock allowed, the prompt offers it instead.
            BiometricPrompt
                .Builder(context)
                .setTitle(reason)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
                .authenticate(
                    cancel,
                    context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        // Cancelled, locked out or no longer set up. A face or finger that isn't
                        // recognized only calls onAuthenticationFailed, with the prompt still up.
                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence?,
                        ) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                )
        }

    private companion object {
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
