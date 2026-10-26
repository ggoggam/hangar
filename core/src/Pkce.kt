package dev.ggoggam.hangar.core

import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.random.CryptoRand
import kotlin.io.encoding.Base64

/** An RFC 7636 code verifier and its S256 challenge. The verifier never leaves the device until redeem. */
internal class Pkce(
    val verifier: String,
) {
    val challenge: String = BASE64_URL.encode(SHA256().digest(verifier.encodeToByteArray()))

    companion object {
        fun random(): Pkce = Pkce(randomToken(32))
    }
}

/** [size] random bytes from the platform's secure generator, base64url without padding (as flyctl makes them). */
internal fun randomToken(size: Int): String = BASE64_URL.encode(CryptoRand.Default.nextBytes(ByteArray(size)))

private val BASE64_URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
