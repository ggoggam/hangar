package dev.ggoggam.hangar.app.session

/**
 * The Keychain, implemented by `KeychainBridge` in `ios-app/src/KeychainBridge.swift`. Only
 * strings cross, so the logic stays in Kotlin.
 */
interface IosSecureStore {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )

    fun delete(key: String)
}

/** ASWebAuthenticationSession, implemented by `SignInBridge` in `ios-app/src/SignInBridge.swift`. */
interface IosSignInBridge {
    fun open(url: String)
}
