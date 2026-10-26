package dev.ggoggam.hangar.core

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlyMachinesTest {
    private val machine =
        """
        {"id":"m1","name":"late-morning","state":"started","region":"nrt","host_status":"ok",
         "created_at":"2026-10-25T13:42:10Z","updated_at":"2026-10-25T16:22:50Z",
         "image_ref":{"registry":"registry.fly.io","repository":"web","tag":"deployment-01","digest":"sha256:x","labels":{}},
         "config":{"guest":{"cpu_kind":"shared","cpus":2,"memory_mb":512},"env":{"SECRET":"s"},
                   "metadata":{"fly_process_group":"app","fly_release_version":"7"}},
         "checks":[{"name":"servicecheck-00-http-8080","status":"passing","output":"ok"},
                   {"name":"bg_deployments_compat_x","status":"critical"}],
         "events":[{"type":"exit","status":"stopped","source":"flyd","timestamp":1792945370046,
                    "request":{"exit_event":{"exit_code":137,"oom_killed":true}}},
                   {"type":"start","status":"started","source":"flyd","timestamp":1792945360000,"request":{}}]}
        """

    private fun other(
        id: String,
        state: String,
        group: String,
    ) = """{"id":"$id","state":"$state","config":{"metadata":{"fly_process_group":"$group"}}}"""

    @Test
    fun listsLiveMachinesWithTheMacaroonHeader() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        "[$machine,${other(
                            "gone",
                            "destroyed",
                            "app",
                        )},${other("rc", "stopped", "fly_app_release_command")},${other("c", "started", "fly_app_console")}]",
                )

            val machines = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test").machines("web")

            assertEquals(
                "https://m.test/v1/apps/web/machines",
                fly.requests
                    .single()
                    .url
                    .toString(),
            )
            assertEquals("FlyV1 fm2_a,fm2_b", fly.requests.single().headers[HttpHeaders.Authorization])
            val m = machines.single()
            assertEquals("shared-cpu-2x", m.config?.guest?.size)
            assertEquals("app", m.processGroup)
            assertEquals(7, m.releaseVersion)
            assertEquals("web:deployment-01", m.imageRef?.short)
            assertEquals(listOf("servicecheck-00-http-8080"), m.checks.map { it.name })
            assertEquals(137, m.events[0].exitCode)
            assertTrue(m.events[0].oomKilled)
            assertNull(m.events[1].exitCode)
        }

    @Test
    fun readsVolumesIpsAndCertificates() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """[{"id":"vol_1","name":"data","state":"created","size_gb":1,"region":"nrt","bytes_used":10,"bytes_total":100},
                            {"id":"vol_2","name":"old","state":"pending_destroy","size_gb":1}]""",
                    HttpStatusCode.OK to
                        """{"ips":[{"ip":"66.241.1.1","shared":true},{"ip":"2a09:8280::1","region":"global"},{"ip":"fdaa:0:1::2"}]}""",
                    HttpStatusCode.OK to
                        """{"certificates":[{"hostname":"example.com","status":"Ready","configured":true}],"total_count":1}""",
                )
            val api = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test")

            assertEquals(listOf("vol_1"), api.volumes("web").map { it.id })
            assertEquals(listOf(FlyIpType.SharedV4, FlyIpType.V6, FlyIpType.PrivateV6), api.ips("web").map { it.type })
            assertEquals("Ready", api.certificates("web").single().status)
        }

    @Test
    fun mapsFailures() =
        runTest {
            val fly = MockFly(HttpStatusCode.Unauthorized to "{}", HttpStatusCode.NotFound to """{"error":"app not found"}""")
            val api = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test")

            assertFailsWith<FlyApiException.Unauthorized> { api.machines("web") }
            val e = assertFailsWith<FlyApiException.Failed> { api.machines("web") }
            assertEquals("HTTP 404: app not found", e.message)
        }

    @Test
    fun listsRegionsWithoutDeprecatedOnes() =
        runTest {
            // Region fields as Fly.io sends them; the list's key is capitalized and the nearest's isn't.
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"Regions":[
                            {"code":"nrt","name":"Tokyo, Japan","gateway_available":true,"mpg_available":true,"requires_paid_plan":false,"geo_region":"asia_pacific","deprecated":false,"capacity":1},
                            {"code":"ams","name":"Amsterdam, Netherlands","mpg_available":false,"geo_region":"europe","deprecated":false},
                            {"code":"old","name":"Gone","geo_region":"europe","deprecated":true}],"nearest":"nrt"}""",
                )

            val regions = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test").regions()

            assertEquals(listOf("ams", "nrt"), regions.regions.map { it.code })
            assertEquals("nrt", regions.nearest)
            assertEquals(listOf(false, true), regions.regions.map { it.mpgAvailable })
            assertEquals(
                "/v1/platform/regions",
                fly.requests
                    .single()
                    .url.encodedPath,
            )
        }
}
