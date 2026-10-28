package dev.ggoggam.hangar.core

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlyMachineActionsTest {
    private val ok = HttpStatusCode.OK
    private val lease = ok to """{"status":"success","data":{"nonce":"n1","expires_at":1,"owner":"me","version":"v"}}"""

    private fun machine(
        id: String,
        state: String,
        check: String = "passing",
    ) = """{"id":"$id","state":"$state","host_status":"ok","checks":[{"name":"http","status":"$check"}]}"""

    private val MockFly.calls get() =
        requests.map {
            "${it.method.value} ${it.url.encodedPathAndQuery.removePrefix(
                "/v1/apps/web/machines",
            )}"
        }

    @Test
    fun stopsUnderALeaseAndReleasesIt() =
        runTest {
            val fly = MockFly(lease, ok to "", ok to "")

            FlyMachineActions(fly.http, MIXED_TOKEN, "https://m.test").stopMachine("web", "m1")

            assertEquals(listOf("POST /m1/lease?ttl=120", "POST /m1/stop", "DELETE /m1/lease"), fly.calls)
            assertEquals(listOf(null, "n1", "n1"), fly.requests.map { it.headers["fly-machine-lease-nonce"] })
            assertEquals("""{"id":"m1"}""", (fly.requests[1].body as TextContent).text)
            assertEquals("FlyV1 fm2_a,fm2_b", fly.requests[1].headers["Authorization"])
        }

    @Test
    fun startReportsAnErrorStatusAndStillReleases() =
        runTest {
            val fly = MockFly(lease, ok to """{"status":"error","message":"no capacity"}""", ok to "")

            val e =
                assertFailsWith<FlyApiException.Failed> {
                    FlyMachineActions(
                        fly.http,
                        MIXED_TOKEN,
                        "https://m.test",
                    ).startMachine("web", "m1")
                }

            assertEquals("no capacity", e.message)
            assertEquals(HttpMethod.Delete, fly.requests.last().method)
        }

    @Test
    fun aRefusalIsNotAllowedRatherThanASignOut() =
        runTest {
            val fly = MockFly(HttpStatusCode.Unauthorized to """{"error":"unauthorized"}""", HttpStatusCode.Forbidden to "{}")
            val actions = FlyMachineActions(fly.http, MIXED_TOKEN, "https://m.test")

            assertFailsWith<FlyActionException.NotAllowed> { actions.suspendMachine("web", "m1") }
            assertFailsWith<FlyActionException.NotAllowed> { actions.suspendMachine("web", "m1") }
            // No lease came back, so there was none to release.
            assertEquals(2, fly.requests.size)
        }

    @Test
    fun aLeaseHeldElsewhereIsTriedAgainThenBusy() =
        runTest {
            val held = HttpStatusCode.Conflict to """{"error":"lease currently held"}"""
            val fly = MockFly(held, held, held, held)

            assertFailsWith<FlyActionException.Busy> {
                FlyMachineActions(
                    fly.http,
                    MIXED_TOKEN,
                    "https://m.test",
                ).restartMachine("web", "m1")
            }
            assertEquals(4, fly.requests.size)
        }

    @Test
    fun restartsTheRunningMachinesOneAfterAnother() =
        runTest {
            val fly =
                MockFly(
                    ok to """{"name":"web","app_role":""}""",
                    ok to "[${machine("m1", "started")},${machine("m2", "stopped")},${machine("m3", "started")}]",
                    lease,
                    lease,
                    // m1: restart, wait for it to start, then its checks, the first time still failing.
                    ok to "",
                    HttpStatusCode.RequestTimeout to """{"error":"deadline_exceeded"}""",
                    ok to """{"ok":true}""",
                    ok to machine("m1", "started", check = "critical"),
                    ok to machine("m1", "started"),
                    // m3.
                    ok to "",
                    ok to """{"ok":true}""",
                    ok to machine("m3", "started"),
                    ok to "",
                    ok to "",
                )
            val progress = mutableListOf<Pair<Int, Int>>()

            FlyMachineActions(fly.http, MIXED_TOKEN, "https://m.test").restartApp("web") { done, total -> progress += done to total }

            assertEquals(
                listOf(
                    "GET /v1/apps/web",
                    "GET ",
                    "POST /m1/lease?ttl=120",
                    "POST /m3/lease?ttl=120",
                    "POST /m1/restart?force_stop=false",
                    "GET /m1/wait?state=started&timeout=20",
                    "GET /m1/wait?state=started&timeout=20",
                    "GET /m1",
                    "GET /m1",
                    "POST /m3/restart?force_stop=false",
                    "GET /m3/wait?state=started&timeout=20",
                    "GET /m3",
                    "DELETE /m1/lease",
                    "DELETE /m3/lease",
                ),
                fly.calls,
            )
            assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        }

    @Test
    fun leavesPostgresAppsToFlyPgRestart() =
        runTest {
            val fly = MockFly(ok to """{"name":"db","app_role":"postgres_cluster"}""")

            assertFailsWith<FlyActionException.PostgresApp> { FlyMachineActions(fly.http, MIXED_TOKEN, "https://m.test").restartApp("db") }
            assertEquals(1, fly.requests.size)
        }
}
