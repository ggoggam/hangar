package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class FlyWatchTest {
    private val now = 1_800_000_000L

    /** Fly.io as the watch sees it, answering by route; tests change it between looks. */
    private class World {
        var incidents = """[]"""
        var incidentsStatus = HttpStatusCode.OK
        var apps = listOf("web")
        val machines = mutableMapOf<String, String>()
        val machinesStatus = mutableMapOf<String, HttpStatusCode>()
        val hostIssues = mutableMapOf<String, String>()

        /** Each app's latest release, as JSON. */
        val releases = mutableMapOf<String, String>()
        val undeployed = mutableSetOf<String>()
        var billingStatus = "CURRENT"
        var billingFails = false

        /** Minutes `web` ran as a shared-cpu-1x 256 MB in iad over the last two days. */
        var minutesRun = 0
        var promQueries = 0
        var unauthorized = false

        val http =
            HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    val (status, body) =
                        when {
                            unauthorized -> {
                                HttpStatusCode.Unauthorized to "{}"
                            }

                            path == "/v1/incidents" -> {
                                incidentsStatus to """{"incidents":$incidents}"""
                            }

                            path.endsWith("/api/v1/query") -> {
                                promQueries++
                                val result =
                                    if ("fly_instance_info" in request.url.parameters["query"].orEmpty() && minutesRun > 0) {
                                        """[{"metric":{"app":"web","region":"iad","instance":"i1","cpu_kind":"shared",
                                            "cpu_count":"1","memory_mb":"256"},"value":[0,"$minutesRun"]}]"""
                                    } else {
                                        "[]"
                                    }
                                HttpStatusCode.OK to """{"status":"success","data":{"resultType":"vector","result":$result}}"""
                            }

                            path.endsWith("/volumes") -> {
                                HttpStatusCode.OK to "[]"
                            }

                            path.startsWith("/v1/apps/") -> {
                                val app = path.removePrefix("/v1/apps/").substringBefore('/')
                                (machinesStatus[app] ?: HttpStatusCode.OK) to (machines[app] ?: "[]")
                            }

                            else -> {
                                val query = request.body.toByteArray().decodeToString()
                                if ("query Overview" in query) {
                                    val nodes =
                                        apps.joinToString(",") {
                                            val deployed = it !in undeployed
                                            """{"name":"$it","status":"${if (deployed) "deployed" else "pending"}","deployed":$deployed,
                                                "organization":{"slug":"personal"},"latestRelease":${releases[it] ?: "null"}}"""
                                        }
                                    HttpStatusCode.OK to
                                        """{"data":{"viewer":{"email":"a@b.c"},"apps":{"pageInfo":{"hasNextPage":false},"nodes":[$nodes]}}}"""
                                } else if ("query Organizations" in query) {
                                    HttpStatusCode.OK to
                                        """{"data":{"organizations":{"nodes":[{"slug":"personal","name":"Me","type":"PERSONAL"}]}}}"""
                                } else if ("query OrganizationBilling" in query && billingFails) {
                                    HttpStatusCode.BadGateway to "bad gateway"
                                } else if ("query OrganizationBilling" in query) {
                                    HttpStatusCode.OK to """{"data":{"organization":{"billingStatus":"$billingStatus"}}}"""
                                } else {
                                    val app = Regex(""""appName":"([^"]+)"""").find(query)!!.groupValues[1]
                                    HttpStatusCode.OK to
                                        """{"data":{"app":{"hostIssues":{"nodes":${hostIssues[app] ?: "[]"}}}}}"""
                                }
                            }
                        }
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
    }

    private class MemoryStore : TokenStore {
        var value: String? = "fo1_x"

        override fun read() = value

        override fun write(token: String) {
            value = token
        }

        override fun clear() {
            value = null
        }
    }

    private fun watch(world: World) =
        FlyWatch(
            world.http,
            FlySession(MemoryStore(), FlyDischarges(world.http)),
            "https://api.test",
            "https://machines.test",
            "https://incidents.test",
        )

    private fun machine(
        state: String = "started",
        checks: String = "[]",
        events: String = "[]",
    ) = """[{"id":"m1","name":"bold-fog","state":"$state","region":"nrt","checks":$checks,"events":$events}]"""

    private fun release(
        version: Int,
        status: String,
        updatedAt: Long,
    ) = """{"version":$version,"status":"$status","createdAt":"${Instant.fromEpochSeconds(updatedAt - 120)}",
        "updatedAt":"${Instant.fromEpochSeconds(updatedAt)}","user":{"email":"a@b.c"}}"""

    private val critical = """[{"name":"servicecheck-00-http-8080","status":"critical"}]"""

    @Test
    fun theFirstLookOnlyTakesNote() =
        runTest {
            val world = World()
            world.incidents = """[{"id":"i1","name":"Elevated API errors","status":"investigating"}]"""
            world.machines["web"] = machine(state = "failed", checks = critical)
            world.hostIssues["web"] = """[{"internalId":"h1","message":"Host is down"}]"""
            val watch = watch(world)

            val first = watch.check(null, now)
            assertEquals(emptyList(), first.alerts)

            // Nothing changed, so the second look has nothing to say either, failing checks included.
            assertEquals(emptyList(), watch.check(first.state, now + 900).alerts)
        }

    @Test
    fun incidentsOpenAndResolve() =
        runTest {
            val world = World()
            val watch = watch(world)
            val first = watch.check(null, now)

            world.incidents = """[{"id":"i1","name":"Elevated API errors","status":"investigating"}]"""
            val opened = watch.check(first.state, now + 900)
            assertEquals("Elevated API errors", assertIs<FlyAlert.IncidentOpened>(opened.alerts.single()).incident.name)

            world.incidents = "[]"
            val resolved = watch.check(opened.state, now + 1800)
            val alert = assertIs<FlyAlert.IncidentResolved>(resolved.alerts.single())
            assertEquals("Elevated API errors", alert.name)
            assertEquals("incident:i1", alert.key)
        }

    @Test
    fun anUnreachableFeedResolvesNothing() =
        runTest {
            val world = World()
            world.incidents = """[{"id":"i1","name":"Elevated API errors","status":"investigating"}]"""
            val watch = watch(world)
            val first = watch.check(null, now)

            world.incidentsStatus = HttpStatusCode.BadGateway
            val down = watch.check(first.state, now + 900)
            assertEquals(emptyList(), down.alerts)
            assertEquals(setOf("i1"), down.state.incidents.keys)
        }

    @Test
    fun aFailedMachineAndAHostIssueAreReportedOnce() =
        runTest {
            val world = World()
            world.machines["web"] = machine()
            val watch = watch(world)
            val first = watch.check(null, now)

            world.machines["web"] = machine(state = "failed")
            world.hostIssues["web"] = """[{"internalId":"h1","message":"Host is down"}]"""
            val broken = watch.check(first.state, now + 900)
            assertEquals(2, broken.alerts.size)
            assertEquals(
                "Host is down",
                broken.alerts
                    .filterIsInstance<FlyAlert.HostIssue>()
                    .single()
                    .issue.message,
            )
            assertEquals(
                "m1",
                broken.alerts
                    .filterIsInstance<FlyAlert.MachineFailed>()
                    .single()
                    .machine.id,
            )

            assertEquals(emptyList(), watch.check(broken.state, now + 1800).alerts)

            // Once it's over, it's reported again if it comes back.
            world.machines["web"] = machine()
            world.hostIssues["web"] = "[]"
            val fixed = watch.check(broken.state, now + 2700)
            world.machines["web"] = machine(state = "failed")
            assertIs<FlyAlert.MachineFailed>(watch.check(fixed.state, now + 3600).alerts.single())
        }

    @Test
    fun checksCountOnceFailingForTwoLooks() =
        runTest {
            val world = World()
            world.machines["web"] = machine()
            val watch = watch(world)
            val first = watch.check(null, now)

            world.machines["web"] = machine(checks = critical)
            val once = watch.check(first.state, now + 900)
            assertEquals(emptyList(), once.alerts)

            val twice = watch.check(once.state, now + 1800)
            val alert = assertIs<FlyAlert.ChecksFailing>(twice.alerts.single())
            assertEquals(listOf("servicecheck-00-http-8080"), alert.checks)

            assertEquals(emptyList(), watch.check(twice.state, now + 2700).alerts)
        }

    @Test
    fun crashesSinceTheLastLookButNotStopsAskedFor() =
        runTest {
            val world = World()
            world.machines["web"] = machine()
            val watch = watch(world)
            val first = watch.check(null, now)

            fun exit(
                at: Long,
                code: Int,
                oom: Boolean = false,
                requested: Boolean = false,
            ) = """{"type":"exit","status":"stopped","source":"flyd","timestamp":${at * 1000},
                "request":{"exit_event":{"exit_code":$code,"oom_killed":$oom,"requested_stop":$requested}}}"""

            world.machines["web"] =
                machine(
                    state = "stopped",
                    events = "[${exit(now - 60, 1)},${exit(now + 100, 143, requested = true)},${exit(now + 200, 0)}]",
                )
            assertEquals(emptyList(), watch.check(first.state, now + 900).alerts)

            world.machines["web"] = machine(state = "stopped", events = "[${exit(now + 1000, 137, oom = true)},${exit(now + 950, 1)}]")
            val crashed = assertIs<FlyAlert.MachineCrashed>(watch.check(first.state, now + 1800).alerts.single())
            assertTrue(crashed.oomKilled)
            assertEquals(137, crashed.exitCode)
        }

    @Test
    fun deploysThatEndAreReportedOnce() =
        runTest {
            val world = World()
            world.releases["web"] = release(1, "complete", now - 86_400)
            val watch = watch(world)
            val first = watch.check(null, now)

            world.releases["web"] = release(2, "running", now + 600)
            val deploying = watch.check(first.state, now + 900)
            assertEquals(emptyList(), deploying.alerts)

            world.releases["web"] = release(2, "complete", now + 1000)
            val done = watch.check(deploying.state, now + 1800)
            val alert = assertIs<FlyAlert.Deploy>(done.alerts.single())
            assertEquals(2, alert.release.version)
            assertEquals("complete", alert.release.status)
            assertEquals("personal", alert.org)

            assertEquals(emptyList(), watch.check(done.state, now + 2700).alerts)

            // A deploy that starts and fails between two looks.
            world.releases["web"] = release(3, "failed", now + 3000)
            assertEquals("failed", assertIs<FlyAlert.Deploy>(watch.check(done.state, now + 3600).alerts.single()).release.status)
        }

    @Test
    fun aDeploySeenRunningIsReportedWhateverTheClockSays() =
        runTest {
            val world = World()
            world.releases["web"] = release(4, "running", now - 60)
            val watch = watch(world)
            val first = watch.check(null, now)

            // Ended, by Fly.io's clock, before the phone's last look.
            world.releases["web"] = release(4, "complete", now - 30)
            assertEquals(4, assertIs<FlyAlert.Deploy>(watch.check(first.state, now + 900).alerts.single()).release.version)
        }

    @Test
    fun aNewAppsEarlierDeployIsntNews() =
        runTest {
            val world = World()
            val watch = watch(world)
            val first = watch.check(null, now)

            world.apps = listOf("web", "api")
            world.releases["api"] = release(9, "complete", now - 3600)
            assertEquals(emptyList(), watch.check(first.state, now + 900).alerts)
        }

    @Test
    fun aFailedFirstDeployIsReported() =
        runTest {
            val world = World()
            world.apps = listOf("web", "new")
            world.undeployed += "new"
            val watch = watch(world)
            val first = watch.check(null, now)

            world.releases["new"] = release(1, "failed", now + 500)
            val alert = assertIs<FlyAlert.Deploy>(watch.check(first.state, now + 900).alerts.single())
            assertEquals("new", alert.app)
        }

    @Test
    fun anUnreachableAppKeepsItsMarks() =
        runTest {
            val world = World()
            world.machines["web"] = machine(state = "failed")
            val watch = watch(world)
            val first = watch.check(null, now)

            world.machinesStatus["web"] = HttpStatusCode.ServiceUnavailable
            val down = watch.check(first.state, now + 900)
            assertEquals(emptyList(), down.alerts)

            // Still failed when it answers again: already known, so not reported again.
            world.machinesStatus.remove("web")
            assertEquals(emptyList(), watch.check(down.state, now + 1800).alerts)
        }

    @Test
    fun theStateHoldsNoNames() =
        runTest {
            val world = World()
            world.apps = listOf("secret-app")
            world.machines["secret-app"] = machine(state = "failed")
            world.hostIssues["secret-app"] = """[{"internalId":"h1","message":"Host is down"}]"""
            val encoded = watch(world).check(null, now).state.encode()

            assertFalse("secret-app" in encoded)
            assertFalse("m1" in encoded)
            assertFalse("h1" in encoded)
            assertEquals(1, FlyWatchState.decode(encoded)!!.apps.size)
        }

    @Test
    fun anotherAccountsStateIsIgnored() =
        runTest {
            val world = World()
            world.machines["web"] = machine()
            val first = watch(world).check(null, now)

            world.machines["web"] = machine(state = "failed")
            val store = MemoryStore().apply { value = "fo1_someone_else" }
            val other =
                FlyWatch(
                    world.http,
                    FlySession(store, FlyDischarges(world.http)),
                    "https://api.test",
                    "https://machines.test",
                    "https://incidents.test",
                )
            assertEquals(emptyList(), other.check(first.state, now + 900).alerts)
        }

    @Test
    fun anEndedSessionEndsTheCheck() =
        runTest {
            val world = World()
            world.unauthorized = true
            assertFailsWith<FlyApiException.Unauthorized> { watch(world).check(null, now) }
        }

    @Test
    fun billingProblemsAreReportedWhenAskedFor() =
        runTest {
            val world = World()
            val watch = watch(world)
            val billing = FlyWatchOptions(billing = true)
            val first = watch.check(null, now, billing)

            world.billingStatus = "PAST_DUE"
            // Not asked for: nothing to say, and nothing forgotten.
            val unasked = watch.check(first.state, now + 900)
            assertEquals(emptyList(), unasked.alerts)

            val due = watch.check(unasked.state, now + 1800, billing)
            val alert = assertIs<FlyAlert.BillingProblem>(due.alerts.single())
            assertEquals("Me", alert.name)
            assertEquals("PAST_DUE", alert.status)
            assertEquals(emptyList(), watch.check(due.state, now + 2700, billing).alerts)

            // A status that can't be read keeps what was known, so it isn't news when it can again.
            world.billingFails = true
            val unread = watch.check(due.state, now + 2700, billing)
            world.billingFails = false
            assertEquals(emptyList(), watch.check(unread.state, now + 3600, billing).alerts)

            // From one problem to another is news too.
            world.billingStatus = "DELINQUENT"
            assertEquals(
                "DELINQUENT",
                assertIs<FlyAlert.BillingProblem>(watch.check(due.state, now + 3600, billing).alerts.single()).status,
            )
        }

    @Test
    fun spendOverTheBudgetIsReportedAtMostFourTimesADay() =
        runTest {
            val world = World()
            // Two days of a shared-cpu-1x 256 MB in iad, $2.19 a month.
            world.minutesRun = 2 * 24 * 60
            val watch = watch(world)
            val first = watch.check(null, now, FlyWatchOptions(monthlyBudgetUsd = 5.0))
            assertTrue(world.promQueries > 0)

            // Lowered under what it's on pace for, but not looked at again for six hours.
            val queries = world.promQueries
            val soon = watch.check(first.state, now + 900, FlyWatchOptions(monthlyBudgetUsd = 2.0))
            assertEquals(emptyList(), soon.alerts)
            assertEquals(queries, world.promQueries)

            val later = watch.check(soon.state, now + 6 * 3600, FlyWatchOptions(monthlyBudgetUsd = 2.0))
            val alert = assertIs<FlyAlert.OverBudget>(later.alerts.single())
            assertEquals("personal", alert.org)
            assertTrue(alert.projectedUsd > 2.18 && alert.projectedUsd < 2.20, "${alert.projectedUsd}")

            assertEquals(emptyList(), watch.check(later.state, now + 12 * 3600, FlyWatchOptions(monthlyBudgetUsd = 2.0)).alerts)
            // A new budget it's over already is news.
            assertIs<FlyAlert.OverBudget>(
                watch.check(later.state, now + 12 * 3600, FlyWatchOptions(monthlyBudgetUsd = 1.0)).alerts.single(),
            )
        }

    @Test
    fun garbageStateStartsAfresh() {
        assertEquals(null, FlyWatchState.decode("not json"))
    }
}
