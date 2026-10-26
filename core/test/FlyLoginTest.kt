package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlyLoginTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun login(vararg responses: Pair<HttpStatusCode, String>): FlyLogin {
        val queue = ArrayDeque(responses.toList())
        val engine =
            MockEngine { request ->
                requests += request
                val (status, body) = queue.removeFirst()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        return FlyLogin(HttpClient(engine), apiUrl = "https://fly.test")
    }

    private fun HttpRequestData.json(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun startsAPkceSessionAndRedeemsWithItsVerifier() =
        runTest {
            val fly =
                login(
                    HttpStatusCode.Created to
                        """{"id":"s1","auth_url":"https://fly.test/app/auth/cli/s1","pkce":true,"access_token":null}""",
                    HttpStatusCode.OK to """{"id":"s1","access_token":"fm2_token"}""",
                )

            val pending = fly.start("Hangar on Pixel")
            assertEquals("https://fly.test/app/auth/cli/s1", pending.approvalUrl)

            val start = requests[0]
            assertEquals(HttpMethod.Post, start.method)
            assertEquals("https://fly.test/api/v1/cli_sessions", start.url.toString())
            val startBody = start.json()
            assertEquals("Hangar on Pixel", startBody.string("name"))
            assertEquals("S256", startBody.string("code_challenge_method"))
            assertEquals(Pkce(pending.verifier).challenge, startBody.string("code_challenge"))

            val token = fly.redeem(pending, "  ABCD-1234 \n")
            assertEquals(FlyToken("fm2_token"), token)

            val redeem = requests[1]
            assertEquals("https://fly.test/api/v1/cli_sessions/s1/redeem", redeem.url.toString())
            assertEquals("ABCD-1234", redeem.json().string("code"))
            assertEquals(pending.verifier, redeem.json().string("code_verifier"))
        }

    @Test
    fun refusesAServerWithoutPkce() =
        runTest {
            val fly = login(HttpStatusCode.Created to """{"id":"s1","auth_url":"https://fly.test/x","pkce":false}""")
            assertFailsWith<FlyLoginException.Unavailable> { fly.start("Hangar") }
        }

    @Test
    fun aMissingSessionIsExpired() =
        runTest {
            val fly =
                login(
                    HttpStatusCode.Created to """{"id":"s1","auth_url":"https://fly.test/x","pkce":true}""",
                    HttpStatusCode.NotFound to "{}",
                )
            val pending = fly.start("Hangar")
            assertFailsWith<FlyLoginException.Expired> { fly.redeem(pending, "code") }
        }

    @Test
    fun aRefusedCodeCarriesTheServersReason() =
        runTest {
            val fly =
                login(
                    HttpStatusCode.Created to """{"id":"s1","auth_url":"https://fly.test/x","pkce":true}""",
                    HttpStatusCode.UnprocessableEntity to """{"error":"invalid code"}""",
                )
            val pending = fly.start("Hangar")
            val e = assertFailsWith<FlyLoginException.Rejected> { fly.redeem(pending, "nope") }
            assertEquals("invalid code", e.message)
        }
}
