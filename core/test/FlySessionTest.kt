package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class FlySessionTest {
    private val ticket = "ticket-1".encodeToByteArray()
    private val now = 1_800_000_000L
    private val permission = TestMacaroons.permission(ticket)
    private val good = FlyToken("$permission,${TestMacaroons.discharge(ticket, now + 600)},fo1_x")
    private val stale = FlyToken("$permission,${TestMacaroons.discharge(ticket, now + 10)},fo1_x")
    private val renewedDischarge = TestMacaroons.discharge(ticket, now + 900)
    private val renewed = FlyToken("$permission,$renewedDischarge,fo1_x")

    private class MemoryStore(
        var value: String?,
    ) : TokenStore {
        var writes = 0

        override fun read() = value

        override fun write(token: String) {
            writes++
            value = token
        }

        override fun clear() {
            value = null
        }
    }

    /** Fly.io's discharge endpoint, holding every answer until [open] completes. */
    private class Discharger(
        status: HttpStatusCode,
        body: String,
    ) {
        val open = CompletableDeferred<Unit>()
        var requests = 0
        val http =
            HttpClient(
                MockEngine {
                    requests++
                    open.await()
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
    }

    private fun TestScope.session(
        token: FlyToken?,
        fly: Discharger,
        store: MemoryStore = MemoryStore(token?.value),
        // A supervisor, as in the app: a failed renewal fails its callers, not every renewal after it.
    ) = FlySession(store, FlyDischarges(fly.http), backgroundScope + SupervisorJob(backgroundScope.coroutineContext[Job])) { now }

    private fun discharger() = Discharger(HttpStatusCode.OK, """{"discharge":"$renewedDischarge"}""")

    @Test
    fun callersAtTheSameTimeShareOneRenewal() =
        runTest {
            val fly = discharger()
            val store = MemoryStore(stale.value)
            val session = session(stale, fly, store)

            val calls = List(7) { async { session.fresh() } }
            testScheduler.advanceUntilIdle()
            fly.open.complete(Unit)

            assertEquals(List(7) { renewed }, calls.awaitAll())
            assertEquals(1, fly.requests)
            assertEquals(1, store.writes)
            assertEquals(renewed, session.token.value)
        }

    @Test
    fun aGoodTokenIsNotRenewed() =
        runTest {
            val fly = discharger()
            assertEquals(good, session(good, fly).fresh())
            assertEquals(0, fly.requests)
        }

    @Test
    fun aTurnedDownTokenIsRenewedOnceForEveryCallAndTheyTryAgain() =
        runTest {
            val fly = discharger()
            val session = session(good, fly)

            val calls =
                List(7) {
                    async {
                        session.authorized { token ->
                            // The phone's clock is behind: Fly.io says the discharge has run out.
                            if (token == good) throw FlyApiException.Unauthorized()
                            token
                        }
                    }
                }
            testScheduler.advanceUntilIdle()
            fly.open.complete(Unit)

            assertEquals(List(7) { renewed }, calls.awaitAll())
            assertEquals(1, fly.requests)
        }

    @Test
    fun aRenewalThatFailsFailsEveryCallWaitingOnItOnce() =
        runTest {
            val fly = Discharger(HttpStatusCode.BadGateway, "bad gateway")
            val session = session(good, fly)

            val calls = List(7) { async { runCatching { session.authorized { throw FlyApiException.Unauthorized() } } } }
            testScheduler.advanceUntilIdle()
            fly.open.complete(Unit)

            // Each waiter trying again in turn would be seven round trips, each up to the timeout.
            calls.awaitAll().forEach { assertIs<FlyApiException.Failed>(it.exceptionOrNull()) }
            assertEquals(1, fly.requests)
            assertEquals(good, session.token.value)
        }

    @Test
    fun aRenewedTokenTurnedDownTooSignsOut() =
        runTest {
            val fly = discharger().also { it.open.complete(Unit) }
            val store = MemoryStore(good.value)
            val session = session(good, fly, store)

            assertFailsWith<FlyApiException.Unauthorized> { session.authorized { throw FlyApiException.Unauthorized() } }
            assertNull(session.token.value)
            assertNull(store.value)
        }

    @Test
    fun aTokenWithNothingToRenewIsSignedOutWhenTurnedDown() =
        runTest {
            val fly = discharger()
            val pasted = FlyToken("fm2_org")
            val session = session(pasted, fly)

            assertFailsWith<FlyApiException.Unauthorized> { session.authorized { throw FlyApiException.Unauthorized() } }
            assertNull(session.token.value)
            assertEquals(0, fly.requests)
        }

    @Test
    fun aLateRejectionDoesNotSignOutANewSignIn() =
        runTest {
            val fly = discharger()
            val session = session(good, fly)
            val other = FlyToken("fm2_other")

            val result =
                session.authorized { token ->
                    if (token == good) {
                        session.signIn(other)
                        throw FlyApiException.Unauthorized()
                    }
                    token
                }

            assertEquals(other, result)
            assertEquals(other, session.token.value)
            assertEquals(0, fly.requests)
        }

    @Test
    fun aRenewalThatFailsKeepsTheSession() =
        runTest {
            val fly = Discharger(HttpStatusCode.BadGateway, "bad gateway").also { it.open.complete(Unit) }
            val session = session(good, fly)

            assertFailsWith<FlyApiException.Failed> { session.authorized { throw FlyApiException.Unauthorized() } }
            assertEquals(good, session.token.value)

            // Discharges that are running out are tried again on the next call, which may still work.
            val staleSession = session(stale, fly)
            assertEquals(stale, staleSession.fresh())
            assertEquals(stale, staleSession.token.value)
        }

    @Test
    fun aRenewalFlyIoRefusesSignsOut() =
        runTest {
            val fly = Discharger(HttpStatusCode.Unauthorized, """{"error":"bad token"}""").also { it.open.complete(Unit) }
            val session = session(stale, fly)

            assertNull(session.fresh())
            assertNull(session.token.value)
        }
}
