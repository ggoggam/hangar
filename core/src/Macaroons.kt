package dev.ggoggam.hangar.core

import kotlin.io.encoding.Base64

/**
 * What Hangar needs to know about one of Fly.io's macaroons, read without verifying it (only
 * Fly.io can): where it's for, the key ID that a discharge's ticket matches, when it expires, and
 * the third-party caveats that need discharges. The layout follows superfly/macaroon: a msgpack
 * array of nonce, location, caveats and tail, with the caveats as type, body pairs.
 */
internal class MacaroonInfo(
    val location: String,
    val keyId: ByteArray,
    /** Unix seconds, or null when no validity window limits it. */
    val expiresAt: Long?,
    val thirdParty: List<ThirdPartyCaveat>,
) {
    companion object {
        private const val CAVEAT_VALIDITY_WINDOW = 4L
        private const val CAVEAT_THIRD_PARTY = 11L

        private val BASE64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

        /** [token] without a scheme, such as `fm2_…`; null when it isn't a macaroon this can read. */
        fun parse(token: String): MacaroonInfo? =
            runCatching {
                val raw = BASE64.decode(token.substringAfter('_'))
                val parts = MsgpackReader(raw).read() as List<*>
                val nonce = parts[0] as List<*>
                val caveats = parts[2] as List<*>
                var expires: Long? = null
                val thirdParty = mutableListOf<ThirdPartyCaveat>()
                for (i in 0 until caveats.size / 2) {
                    val body = caveats[2 * i + 1] as? List<*> ?: continue
                    when (caveats[2 * i] as? Long) {
                        CAVEAT_VALIDITY_WINDOW -> {
                            val notAfter = body[1] as Long
                            expires = expires?.let { minOf(it, notAfter) } ?: notAfter
                        }

                        CAVEAT_THIRD_PARTY -> {
                            thirdParty += ThirdPartyCaveat(body[0] as String, body[2] as ByteArray)
                        }
                    }
                }
                MacaroonInfo(parts[1] as String, nonce[0] as ByteArray, expires, thirdParty)
            }.getOrNull()

        /** Fly.io's permission tokens, the ones the API checks; anything else is a discharge. */
        const val LOCATION_PERMISSION = "https://api.fly.io/v1"
    }
}

internal class ThirdPartyCaveat(
    val location: String,
    val ticket: ByteArray,
)
