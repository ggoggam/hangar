package dev.ggoggam.hangar.core

import kotlin.io.encoding.Base64

/** Builds macaroon-shaped tokens for tests: the layout Fly.io uses, with made-up keys and no valid signature. */
internal object TestMacaroons {
    const val AUTH = "https://api.fly.io/aaa/v1"

    fun permission(
        ticket: ByteArray,
        location: String = AUTH,
    ): String =
        token(
            listOf(
                nonce("perm".encodeToByteArray()),
                MacaroonInfo.LOCATION_PERMISSION,
                listOf(11L, listOf(location, byteArrayOf(9), ticket)),
            ),
        )

    fun discharge(
        ticket: ByteArray,
        notAfter: Long,
    ): String = token(listOf(nonce(ticket), AUTH, listOf(4L, listOf(0L, notAfter))))

    private fun nonce(kid: ByteArray) = listOf(kid, byteArrayOf(1, 2, 3), false)

    private fun token(parts: List<Any?>) = "fm2_" + Base64.Default.encode(encode(parts + listOf(byteArrayOf(7, 7))))

    fun encode(value: Any?): ByteArray =
        when (value) {
            null -> byteArrayOf(0xc0.toByte())
            false -> byteArrayOf(0xc2.toByte())
            true -> byteArrayOf(0xc3.toByte())
            is Long -> if (value in 0..127) byteArrayOf(value.toByte()) else byteArrayOf(0xcf.toByte()) + be(value, 8)
            is String -> value.encodeToByteArray().let { byteArrayOf(0xd9.toByte(), it.size.toByte()) + it }
            is ByteArray -> byteArrayOf(0xc4.toByte(), value.size.toByte()) + value
            is List<*> -> byteArrayOf((0x90 + value.size).toByte()) + value.fold(ByteArray(0)) { acc, v -> acc + encode(v) }
            else -> error("can't encode $value")
        }

    private fun be(
        v: Long,
        size: Int,
    ) = ByteArray(size) { i -> (v shr (8 * (size - 1 - i))).toByte() }
}
