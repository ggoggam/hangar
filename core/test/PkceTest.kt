package dev.ggoggam.hangar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun challengeMatchesRfc7636AppendixB() {
        val pkce = Pkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
    }

    @Test
    fun randomVerifiersAreUnpaddedBase64UrlOf32Bytes() {
        val a = Pkce.random().verifier
        val b = Pkce.random().verifier
        assertEquals(43, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' }, a)
        assertNotEquals(a, b)
    }
}
