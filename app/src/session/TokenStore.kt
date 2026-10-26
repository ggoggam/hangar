package dev.ggoggam.hangar.app.session

/** Where the Fly.io token lives between launches: the Android Keystore or the iOS Keychain. */
interface TokenStore {
    fun read(): String?

    fun write(token: String)

    fun clear()
}
