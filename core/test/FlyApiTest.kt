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

    @Test
    fun readsTheOrganizationNumberForGrafana() =
        runTest {
            val fly =
                api(
                    HttpStatusCode.OK to
                        """{"data":{"app":{"name":"web","status":"deployed","organization":{"slug":"personal","internalNumericId":"12345"}}}}""",
                    HttpStatusCode.OK to """{"data":{"app":{"name":"web","status":"deployed","organization":{"slug":"personal"}}}}""",
                )

            assertEquals("https://fly-metrics.net/d/fly-app/fly-app?orgId=12345&var-app=web", grafanaUrl(fly.app("web")))
            assertEquals(null, grafanaUrl(fly.app("web")))
        }

    @Test
    fun readsHostIssuesOrganizationsAndAddOns() =
        runTest {
            val fly =
                api(
                    HttpStatusCode.OK to
                        """{"data":{"app":{"hostIssues":{"nodes":[{"internalId":"h1","message":"Host down","createdAt":"2026-10-25T13:42:10Z","updatedAt":"2026-10-25T14:00:00Z"}]}}}}""",
                    HttpStatusCode.OK to
                        """{"data":{"organizations":{"nodes":[{"slug":"team","rawSlug":"team","name":"Team"},{"slug":"personal","rawSlug":"me-123","name":"Me"}]}}}""",
                    HttpStatusCode.OK to
                        """{"data":{"addOns":{"nodes":[
                            {"id":"b","name":"cache","addOnPlan":{"displayName":"Pay-as-you-go"},"primaryRegion":"nrt","readRegions":["sin"],"options":{"eviction":true},"organization":{"slug":"team"}},
                            {"id":"a","name":"queue","addOnPlan":null,"primaryRegion":"iad","readRegions":[],"options":null,"organization":{"slug":"personal"}}]}}}""",
                )

            assertEquals("Host down", fly.hostIssues("web").single().message)
            val orgs = fly.organizations()
            assertEquals(listOf("personal", "team"), orgs.map { it.slug })
            assertEquals("me-123", orgs.first().rawSlug)
            val addOns = fly.addOns("upstash_redis")
            assertEquals(listOf("queue", "cache"), addOns.map { it.name })
            assertEquals(listOf(false, true), addOns.map { it.eviction })
            assertEquals("Pay-as-you-go", addOns[1].planName)
            assertEquals(listOf("sin"), addOns[1].readRegions)
            val variables =
                Json
                    .parseToJsonElement((requests[2].body as TextContent).text)
                    .jsonObject
                    .getValue("variables")
                    .jsonObject
            assertEquals("upstash_redis", variables.getValue("addOnType").jsonPrimitive.content)
        }

    @Test
    fun readsTigrisBucketsAsFlyIoSendsThem() =
        runTest {
            // A bucket's shape as Fly.io sent it: no plan, an empty region and null read regions.
            val fly =
                api(
                    HttpStatusCode.OK to
                        """{"data":{"addOns":{"nodes":[{"id":"t1","name":"assets","addOnPlan":null,""" +
                        """"primaryRegion":"","readRegions":null,"options":{"public":false,"website":{"domain_name":""},""" +
                        """"accelerate":false},"organization":{"slug":"personal"}}]}}}""",
                )

            val bucket = fly.addOns("tigris").single()
            assertEquals("assets", bucket.name)
            assertEquals(null, bucket.primaryRegion)
            assertEquals(emptyList(), bucket.readRegions)
            assertEquals(null, bucket.planName)
        }

    @Test
    fun listsPostgresAppsByRole() =
        runTest {
            val node = """{"name":"db","deployed":true,"status":"deployed","organization":{"slug":"personal"}}"""
            val fly = api(HttpStatusCode.OK to """{"data":{"apps":{"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[$node]}}}""")

            assertEquals(listOf("db"), fly.postgresApps().map { it.name })
            val variables =
                Json
                    .parseToJsonElement((requests.single().body as TextContent).text)
                    .jsonObject
                    .getValue("variables")
                    .jsonObject
            assertEquals("postgres_cluster", variables.getValue("role").jsonPrimitive.content)
        }

    @Test
    fun readsWhoamiAnOrganizationAndItsTokens() =
        runTest {
            val fly =
                api(
                    HttpStatusCode.OK to """{"data":{"viewer":{"email":"me@example.com"}}}""",
                    HttpStatusCode.OK to
                        """{"data":{"organization":{"slug":"personal","rawSlug":"me-123","name":"Me","type":"PERSONAL",""" +
                        """"viewerRole":"admin","members":{"edges":[{"node":{"id":"u1","name":"Me","email":"me@example.com"},""" +
                        """"joinedAt":"2026-10-23T07:13:35Z","role":"ADMIN"}]}}}}""",
                    HttpStatusCode.OK to
                        """{"data":{"organization":{"limitedAccessTokens":{"nodes":[""" +
                        """{"id":"t1","name":"deploy","expiresAt":"2036-10-22T14:40:12Z","revokedAt":null,""" +
                        """"user":{"email":"me@example.com"}},""" +
                        """{"id":"t2","name":"old","expiresAt":"2027-01-01T00:00:00Z","revokedAt":"2026-01-01T00:00:00Z","user":null}]}}}}""",
                )

            assertEquals("me@example.com", fly.whoami())
            val org = fly.organization("personal")
            assertEquals("PERSONAL", org.type)
            assertEquals(listOf("ADMIN"), org.members.map { it.role })
            val tokens = fly.organizationTokens("personal")
            assertEquals(listOf("deploy", "old"), tokens.map { it.name })
            assertEquals(listOf("me@example.com", null), tokens.map { it.createdBy })
            assertEquals("2026-01-01T00:00:00Z", tokens[1].revokedAt)
        }
}
