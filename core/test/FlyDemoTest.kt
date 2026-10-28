package dev.ggoggam.hangar.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Every call the screens make, against the demo: each must answer, as Google Play's reviewers only see the demo. */
class FlyDemoTest {
    private val now = 1_793_000_000L
    private val http = flyDemoHttpClient { now }
    private val token = DEMO_TOKEN

    @Test
    fun answersTheAppList() =
        runTest {
            val overview = FlyApi(http, token).overview()
            assertEquals("demo@example.com", overview.email)
            // Enough for the app list's search field.
            assertEquals(
                listOf(
                    "admin",
                    "api",
                    "api-db",
                    "api-staging",
                    "docs",
                    "image-resizer",
                    "web",
                    "worker",
                    "blog",
                    "discord-bot",
                    "portfolio",
                    "status-bot",
                ),
                overview.apps.map { it.name },
            )
            assertTrue(overview.apps.all { it.currentRelease != null })
            assertTrue(FlyIncidents(http).active().isEmpty())
        }

    @Test
    fun machineActionsTakeEffect() =
        runTest {
            val machines = FlyMachines(http, token)
            val actions = FlyMachineActions(http, token)
            val id = machines.machines("api").first().id

            actions.stopMachine("api", id)
            assertEquals("stopped", machines.machines("api").first { it.id == id }.state)
            actions.startMachine("api", id)
            assertEquals("started", machines.machines("api").first { it.id == id }.state)
            actions.restartApp("api")
            assertTrue(machines.machines("api").all { it.state == "started" })
            assertFailsWith<FlyActionException.PostgresApp> { actions.restartApp("api-db") }
        }

    @Test
    fun hasAMachineThatRanOutOfMemory() =
        runTest {
            val machines = FlyMachines(http, token)
            val listed = machines.machines("api").single { m -> m.events.any { it.oomKilled } }
            val m = machines.machine("api", listed.id)

            assertEquals("critical", m.checks.single().status)
            assertTrue(
                m.checks
                    .single()
                    .output
                    .isNotEmpty(),
            )
            val exit = m.events.first { it.type == "exit" }
            assertTrue(exit.oomKilled)
            assertEquals(137, exit.exitCode)
            assertTrue(m.privateIp.startsWith("fdaa:"))
            assertEquals(26, m.instanceId.length)
            assertEquals(
                listOf("80", "443"),
                m.config
                    ?.services
                    ?.single()
                    ?.ports
                    ?.map { it.label },
            )
            // Restarting it brings its check back.
            FlyMachineActions(http, token).restartMachine("api", m.id)
            assertEquals(
                "passing",
                machines
                    .machine("api", m.id)
                    .checks
                    .single()
                    .status,
            )
            assertFailsWith<FlyApiException.Failed> { machines.machine("api", "nope") }
        }

    @Test
    fun answersEveryDashboardTab() =
        runTest {
            val api = FlyApi(http, token)
            val app = api.app("api")
            assertEquals("demo-team", app.organization.slug)
            assertEquals(52, app.currentRelease?.version)
            assertTrue(grafanaUrl(app) != null)
            assertEquals(25, api.releases("api").size)
            assertTrue(api.hostIssues("api").isEmpty())

            val machines = FlyMachines(http, token)
            assertEquals(3, machines.machines("api").size)
            assertEquals(
                "shared-cpu-2x",
                machines
                    .machines("api")
                    .first()
                    .config
                    ?.guest
                    ?.size,
            )
            assertEquals(2, machines.volumes("api-db").size)
            assertTrue(machines.volumes("api-db").none { it.nearlyFull })
            // One volume nearly full, for Overview's warning.
            assertTrue(machines.volumes("blog").single().nearlyFull)
            assertEquals(2, machines.ips("web").size)
            assertEquals(FlyCertState.Issued, machines.certificates("web").first().state)

            val metrics = FlyMetrics(http, token).dashboard("demo-team", "api", now)
            for (series in listOf(
                metrics.cpuPercent,
                metrics.memoryBytes,
                metrics.memoryTotalBytes,
                metrics.requestsPerMinute,
                metrics.p95Millis,
            )) {
                assertTrue(series.size in 55..65, "about 60 points an hour")
                assertTrue(series.all { it.value >= 0 })
            }
            // The worker serves no HTTP, so it has no traffic series, as on Fly.io.
            assertTrue(FlyMetrics(http, token).dashboard("demo-team", "worker", now).requestsPerMinute.isEmpty())

            val logs = FlyLogs(http, token)
            val first = logs.page("api", cursor = null)
            assertEquals(60, first.entries.size)
            assertTrue(first.entries.all { it.instance.isNotEmpty() && it.region.isNotEmpty() })
            val one = machines.machines("api").first().id
            assertTrue(logs.page("api", cursor = null, instance = one).entries.all { it.instance == one })
        }

