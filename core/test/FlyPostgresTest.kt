package dev.ggoggam.hangar.core

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun readsAClusterAndItsListsFromTheMachinesApi() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"data":{"id":"c1","name":"main","status":"ready","region":"nrt","plan":"basic","disk_size_gb":10,
                            "storage_used_bytes":1073741824,"storage_provisioned_bytes":10737418240,"cpus":2,"cpu_kind":"shared",
                            "memory_mb":1024,"replicas":1,"pg_major_version":"17","postgis_enabled":true,
                            "endpoints":{"primary":{"direct":{"host":"direct.c1.test","port":5432},"pooler":{"host":"","port":0}}},
                            "organization":{"name":"Me","slug":"me-123"},"created_at":"2026-09-01T00:00:00Z",
                            "attached_apps":[{"name":"web"}]}}""",
                    HttpStatusCode.OK to """{"data":[{"name":"fly-db"},{"name":"orders"}]}""",
                    HttpStatusCode.OK to """{"data":[{"username":"fly-user","role":"schema_admin"}]}""",
                    HttpStatusCode.OK to
                        """{"data":[
                            {"id":"b1","status":"completed","type":"full","size_bytes":42,"started_at":"2026-10-27T00:00:00Z","finished_at":"2026-10-27T00:05:00Z"},
                            {"id":"b2","status":"running","type":"incr","started_at":"2026-10-28T06:00:00Z"}]}""",
                )
            val postgres = FlyManagedPostgres(fly.http, MIXED_TOKEN, "https://fly.test", "https://m.test")

            val cluster = postgres.cluster("c1", personal)
            assertEquals("main", cluster.name)
            assertEquals("personal", cluster.org)
            assertEquals(2, cluster.cpus)
            assertEquals(1024, cluster.memoryMb)
            assertEquals(1_073_741_824L, cluster.storageUsedBytes)
            assertEquals("17", cluster.postgresVersion)
            assertTrue(cluster.postGis)
            assertEquals("direct.c1.test", cluster.direct?.host)
            assertEquals(5432, cluster.direct?.port)
            // An empty host is no endpoint.
            assertNull(cluster.pooler)
            assertEquals(listOf("web"), cluster.attachedApps)

            assertEquals(listOf("fly-db", "orders"), postgres.databases(cluster))
            val user = postgres.users(cluster).single()
            assertEquals("fly-user", user.name)
            assertEquals("schema_admin", user.role)
            val backups = postgres.backups(cluster)
            // Newest first.
            assertEquals(listOf("b2", "b1"), backups.map { it.id })
            assertEquals(42L, backups[1].sizeBytes)
            assertNull(backups[0].sizeBytes)

            assertEquals(
                listOf("/v1/postgres/c1", "/v1/postgres/c1/databases", "/v1/postgres/c1/users", "/v1/postgres/c1/backups"),
                fly.requests.map { it.url.encodedPath },
            )
            assertTrue(fly.requests.all { it.url.host == "m.test" && it.headers[HttpHeaders.Authorization] == "FlyV1 fm2_a,fm2_b" })
        }

    @Test
    fun neverAsksForTheOlderClusterAnswerWithItsPassword() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.NotFound to """{"error":"not found"}""",
                    HttpStatusCode.OK to
                        """{"data":[{"id":"c0","name":"old","region":"iad","status":"ready","plan":"launch","disk":20,"replicas":0,
                            "version":1,"ip_assignments":{"direct":"fdaa::3"},"attached_apps":[{"name":"api"}]}]}""",
                    HttpStatusCode.OK to """{"data":[{"name":"app"}]}""",
                    HttpStatusCode.OK to """{"data":[{"name":"app_user","role":"writer"}]}""",
                    HttpStatusCode.OK to
                        """{"data":[{"id":"b0","status":"completed","type":"full","start":"2026-10-27T00:00:00Z","stop":null}]}""",
                )
            val postgres = FlyManagedPostgres(fly.http, MIXED_TOKEN, "https://fly.test", "https://m.test")

            val cluster = postgres.cluster("c0", personal)
            assertEquals("old", cluster.name)
            assertEquals(20, cluster.diskGb)
            assertEquals("fdaa::3", cluster.direct?.host)
            assertNull(cluster.direct?.port)
            assertNull(cluster.postgresVersion)
            assertEquals(listOf("app"), postgres.databases(cluster))
            assertEquals("app_user", postgres.users(cluster).single().name)
            assertEquals("2026-10-27T00:00:00Z", postgres.backups(cluster).single().startedAt)

            // The org's list, not /api/v1/postgres/c0, whose answer carries credentials; then the
            // first version's lists, under Bearer as flyctl sends them.
            assertEquals(
                listOf(
                    "/v1/postgres/c0",
                    "/api/v1/organizations/me-123/postgres",
                    "/api/v1/postgres/c0/databases",
                    "/api/v1/postgres/c0/users",
                    "/api/v1/postgres/c0/backups",
                ),
                fly.requests.map { it.url.encodedPath },
            )
            assertEquals("Bearer fm2_a,fm2_b,fo1_c", fly.requests[2].headers[HttpHeaders.Authorization])
            assertTrue(fly.requests.none { it.url.encodedPath.endsWith("/credentials") })
        }

    @Test
    fun secondVersionListsFallBackWhereTheMachinesApiIsMissing() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.NotFound to """{"error":"not found"}""",
                    HttpStatusCode.OK to
                        """{"data":[{"id":"c2","name":"two","region":"ord","status":"ready","plan":"basic","version":2}]}""",
                    HttpStatusCode.NotFound to """{"error":"not found"}""",
                    HttpStatusCode.OK to """{"data":null}""",
                )
            val postgres = FlyManagedPostgres(fly.http, MIXED_TOKEN, "https://fly.test", "https://m.test")

            val cluster = postgres.cluster("c2", personal)
            assertTrue(postgres.databases(cluster).isEmpty())
            assertEquals("/v1/postgres/c2/databases", fly.requests[2].url.encodedPath)
            assertEquals("/api/v1/postgresv2/c2/databases", fly.requests[3].url.encodedPath)
        }

    @Test
    fun aClusterInNeitherPlaceFails() =
        runTest {
            val fly = MockFly(HttpStatusCode.NotFound to """{"error":"not found"}""", HttpStatusCode.OK to """{"data":null}""")
            assertFailsWith<FlyApiException.Failed> {
                FlyManagedPostgres(fly.http, MIXED_TOKEN, "https://fly.test", "https://m.test").cluster("gone", personal)
            }
        }
}
