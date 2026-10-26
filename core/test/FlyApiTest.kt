package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlyApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(vararg responses: Pair<HttpStatusCode, String>): FlyApi {
        val queue = ArrayDeque(responses.toList())
        val engine =
            MockEngine { request ->
                requests += request
                val (status, body) = queue.removeFirst()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        return FlyApi(HttpClient(engine), FlyToken("fm2_abc"), apiUrl = "https://fly.test")
    }

    private fun page(
        names: List<String>,
        next: String?,
    ) = """
        {"data":{"viewer":{"email":"me@example.com"},"apps":{
          "pageInfo":{"hasNextPage":${next != null},"endCursor":${next?.let { "\"$it\"" } ?: "null"}},
          "nodes":[${names.joinToString(
        ",",
    ) { """{"name":"$it","deployed":true,"status":"deployed","organization":{"slug":"personal"},"currentRelease":null,"unknown":1}""" }}]
        }}}
    """

    @Test
    fun followsPagesAndSendsTheMacaroonHeader() =
        runTest {
            val fly = api(HttpStatusCode.OK to page(listOf("web"), next = "c1"), HttpStatusCode.OK to page(listOf("api"), next = null))

            val overview = fly.overview()

            assertEquals("me@example.com", overview.email)
            assertEquals(listOf("api", "web"), overview.apps.map { it.name })
            assertEquals("FlyV1 fm2_abc", requests[0].headers[HttpHeaders.Authorization])
            val variables = { r: HttpRequestData ->
                Json
                    .parseToJsonElement((r.body as TextContent).text)
                    .jsonObject
                    .getValue("variables")
                    .jsonObject
            }
            assertEquals(JsonNull, variables(requests[0]).getValue("after"))
            assertEquals("c1", variables(requests[1]).getValue("after").jsonPrimitive.content)
        }

    @Test
    fun aRevokedTokenIsUnauthorized() =
        runTest {
            assertFailsWith<FlyApiException.Unauthorized> { api(HttpStatusCode.Unauthorized to "{}").overview() }
            assertFailsWith<FlyApiException.Unauthorized> {
                api(
                    HttpStatusCode.OK to
                        """{"data":null,"errors":[{"message":"You must be authenticated","extensions":{"code":"UNAUTHORIZED"}}]}""",
                ).overview()
            }
        }

    @Test
    fun otherGraphQlErrorsCarryTheirMessage() =
        runTest {
            val e = assertFailsWith<FlyApiException.Failed> { api(HttpStatusCode.OK to """{"errors":[{"message":"boom"}]}""").overview() }
            assertEquals("boom", e.message)
        }

    @Test
    fun readsAnAppAndItsReleases() =
        runTest {
            val release =
                """{"id":"r","version":7,"description":"Release","reason":"","status":"complete","imageRef":"img",""" +
                    """"stable":true,"user":{"email":"a@b.c"},"createdAt":"2026-10-25T13:42:10Z"}"""
            val fly =
                api(
                    HttpStatusCode.OK to
                        """{"data":{"app":{"name":"web","hostname":"web.fly.dev","deployed":true,"status":"deployed","platformVersion":"machines","organization":{"slug":"personal"},"currentRelease":$release}}}""",
                    HttpStatusCode.OK to """{"data":{"app":{"releases":{"nodes":[$release]}}}}""",
                )

            val app = fly.app("web")
            assertEquals("web.fly.dev", app.hostname)
            assertEquals(7, app.currentRelease?.version)
            assertEquals(
                "a@b.c",
                fly
                    .releases("web")
                    .single()
                    .user
                    ?.email,
            )
            val variables =
                Json
                    .parseToJsonElement((requests[1].body as TextContent).text)
                    .jsonObject
                    .getValue("variables")
                    .jsonObject
            assertEquals("25", variables.getValue("limit").jsonPrimitive.content)
        }
}
