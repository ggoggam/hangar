package dev.ggoggam.hangar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlyTokenTest {
    @Test
    fun macaroonsUseTheFlyV1Scheme() {
        val token = FlyToken("FlyV1 fm2_abc,fm2_def")
        assertEquals("fm2_abc,fm2_def", token.value)
        assertEquals("FlyV1 fm2_abc,fm2_def", token.graphqlHeader)
        assertEquals("FlyV1 fm2_abc,fm2_def", token.machinesHeader)
    }

    @Test
    fun userTokensUseBearer() {
        val token = FlyToken("  Bearer fo1_xyz \n")
        assertEquals("fo1_xyz", token.value)
        assertEquals("Bearer fo1_xyz", token.graphqlHeader)
    }

    @Test
    fun mixedTokensSendOnlyMacaroonsToMachines() {
        val token = FlyToken("fo1_user, fm1r_read")
        assertEquals("FlyV1 fm1r_read,fo1_user", token.graphqlHeader)
        assertEquals("FlyV1 fm1r_read", token.machinesHeader)
        assertEquals("Bearer fm1r_read,fo1_user", token.uiexHeader)
    }

    @Test
    fun blankInputIsBlank() {
        assertTrue(FlyToken(" FlyV1 ").isBlank)
        assertTrue(FlyToken(" , ").isBlank)
    }
}
