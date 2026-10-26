package dev.ggoggam.hangar.core

/**
 * Just enough of a MessagePack reader to look inside a macaroon: values come back as null,
 * Boolean, Long, Double, String, ByteArray, List and Map. Extension types are skipped as null.
 */
internal class MsgpackReader(
    private val bytes: ByteArray,
) {
    private var at = 0

    fun read(): Any? {
        val b = u8()
        return when {
            b <= 0x7f -> {
                b.toLong()
            }

            b in 0x80..0x8f -> {
                map(b and 0x0f)
            }

            b in 0x90..0x9f -> {
                array(b and 0x0f)
            }

            b in 0xa0..0xbf -> {
                string(b and 0x1f)
            }

            b >= 0xe0 -> {
                (b - 0x100).toLong()
            }

            else -> {
                when (b) {
                    0xc0 -> null
                    0xc2 -> false
                    0xc3 -> true
                    0xc4 -> take(u8())
                    0xc5 -> take(u16())
                    0xc6 -> take(u32())
                    0xc7 -> skipExt(u8())
                    0xc8 -> skipExt(u16())
                    0xc9 -> skipExt(u32())
                    0xca -> Float.fromBits(uint(4).toInt()).toDouble()
                    0xcb -> Double.fromBits(uint(8))
                    0xcc -> uint(1)
                    0xcd -> uint(2)
                    0xce -> uint(4)
                    0xcf -> uint(8)
                    0xd0 -> uint(1).toByte().toLong()
                    0xd1 -> uint(2).toShort().toLong()
                    0xd2 -> uint(4).toInt().toLong()
                    0xd3 -> uint(8)
                    0xd4 -> skipExt(1)
                    0xd5 -> skipExt(2)
                    0xd6 -> skipExt(4)
                    0xd7 -> skipExt(8)
                    0xd8 -> skipExt(16)
                    0xd9 -> string(u8())
                    0xda -> string(u16())
                    0xdb -> string(u32())
                    0xdc -> array(u16())
                    0xdd -> array(u32())
                    0xde -> map(u16())
                    0xdf -> map(u32())
                    else -> throw IllegalArgumentException("msgpack: bad byte 0x${b.toString(16)}")
                }
            }
        }
    }

    private fun u8(): Int {
        require(at < bytes.size) { "msgpack: truncated" }
        return bytes[at++].toInt() and 0xff
    }

    private fun u16(): Int = uint(2).toInt()

    private fun u32(): Int = uint(4).also { require(it <= Int.MAX_VALUE) { "msgpack: too long" } }.toInt()

    private fun uint(size: Int): Long {
        var v = 0L
        repeat(size) { v = (v shl 8) or u8().toLong() }
        return v
    }

    private fun take(n: Int): ByteArray {
        require(n >= 0 && at + n <= bytes.size) { "msgpack: truncated" }
        return bytes.copyOfRange(at, at + n).also { at += n }
    }

    private fun string(n: Int): String = take(n).decodeToString()

    // Lengths come from the token, so they don't size anything before the bytes are known to exist.
    private fun array(n: Int): List<Any?> = buildList { repeat(n) { add(read()) } }

    private fun map(n: Int): Map<Any?, Any?> = buildMap { repeat(n) { put(read(), read()) } }

    private fun skipExt(n: Int): Any? {
        take(n + 1)
        return null
    }
}