    @Test
    fun logsMoveOnWithTheClock() =
        runTest {
            var clock = now
            val logs = FlyLogs(flyDemoHttpClient { clock }, token)
            val first = logs.page("web", cursor = null)
            clock += 60
            val next = logs.page("web", cursor = first.cursor)
            assertTrue(next.entries.isNotEmpty())
            assertTrue(next.entries.none { e -> first.entries.any { it.id == e.id } })
            // The same moment always logs the same lines.
            assertEquals(first.entries.map { it.message }, FlyLogs(http, token).page("web", cursor = null).entries.map { it.message })
        }

    @Test
    fun estimatesUsage() =
        runTest {
            val usage = FlyUsage(http, token).estimate("demo-team", listOf("api", "api-db", "web", "worker"), now)
            assertTrue(usage.estimatedUsd > 0)
            assertEquals(20, usage.volumeGb)
            assertTrue(usage.app("worker")!!.machineSeconds > 0)
            assertEquals(0.0, usage.app("worker")!!.dataOutBytes)
            assertTrue(!usage.unpricedRegion)
        }

    @Test
    fun answersDataAndSettings() =
        runTest {
            val api = FlyApi(http, token)
            val orgs = api.organizations()
            assertEquals(listOf("demo-team", "personal"), orgs.map { it.slug })
            assertEquals("demo@example.com", api.whoami())
            assertEquals(3, api.organization("demo-team").members.size)
            assertEquals(3, api.organizationTokens("demo-team").size)
            assertTrue(api.organizationTokens("personal").isEmpty())
            assertEquals("api-cache", api.addOns("upstash_redis").single().name)
            assertEquals(null, api.addOns("tigris").single().primaryRegion)
            assertEquals(listOf("api-db"), api.postgresApps().map { it.name })

            val postgres = FlyManagedPostgres(http, token)
            val team = postgres.clusters(orgs.first { it.slug == "demo-team" }).single()
            assertEquals(10, team.diskGb)
            assertEquals(listOf("api"), team.attachedApps)
            assertTrue(postgres.clusters(orgs.first { it.slug == "personal" }).isEmpty())
            val cluster = postgres.cluster(team.id, orgs.first { it.slug == "demo-team" })
            assertEquals("orders", cluster.name)
            assertEquals("17", cluster.postgresVersion)
            assertEquals(listOf("api"), cluster.attachedApps)
            assertEquals(listOf("fly-db", "orders"), postgres.databases(cluster))
            assertEquals(3, postgres.users(cluster).size)
            val backups = postgres.backups(cluster)
            assertEquals(8, backups.size)
            assertTrue(backups.any { it.type == "full" })
            assertFailsWith<FlyApiException> { postgres.cluster("not-a-cluster", orgs.first { it.slug == "demo-team" }) }

            val regions = FlyMachines(http, token).regions()
            assertEquals("nrt", regions.nearest)
            assertTrue(regions.regions.size > 10)
        }

    @Test
    fun turnsDownWhatItDoesntHave() =
        runTest {
            assertFailsWith<FlyApiException> { FlyApi(http, token).app("not-an-app") }
            assertFailsWith<FlyApiException> { FlyMachines(http, token).machines("not-an-app") }
        }
}
