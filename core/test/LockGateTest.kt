package dev.ggoggam.hangar.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LockGateTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000L)

    @Test
    fun shutAtColdStartOnlyWhileOn() {
        assertTrue(LockGate(on = true).locked.value)
        assertFalse(LockGate(on = false).locked.value)
    }

    @Test
    fun unlocksWhenAuthenticationSucceeds() =
        runTest {
            val gate = LockGate(on = true)
            assertFalse(gate.unlock { false })
            assertTrue(gate.locked.value)
            assertTrue(gate.unlock { true })
            assertFalse(gate.locked.value)
        }

    @Test
    fun aShortTripAwayStaysOpen() =
        runTest {
            val gate = LockGate(on = true)
            gate.unlock { true }
            gate.stopped(now)
            gate.started(now + 59.seconds)
            assertFalse(gate.locked.value)
            assertEquals(0, gate.shuts.value)
        }

    @Test
    fun aMinuteAwayShutsIt() =
        runTest {
            val gate = LockGate(on = true)
            gate.unlock { true }
            gate.stopped(now)
            gate.started(now + LockGate.AWAY_LIMIT)
            assertTrue(gate.locked.value)
            assertEquals(1, gate.shuts.value)
        }

    @Test
    fun aClockThatWentBackCountsAsTimeAway() =
        runTest {
            val gate = LockGate(on = true)
            gate.unlock { true }
            gate.stopped(now)
            gate.started(now - 1.seconds)
            assertTrue(gate.locked.value)
        }

    @Test
    fun timeAwayCountsOnlyOnce() =
        runTest {
            val gate = LockGate(on = true)
            gate.unlock { true }
            gate.stopped(now)
            gate.started(now + 10.seconds)
            // Started again without stopping first, as an observer added late sees it.
            gate.started(now + 120.seconds)
            assertFalse(gate.locked.value)
        }

    @Test
    fun comingBackWhileShutCountsAnotherShut() =
        runTest {
            val gate = LockGate(on = true)
            gate.stopped(now)
            gate.started(now + 5.minutes)
            assertTrue(gate.locked.value)
            assertEquals(1, gate.shuts.value)
        }

    @Test
    fun theSystemPromptIsntTimeAway() =
        runTest {
            val gate = LockGate(on = true)
            // Android's passcode screen is an activity of its own, which stops the app under it.
            val opened =
                gate.unlock {
                    gate.stopped(now)
                    true
                }
            assertTrue(opened)
            gate.started(now + 5.minutes)
            assertFalse(gate.locked.value)
        }

    @Test
    fun staysOpenWhileOff() =
        runTest {
            val gate = LockGate(on = false)
            gate.stopped(now)
            gate.started(now + 5.minutes)
            assertFalse(gate.locked.value)
        }

    @Test
    fun turnsOnOnlyOnceAuthenticated() =
        runTest {
            val gate = LockGate(on = false)
            assertFalse(gate.turnOn { false })
            assertFalse(gate.on)
            assertTrue(gate.turnOn { true })
            assertTrue(gate.on)
            assertFalse(gate.locked.value)
            gate.stopped(now)
            gate.started(now + 5.minutes)
            assertTrue(gate.locked.value)
        }

    @Test
    fun turningOffOpensIt() {
        val gate = LockGate(on = true)
        gate.turnOff()
        assertFalse(gate.on)
        assertFalse(gate.locked.value)
    }

    @Test
    fun openingForgetsTheTimeAway() {
        val gate = LockGate(on = true)
        gate.stopped(now)
        gate.open()
        assertFalse(gate.locked.value)
        gate.started(now + 5.minutes)
        assertFalse(gate.locked.value)
    }
}
