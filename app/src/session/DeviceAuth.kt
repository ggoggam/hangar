package dev.ggoggam.hangar.app.session

import androidx.compose.runtime.Composable

/**
 * The system's own check that it's the device's owner: a face, a fingerprint, or failing those the
 * device's passcode (BiometricPrompt, LocalAuthentication). The system checks them and only tells
 * the app whether it worked; nothing about them reaches the app.
 */
interface DeviceAuth {
    /** Whether the device can check at all: it has biometrics enrolled or a screen lock set. */
    val available: Boolean

    /** Shows the system's prompt with [reason]; whether the owner got through. False if cancelled. */
    suspend fun authenticate(reason: String): Boolean
}

@Composable
expect fun rememberDeviceAuth(): DeviceAuth
