package dev.ggoggam.hangar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MsgpackReaderTest {
    private fun read(vararg bytes: Int) = MsgpackReader(ByteArray(bytes.size) { bytes[it].toByte() }).read()

    @Test
    fun readsScalars() {
        assertEquals(5L, read(0x05))
        assertEquals(-1L, read(0xff))
        assertEquals(300L, read(0xcd, 0x01, 0x2c))
        assertEquals(-2L, read(0xd0, 0xfe))
        assertEquals(1_800_000_000L, read(0xce, 0x6b, 0x49, 0xd2, 0x00))
        assertEquals("hi", read(0xa2, 0x68, 0x69))
        assertEquals(null, read(0xc0))
        assertEquals(listOf(1L, true), read(0x92, 0x01, 0xc3))
        assertEquals(mapOf("a" to 1L), read(0x81, 0xa1, 0x61, 0x01))
    }

    @Test
    fun rejectsATruncatedValue() {
        assertFailsWith<IllegalArgumentException> { read(0xc4, 0x05, 0x01) }
        assertFailsWith<IllegalArgumentException> { read(0xdd, 0x7f, 0xff, 0xff, 0xff) }
    }
}
