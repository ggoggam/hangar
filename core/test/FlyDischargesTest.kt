package dev.ggoggam.hangar.core

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlyDischargesTest {
    private val ticket = "ticket-1".encodeToByteArray()
    private val now = 1_800_000_000L
    private val permission = TestMacaroons.permission(ticket)

    @Test
    fun aDischargeCountsUntilAMinuteBeforeItExpires() {
        assertEquals(1, FlyToken("$permission,fo1_x").undischarged(now).size)
        assertEquals(0, FlyToken("$permission,${TestMacaroons.discharge(ticket, now + 300)},fo1_x").undischarged(now).size)
        assertEquals(1, FlyToken("$permission,${TestMacaroons.discharge(ticket, now + 30)},fo1_x").undischarged(now).size)
        assertEquals(1, FlyToken("$permission,${TestMacaroons.discharge("other".encodeToByteArray(), now + 300)}").undischarged(now).size)
    }

    @Test
    fun fetchesADischargeWithTheOAuthTokenAndSwapsOutTheOldOne() =
        runTest {
            val stale = TestMacaroons.discharge(ticket, now + 10)
            val fresh = TestMacaroons.discharge(ticket, now + 600)
            val fly = MockFly(HttpStatusCode.OK to """{"discharge":"$fresh"}""")

            val refreshed = FlyDischarges(fly.http).refresh(FlyToken("$permission,$stale,fo1_x"), now)

            val request = fly.requests.single()
            assertEquals("https://api.fly.io/aaa/v1/.well-known/macfly/3p", request.url.toString())
            assertEquals("Bearer fo1_x", request.headers[HttpHeaders.Authorization])
            val sent =
                Json
                    .parseToJsonElement((request.body as TextContent).text)
                    .jsonObject
                    .getValue("ticket")
                    .jsonPrimitive.content
            assertTrue(Base64.Default.decode(sent).contentEquals(ticket))
            assertEquals(FlyToken("$permission,$fresh,fo1_x"), refreshed)
            assertEquals("FlyV1 $permission,$fresh", refreshed.machinesHeader)
            assertEquals(FlyToken("$permission,$stale,fo1_x").identity, refreshed.identity)
        }

    @Test
    fun leavesAGoodTokenAlone() =
        runTest {
            val fly = MockFly()
            val token = FlyToken("$permission,${TestMacaroons.discharge(ticket, now + 600)},fo1_x")
            assertSame(token, FlyDischarges(fly.http).refresh(token, now))
            assertEquals(0, fly.requests.size)
        }

    @Test
    fun neverSendsTheOAuthTokenToAnotherHost() =
        runTest {
            val fly = MockFly()
            val token = FlyToken("${TestMacaroons.permission(ticket, location = "https://evil.example")},fo1_x")
            assertFailsWith<FlyApiException.Unauthorized> { FlyDischarges(fly.http).refresh(token, now) }
            assertEquals(0, fly.requests.size)
        }

    @Test
    fun aDischargeThatNeedsTheUserMeansSigningInAgain() =
        runTest {
            val needsUser = MockFly(HttpStatusCode.Created to """{"user_interactive":{"user_url":"https://fly.io/x","poll_url":"/p"}}""")
            assertFailsWith<FlyApiException.Unauthorized> { FlyDischarges(needsUser.http).refresh(FlyToken("$permission,fo1_x"), now) }

            // Without an OAuth token there's nothing to ask with.
            assertFailsWith<FlyApiException.Unauthorized> { FlyDischarges(MockFly().http).refresh(FlyToken(permission), now) }

            val rejected = MockFly(HttpStatusCode.Unauthorized to """{"error":"bad token"}""")
            assertFailsWith<FlyApiException.Unauthorized> { FlyDischarges(rejected.http).refresh(FlyToken("$permission,fo1_x"), now) }
        }
}
