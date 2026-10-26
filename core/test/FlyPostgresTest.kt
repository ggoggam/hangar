package dev.ggoggam.hangar.core

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlyPostgresTest {
    private val personal = FlyOrganization(slug = "personal", rawSlug = "me-123")

    @Test
    fun mergesBothListsAsFlyctlDoes() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"data":[{"id":"c1","name":"main","status":"ready","region":"nrt","plan":"basic","attached_apps":[{"name":"web"}]}]}""",
                    HttpStatusCode.OK to
                        """{"data":[
                            {"id":"c1","name":"main","region":"nrt","status":"ready","plan":"basic","disk":10,"replicas":1,"attached_apps":[]},
                            {"id":"c0","name":"old","region":"iad","status":"ready","plan":"launch","disk":20,"replicas":0,"attached_apps":[{"name":"api"}]}]}""",
                )

            val clusters = FlyManagedPostgres(fly.http, MIXED_TOKEN, "https://fly.test", "https://m.test").clusters(personal)

            assertEquals(listOf("main", "old"), clusters.map { it.name })
            // The older list fills in the disk; the newer one's attached apps stand.
            assertEquals(10, clusters[0].diskGb)
            assertEquals(listOf("web"), clusters[0].attachedApps)
            assertEquals(listOf("api"), clusters[1].attachedApps)
            assertEquals("personal", clusters[1].org)
            // Both take the real slug, and the older list takes every token under Bearer.
            assertEquals("/v1/postgres", fly.requests[0].url.encodedPath)
            assertEquals("me-123", fly.requests[0].url.parameters["org_slug"])
            assertEquals("FlyV1 fm2_a,fm2_b", fly.requests[0].headers[HttpHeaders.Authorization])
            assertEquals("/api/v1/organizations/me-123/postgres", fly.requests[1].url.encodedPath)
            assertEquals("Bearer fm2_a,fm2_b,fo1_c", fly.requests[1].headers[HttpHeaders.Authorization])
        }

    @Test
    fun skipsTheMachinesApiWhereItIsMissing() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.NotFound to """{"error":"not found"}""",
                    HttpStatusCode.OK to
                        """{"data":[{"id":"c0","name":"old","region":"iad","status":"ready","plan":"launch","attached_apps":null}]}""",
                )

            val clusters = FlyManagedPostgres(fly.http, MIXED_TOKEN, "https://fly.test", "https://m.test").clusters(personal)

            assertEquals(listOf("old"), clusters.map { it.name })
        }

    @Test
    fun otherFailuresFail() =
        runTest {
            val fly = MockFly(HttpStatusCode.OK to """{"data":null}""", HttpStatusCode.Unauthorized to "{}")
            assertFailsWith<FlyApiException.Unauthorized> {
                FlyManagedPostgres(fly.http, MIXED_TOKEN, "https://fly.test", "https://m.test").clusters(personal)
            }
        }
}
