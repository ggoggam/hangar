package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.Instant

/** The token "Try the demo" signs in with. It's never sent anywhere: [flyDemoHttpClient] answers its calls. */
val DEMO_TOKEN = FlyToken("hangar-demo")

val FlyToken.isDemo: Boolean get() = this == DEMO_TOKEN

/**
 * An HTTP client that answers every call the app makes to Fly.io from sample data, without touching
 * the network: what the screens get while [DEMO_TOKEN] is signed in, so the app can be tried, and
 * reviewed for Google Play, without a Fly.io account. Each answer is the JSON the real endpoint
 * sends, so the demo runs the same code as a real session. Logs and metrics follow [nowSeconds], so
 * they move as real ones do. Starting, stopping and restarting machines take effect for as long as
 * the client lasts.
 */
fun flyDemoHttpClient(nowSeconds: () -> Long = { Clock.System.now().epochSeconds }): HttpClient {
    val states = MutableStateFlow(emptyMap<String, String>())
    return HttpClient(MockEngine { request -> respondDemo(request, nowSeconds(), states) })
}

private fun MockRequestHandleScope.respondDemo(
    request: HttpRequestData,
    now: Long,
    states: MutableStateFlow<Map<String, String>>,
): HttpResponseData {
    val body =
        try {
            FlyDemo(now, states).answer(request)
        } catch (e: NotInDemo) {
            null
        }
    val headers = headersOf(HttpHeaders.ContentType, "application/json")
    return if (body == null) {
        respond("""{"error":"not found"}""", HttpStatusCode.NotFound, headers)
    } else {
        respond(body.toString(), HttpStatusCode.OK, headers)
    }
}

private class NotInDemo : Exception()

private class FlyDemo(
    private val now: Long,
    /** Machines' states as the demo's actions left them, by id. */
    private val states: MutableStateFlow<Map<String, String>>,
) {
    fun answer(request: HttpRequestData): JsonElement? {
        val url = request.url
        val path = url.encodedPath

        fun param(name: String) = url.parameters[name]
        return when (url.host) {
            API_HOST -> {
                LOGS.matchEntire(path)?.let { return logs(app(it.groupValues[1]), param("next_token"), param("instance")) }
                PROMETHEUS.matchEntire(path)?.let {
                    val query = param("query") ?: throw NotInDemo()
                    return if (it.groupValues[2] == "query_range") {
                        range(query, param("start")!!.toLong(), param("end")!!.toLong(), param("step")!!.toLong())
                    } else {
                        instant(it.groupValues[1], query)
                    }
                }
                OLDER_POSTGRES.matchEntire(path)?.let { return olderPostgres(it.groupValues[1]) }
                if (path == "/graphql") graphql((request.body as TextContent).text) else null
            }

            MACHINES_HOST -> {
                MACHINE.matchEntire(path)?.let {
                    val app = app(it.groupValues[1])
                    val m = app.machines.firstOrNull { m -> m.id == it.groupValues[2] } ?: throw NotInDemo()
                    return machineAction(app, m, it.groupValues[3], request.method)
                }
                APP.matchEntire(path)?.let {
                    val app = app(it.groupValues[1])
                    return obj("name" to app.name, "app_role" to if (app.postgres) "postgres_cluster" else "")
                }
                MACHINES_API.matchEntire(path)?.let {
                    val app = app(it.groupValues[1])
                    return when (it.groupValues[2]) {
                        "machines" -> {
                            j(app.machines.map { m -> machine(app, m) })
                        }

                        "volumes" -> {
                            j(app.volumes.map(::volume))
                        }

                        "ip_assignments" -> {
                            obj("ips" to ips(app))
                        }

                        else -> {
                            obj(
                                "certificates" to
                                    app.hostnames.map { h ->
                                        mapOf(
                                            "hostname" to h,
                                            "status" to "Ready",
                                            "configured" to true,
                                        )
                                    },
                            )
                        }
                    }
                }
                when (path) {
                    "/v1/platform/regions" -> obj("Regions" to REGIONS.map(::region), "nearest" to "nrt")
                    "/v1/postgres" -> managedPostgres(param("org_slug"))
                    else -> null
                }
            }

            INCIDENTS_HOST -> {
                if (path == "/v1/incidents") obj("incidents" to emptyList<Any>()) else null
            }

            else -> {
                null
            }
        }
    }

    // GraphQL, answered by operation name.

    private fun graphql(text: String): JsonElement {
        val body = FlyJson.parseToJsonElement(text).jsonObject
        val query = body["query"]?.jsonPrimitive?.content.orEmpty()
        val variables = body["variables"] as? JsonObject ?: JsonObject(emptyMap())

        fun variable(name: String) = (variables[name] as? JsonPrimitive)?.contentOrNull
        val data =
            when (OPERATION.find(query)?.groupValues?.get(1)) {
                "Overview" -> {
                    obj("viewer" to viewer(), "apps" to page(APPS))
                }

                "App" -> {
                    val app = app(variable("appName"))
                    obj(
                        "app" to
                            mapOf(
                                "name" to app.name,
                                "hostname" to "${app.name}.fly.dev",
                                "deployed" to app.deployed,
                                "status" to app.status,
                                "platformVersion" to "machines",
                                "organization" to mapOf("slug" to app.org.slug, "internalNumericId" to app.org.numericId),
                                "currentRelease" to release(app, app.releases),
                            ),
                    )
                }

                "Releases" -> {
                    val app = app(variable("appName"))
                    val limit = (variables["limit"] as? JsonPrimitive)?.intOrNull ?: 25
                    val versions = app.releases downTo maxOf(1, app.releases - limit + 1)
                    obj("app" to mapOf("releases" to mapOf("nodes" to versions.map { release(app, it) })))
                }

                "HostIssues" -> {
                    obj("app" to mapOf("hostIssues" to mapOf("nodes" to emptyList<Any>())))
                }

                "Whoami" -> {
                    obj("viewer" to viewer())
                }

                "Organizations" -> {
                    obj("organizations" to mapOf("nodes" to ORGS.map(::organization)))
                }

                "Organization" -> {
                    val org = org(variable("slug"))
                    obj("organization" to organization(org) + ("members" to mapOf("edges" to org.members.map(::member))))
                }

                "OrganizationTokens" -> {
                    val org = org(variable("slug"))
                    obj("organization" to mapOf("limitedAccessTokens" to mapOf("nodes" to org.tokens.map(::accessToken))))
                }

                "AddOns" -> {
                    obj("addOns" to mapOf("nodes" to ADD_ONS.filter { it["type"] == variable("addOnType") }.map { it - "type" }))
                }

                "RoleApps" -> {
                    obj("apps" to page(APPS.filter { it.postgres }))
                }

                else -> {
                    throw NotInDemo()
                }
            }
        return obj("data" to data)
    }

    private fun viewer() = mapOf("email" to EMAIL)

    private fun page(apps: List<DemoApp>) =
        mapOf(
            "pageInfo" to mapOf("hasNextPage" to false, "endCursor" to null),
            "nodes" to
                apps.map { app ->
                    mapOf(
                        "name" to app.name,
                        "deployed" to app.deployed,
                        "status" to app.status,
                        "organization" to mapOf("slug" to app.org.slug),
                        "currentRelease" to mapOf("createdAt" to releasedAt(app, app.releases), "status" to "complete"),
                        "latestRelease" to release(app, app.releases),
                    )
                },
        )

    private fun release(
        app: DemoApp,
        version: Int,
    ): Map<String, Any?> {
        // One failed deploy a few releases back, as most busy apps have.
        val failed = version == app.releases - 3
        return mapOf(
            "id" to "rel_${app.name}_$version",
            "version" to version,
            "description" to if (failed) "Release failed" else "Deploy image",
            "reason" to "deploy",
            "status" to if (failed) "failed" else "complete",
            "imageRef" to "registry.fly.io/${app.name}:deployment-${version.toString().padStart(4, '0')}",
            "stable" to !failed,
            "user" to mapOf("email" to if (version % 3 == 0) "sam@example.com" else EMAIL),
            "createdAt" to releasedAt(app, version),
            "updatedAt" to releasedAt(app, version),
        )
    }

    /** The latest release went out three hours ago, and each before it about a day and a half earlier. */
    private fun releasedAt(
        app: DemoApp,
        version: Int,
    ): String {
        val back = app.releases - version
        return iso(now - 3 * HOUR - back * 37 * HOUR - (noise(app.seed, version.toLong()) * 4 * HOUR).toLong())
    }

    private fun organization(org: DemoOrg): Map<String, Any?> =
        mapOf("slug" to org.slug, "rawSlug" to org.rawSlug, "name" to org.name, "type" to org.type, "viewerRole" to "admin")

    private fun member(member: DemoMember) =
        mapOf(
            "node" to mapOf("id" to member.email, "name" to member.name, "email" to member.email),
            "joinedAt" to iso(now - member.daysAgo * DAY),
            "role" to member.role,
        )

    private fun accessToken(token: DemoToken) =
        mapOf(
            "id" to token.name,
            "name" to token.name,
            "expiresAt" to iso(now + token.expiresInDays * DAY),
            "revokedAt" to if (token.revoked) iso(now - 9 * DAY) else null,
            "user" to mapOf("email" to EMAIL),
        )

    // The Machines API.

    private fun stateOf(m: DemoMachine) = states.value[m.id] ?: m.state

    /** One machine, or what `fly machine start|stop|suspend|restart` and their leases and waits get. */
    private fun machineAction(
        app: DemoApp,
        m: DemoMachine,
        action: String,
        method: HttpMethod,
    ): JsonElement {
        fun set(state: String) = states.update { it + (m.id to state) }
        return when (action) {
            "" -> {
                j(machine(app, m))
            }

            "/lease" -> {
                if (method == HttpMethod.Delete) obj() else obj("status" to "success", "data" to mapOf("nonce" to "demo-${m.id}"))
            }

            "/wait" -> {
                obj("ok" to true)
            }

            "/start" -> {
                obj("status" to "success", "previous_state" to stateOf(m)).also { set("started") }
            }

            "/stop" -> {
                obj().also { set("stopped") }
            }

            "/suspend" -> {
                obj().also { set("suspended") }
            }

            "/restart" -> {
                obj().also { set("started") }
            }

            else -> {
                throw NotInDemo()
            }
        }
    }

    private fun machine(
        app: DemoApp,
        m: DemoMachine,
    ): Map<String, Any?> {
        val started = now - (2 * DAY + noise(m.id.hashCode(), 1) * DAY).toLong()
        return mapOf(
            "id" to m.id,
            "name" to m.name,
            "state" to stateOf(m),
            "region" to m.region,
            "host_status" to "ok",
            "created_at" to iso(started - 20 * DAY),
            "updated_at" to iso(started),
            "image_ref" to
                mapOf(
                    "registry" to "registry.fly.io",
                    "repository" to app.name,
                    "tag" to "deployment-${app.releases.toString().padStart(4, '0')}",
                    "digest" to "sha256:${m.id}",
                ),
            "config" to
                mapOf(
                    "guest" to mapOf("cpu_kind" to m.cpuKind, "cpus" to m.cpus, "memory_mb" to m.memoryMb),
                    "metadata" to mapOf("fly_process_group" to m.group, "fly_release_version" to app.releases.toString()),
                    "mounts" to
                        app.volumes.filter { it.machine == m.id }.map { mapOf("volume" to it.id, "path" to "/data", "name" to it.name) },
                ),
            "checks" to
                if (m.check == null || stateOf(m) != "started") {
                    emptyList()
                } else {
                    listOf(mapOf("name" to m.check, "status" to "passing", "output" to "OK", "updated_at" to iso(now - 12)))
                },
            "events" to
                listOfNotNull(
                    if (m.state ==
                        "stopped"
                    ) {
                        mapOf(
                            "type" to "exit",
                            "status" to "stopped",
                            "source" to "flyd",
                            "timestamp" to (started + 6 * HOUR) * 1000,
                            "request" to mapOf("exit_event" to mapOf("exit_code" to 0, "oom_killed" to false)),
                        )
                    } else {
                        null
                    },
                    mapOf("type" to "start", "status" to "started", "source" to "flyd", "timestamp" to started * 1000),
                    mapOf("type" to "launch", "status" to "created", "source" to "user", "timestamp" to (started - 4) * 1000),
                ),
        )
    }

    private fun volume(v: DemoVolume): Map<String, Any?> {
        val total = v.sizeGb * 1_000_000_000L
        return mapOf(
            "id" to v.id,
            "name" to v.name,
            "state" to "created",
            "size_gb" to v.sizeGb,
            "region" to v.region,
            "encrypted" to true,
            "attached_machine_id" to v.machine,
            "created_at" to iso(now - 41 * DAY),
            "bytes_used" to (total * v.used).toLong(),
            "bytes_total" to total,
        )
    }

    private fun ips(app: DemoApp) =
        if (app.hostnames.isEmpty() && !app.postgres) {
            emptyList()
        } else {
            // Documentation ranges (RFC 5737 and RFC 3849), so no real address appears.
            val n = app.seed % 200 + 10
            listOf(
                mapOf("ip" to "203.0.113.$n", "region" to "global", "shared" to true, "created_at" to iso(now - 41 * DAY)),
                mapOf("ip" to "2001:db8::$n", "region" to "global", "shared" to false, "created_at" to iso(now - 41 * DAY)),
            )
        }

    private fun region(r: List<String>) =
        mapOf(
            "code" to r[0],
            "name" to r[1],
            "geo_region" to r[2],
            "mpg_available" to (r[0] in setOf("ams", "fra", "iad", "lax", "nrt", "ord", "sin", "syd")),
            "requires_paid_plan" to false,
            "deprecated" to false,
        )

    // Managed Postgres, from both of the lists `fly mpg list` reads.

    private fun managedPostgres(orgSlug: String?): JsonElement =
        obj("data" to if (orgSlug == TEAM.rawSlug) listOf(MPG + ("attached_apps" to listOf(mapOf("name" to "api")))) else null)

    private fun olderPostgres(orgSlug: String): JsonElement =
        obj(
            "data" to
                if (orgSlug ==
                    TEAM.rawSlug
                ) {
                    listOf(MPG + mapOf("disk" to 10, "replicas" to 1, "attached_apps" to listOf(mapOf("name" to "api"))))
                } else {
                    null
                },
        )

    // Logs: a line every few seconds from each running machine, the same lines for the same moment.

    private fun logs(
        app: DemoApp,
        cursor: String?,
        instance: String?,
    ): JsonElement {
        val machines = app.machines.filter { stateOf(it) == "started" && (instance == null || it.id == instance) }
        val since = cursor?.removePrefix("demo-")?.toLongOrNull()
        val entries = mutableListOf<Map<String, Any?>>()
        if (machines.isNotEmpty()) {
            if (since == null) {
                // The latest page: back from now until it holds 60 lines.
                var s = now
                while (entries.size < 60 && s > now - HOUR) {
                    entries.addAll(0, linesAt(app, machines, s))
                    s--
                }
            } else {
                for (s in (since + 1)..now) entries += linesAt(app, machines, s)
            }
        }
        return obj("data" to entries.takeLast(100), "meta" to mapOf("next_token" to "demo-$now"))
    }

    private fun linesAt(
        app: DemoApp,
        machines: List<DemoMachine>,
        second: Long,
    ): List<Map<String, Any?>> {
        val roll = noise(app.seed, second)
        if (roll > app.linesPerSecond) return emptyList()
        val m = machines[(noise(app.seed + 7, second) * machines.size).toInt().coerceIn(machines.indices)]
        val line = app.log(second, (noise(app.seed + 13, second) * 1000).toInt())
        return listOf(
            mapOf(
                "id" to "demo-${app.name}-$second",
                "type" to "logs",
                "attributes" to
                    mapOf(
                        "timestamp" to Instant.fromEpochSeconds(second, (roll * 999_999_999).toLong()).toString(),
                        "message" to line.second,
                        "level" to line.first,
                        "instance" to m.id,
                        "region" to m.region,
                        "meta" to emptyMap<String, Any>(),
                    ),
            ),
        )
    }

    // Prometheus: smooth daily curves with a little noise, from each app's typical numbers.

    private fun range(
        query: String,
        start: Long,
        end: Long,
        step: Long,
    ): JsonElement {
        val app =
            APP_LABEL
                .find(query)
                ?.groupValues
                ?.get(1)
                ?.let { name -> APPS.firstOrNull { it.name == name } }
        val running = app?.machines?.filter { stateOf(it) == "started" }.orEmpty()
        val value: ((Long) -> Double)? =
            when {
                app == null || running.isEmpty() -> null
                "fly_instance_cpu" in query -> { t -> wave(app.cpuPercent, app.seed, t, 0.3) }
                "mem_available" in query -> { t -> memoryBytes(running) * wave(app.memoryShare, app.seed + 1, t, 0.08) }
                "fly_instance_memory_mem_total" in query -> { _ -> memoryBytes(running) }
                app.requestsPerMinute == 0.0 -> null
                "status=~" in query -> { t -> maxOf(0.0, wave(app.requestsPerMinute * 0.001, app.seed + 2, t, 1.0)) }
                "fly_edge_http_responses_count" in query -> { t -> wave(app.requestsPerMinute, app.seed + 3, t, 0.35) }
                "histogram_quantile" in query -> { t -> wave(app.p95Millis, app.seed + 4, t, 0.25) }
                else -> null
            }
        val result =
            if (value == null) {
                emptyList()
            } else {
                val first = start - start % step
                listOf(
                    mapOf(
                        "metric" to emptyMap<String, String>(),
                        "values" to (first..end step step).map { t -> listOf(t, value(t).toString()) },
                    ),
                )
            }
        return obj("status" to "success", "data" to mapOf("resultType" to "matrix", "result" to result))
    }

    private fun instant(
        org: String,
        query: String,
    ): JsonElement {
        val apps = APPS.filter { it.org.slug == org || it.org.rawSlug == org }
        val window =
            WINDOW
                .find(query)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull() ?: FlyUsage.WINDOW_SECONDS
        val result =
            when {
                "fly_instance_info" in query -> {
                    apps.flatMap { app ->
                        app.machines.filter { it.state == "started" }.map { m ->
                            sample(
                                mapOf(
                                    "app" to app.name,
                                    "region" to m.region,
                                    "instance" to m.id,
                                    "cpu_kind" to m.cpuKind,
                                    "cpu_count" to m.cpus.toString(),
                                    "memory_mb" to m.memoryMb.toString(),
                                ),
                                window / 60.0,
                            )
                        }
                    }
                }

                "fly_instance_cpu" in query -> {
                    apps.flatMap { app ->
                        app.machines.filter { it.state == "started" }.map { m ->
                            sample(mapOf("app" to app.name, "instance" to m.id), m.cpus * window.toDouble())
                        }
                    }
                }

                "fly_edge_data_out" in query -> {
                    apps.filter { it.bytesOutPerDay > 0 }.flatMap { app ->
                        val regions = app.machines.map { it.region }.distinct()
                        regions.map { region ->
                            sample(
                                mapOf("app" to app.name, "region" to region),
                                app.bytesOutPerDay * window / DAY / regions.size,
                            )
                        }
                    }
                }

                else -> {
                    emptyList()
                }
            }
        return obj("status" to "success", "data" to mapOf("resultType" to "vector", "result" to result))
    }

    private fun sample(
        labels: Map<String, String>,
        value: Double,
    ) = mapOf("metric" to labels, "value" to listOf(now, value.toString()))

    private fun memoryBytes(machines: List<DemoMachine>) = machines.sumOf { it.memoryMb } * 1_048_576.0

    /** [base] rising and falling over the day, by up to [swing] of itself, with some noise. */
    private fun wave(
        base: Double,
        seed: Int,
        t: Long,
        swing: Double,
    ): Double {
        val day = sin(2 * PI * (t % DAY) / DAY + seed)
        val jitter = noise(seed, t / 60) - 0.5
        return base * (1 + swing * (0.7 * day + 0.6 * jitter))
    }

    private fun app(name: String?): DemoApp = APPS.firstOrNull { it.name == name } ?: throw NotInDemo()

    private fun org(slug: String?): DemoOrg = ORGS.firstOrNull { it.slug == slug || it.rawSlug == slug } ?: throw NotInDemo()

    private companion object {
        val API_HOST = Url(FLY_API_URL).host
        val MACHINES_HOST = Url(FLY_MACHINES_URL).host
        val INCIDENTS_HOST = Url(FLY_INCIDENTS_URL).host

        val LOGS = Regex("/api/v1/apps/([^/]+)/logs")
        val PROMETHEUS = Regex("/prometheus/([^/]+)/api/v1/(query|query_range)")
        val OLDER_POSTGRES = Regex("/api/v1/organizations/([^/]+)/postgres")
        val MACHINES_API = Regex("/v1/apps/([^/]+)/(machines|volumes|ip_assignments|certificates)")
        val MACHINE = Regex("/v1/apps/([^/]+)/machines/([^/]+)(|/lease|/wait|/start|/stop|/suspend|/restart)")
        val APP = Regex("/v1/apps/([^/]+)")
        val OPERATION = Regex("""query\s+(\w+)""")
        val APP_LABEL = Regex("""app="([^"]+)"""")
        val WINDOW = Regex("""\[(\d+)s""")

        const val HOUR = 3_600L
        const val DAY = 86_400L
    }
}

/** The same number in [0, 1) for the same [seed] and [n], so the demo looks the same each time. */
private fun noise(
    seed: Int,
    n: Long,
): Double {
    val x = sin(seed * 12.9898 + n * 78.233) * 43_758.5453
    return x - floor(x)
}

private fun iso(seconds: Long) = Instant.fromEpochSeconds(seconds).toString()

private fun obj(vararg pairs: Pair<String, Any?>): JsonElement = j(mapOf(*pairs))

private fun j(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to j(v) })
        is Iterable<*> -> JsonArray(value.map(::j))
        else -> error("not JSON: $value")
    }

// The sample data: a personal organization with a small blog and a team running an API, a website,
// a worker and their databases.

private const val EMAIL = "demo@example.com"

private class DemoOrg(
    val slug: String,
    val rawSlug: String,
    val name: String,
    val type: String,
    val numericId: String,
    val members: List<DemoMember>,
    val tokens: List<DemoToken>,
)

private class DemoMember(
    val name: String,
    val email: String,
    val role: String,
    val daysAgo: Long,
)

private class DemoToken(
    val name: String,
    val expiresInDays: Long,
    val revoked: Boolean = false,
)

private class DemoMachine(
    val id: String,
    val name: String,
    val region: String,
    val state: String = "started",
    val cpuKind: String = "shared",
    val cpus: Int = 1,
    val memoryMb: Int = 256,
    val group: String = "app",
    val check: String? = "servicecheck-00-http-8080",
)

private class DemoVolume(
    val id: String,
    val name: String,
    val sizeGb: Int,
    val region: String,
    val machine: String,
    val used: Double,
)

private class DemoApp(
    val name: String,
    val org: DemoOrg,
    val releases: Int,
    val machines: List<DemoMachine>,
    val status: String = "deployed",
    val volumes: List<DemoVolume> = emptyList(),
    val hostnames: List<String> = emptyList(),
    val postgres: Boolean = false,
    val cpuPercent: Double,
    val memoryShare: Double,
    /** Requests a minute at the edge; 0 for an app with no public HTTP service. */
    val requestsPerMinute: Double = 0.0,
    val p95Millis: Double = 0.0,
    val bytesOutPerDay: Double = 0.0,
    /** Roughly how many lines a second it logs, below 1. */
    val linesPerSecond: Double,
    /** A log line's level and message for a second and a number from 0 to 999. */
    val log: (Long, Int) -> Pair<String, String>,
) {
    val deployed: Boolean get() = status == "deployed"
    val seed: Int get() = name.hashCode() and 0xffff
}

private val PERSONAL =
    DemoOrg(
        slug = "personal",
        rawSlug = "demo-personal",
        name = "Demo",
        type = "PERSONAL",
        numericId = "40001",
        members = listOf(DemoMember("Demo", EMAIL, "ADMIN", 400)),
        tokens = emptyList(),
    )

private val TEAM =
    DemoOrg(
        slug = "demo-team",
        rawSlug = "demo-team",
        name = "Demo Team",
        type = "SHARED",
        numericId = "40002",
        members =
            listOf(
                DemoMember("Demo", EMAIL, "ADMIN", 380),
                DemoMember("Sam Rivera", "sam@example.com", "ADMIN", 300),
                DemoMember("Alex Kim", "alex@example.com", "MEMBER", 95),
            ),
        tokens =
            listOf(
                DemoToken("github-actions-deploy", expiresInDays = 290),
                DemoToken("grafana-readonly", expiresInDays = 7_000),
                DemoToken("old-laptop", expiresInDays = 120, revoked = true),
            ),
    )

private val ORGS = listOf(PERSONAL, TEAM)

private val PATHS = listOf("/v1/orders", "/v1/orders/1042", "/v1/customers", "/v1/invoices", "/v1/products", "/healthz")

private val APPS =
    listOf(
        DemoApp(
            name = "blog",
            org = PERSONAL,
            releases = 14,
            machines = listOf(DemoMachine("6e82d4f1c03e98", "quiet-water-4021", "nrt")),
            volumes = listOf(DemoVolume("vol_r63w8m1oq0ze7p4v", "blog_data", 1, "nrt", "6e82d4f1c03e98", 0.18)),
            hostnames = listOf("blog.example.com"),
            cpuPercent = 3.0,
            memoryShare = 0.42,
            requestsPerMinute = 18.0,
            p95Millis = 24.0,
            bytesOutPerDay = 40e6,
            linesPerSecond = 0.15,
        ) { _, n ->
            val page = listOf("/", "/posts/fly-machines", "/posts/sqlite-on-volumes", "/feed.xml", "/about")[n % 5]
            "info" to "GET $page 200 ${2 + n % 9}ms"
        },
        DemoApp(
            name = "status-bot",
            org = PERSONAL,
            status = "suspended",
            releases = 3,
            machines = listOf(DemoMachine("d891e2a7b45608", "late-meadow-2290", "iad", state = "stopped", check = null)),
            cpuPercent = 0.0,
            memoryShare = 0.0,
            linesPerSecond = 0.0,
        ) { _, _ -> "info" to "" },
        DemoApp(
            name = "api",
            org = TEAM,
            releases = 52,
            machines =
                listOf(
                    DemoMachine("148e270c5d6389", "bold-sun-8812", "nrt", cpus = 2, memoryMb = 1024),
                    DemoMachine("3d8d9e1b2c4f70", "misty-river-1404", "nrt", cpus = 2, memoryMb = 1024),
                    DemoMachine("9185e3a6f0d221", "steady-pine-3377", "iad", cpus = 2, memoryMb = 1024),
                ),
            hostnames = listOf("api.example.com"),
            cpuPercent = 27.0,
            memoryShare = 0.61,
            requestsPerMinute = 240.0,
            p95Millis = 84.0,
            bytesOutPerDay = 2.4e9,
            linesPerSecond = 0.75,
        ) { _, n ->
            val path = PATHS[n % PATHS.size]
            val method = if (n % 7 == 0) "POST" else "GET"
            when {
                n % 97 == 0 -> {
                    "error" to
                        """{"level":"error","msg":"payment provider timed out","path":"/v1/invoices","attempt":2,"duration_ms":10003}"""
                }

                n % 31 == 0 -> {
                    "warn" to
                        """{"level":"warn","msg":"slow request","method":"$method","path":"$path","status":200,"duration_ms":${640 + n}}"""
                }

                else -> {
                    "info" to
                        """{"level":"info","msg":"request","method":"$method","path":"$path","status":${if (method == "POST") 201 else 200},"duration_ms":${8 + n % 60}}"""
                }
            }
        },
        DemoApp(
            name = "web",
            org = TEAM,
            releases = 87,
            machines =
                listOf(
                    DemoMachine("e2865d0b7a1143", "wild-cloud-5120", "nrt", memoryMb = 512),
                    DemoMachine("5683d97c6e4b02", "calm-field-7741", "ams", memoryMb = 512),
                ),
            hostnames = listOf("example.com", "www.example.com"),
            cpuPercent = 11.0,
            memoryShare = 0.55,
            requestsPerMinute = 610.0,
            p95Millis = 46.0,
            bytesOutPerDay = 9.5e9,
            linesPerSecond = 0.6,
        ) { _, n ->
            val page = listOf("/", "/pricing", "/docs", "/docs/getting-started", "/blog", "/_next/static/chunks/main.js")[n % 6]
            "info" to "[next] GET $page ${if (n % 53 == 0) 404 else 200} in ${4 + n % 40}ms"
        },
        DemoApp(
            name = "worker",
            org = TEAM,
            releases = 31,
            machines =
                listOf(
                    DemoMachine(
                        "7811d5e9a40c38",
                        "brave-frost-6630",
                        "iad",
                        cpuKind = "performance",
                        memoryMb = 2048,
                        group = "worker",
                        check = null,
                    ),
                ),
            cpuPercent = 46.0,
            memoryShare = 0.7,
            linesPerSecond = 0.3,
        ) { s, n ->
            when {
                n % 61 == 0 -> "warn" to "job ${48_000 + s % 10_000} retrying in 30s: upstream returned 503"
                else -> "info" to "job ${48_000 + s % 10_000} done: sent ${1 + n % 40} emails in ${(200 + n * 3) / 100.0}s"
            }
        },
        DemoApp(
            name = "api-db",
            org = TEAM,
            releases = 5,
            postgres = true,
            machines =
                listOf(
                    DemoMachine("2865117b0e3d48", "solid-rock-1188", "nrt", memoryMb = 1024, check = "pg"),
                    DemoMachine("4d89ee5c27a106", "still-lake-9056", "nrt", memoryMb = 1024, check = "pg"),
                ),
            volumes =
                listOf(
                    DemoVolume("vol_4y5x9k2d1v0r8q3n", "pg_data", 10, "nrt", "2865117b0e3d48", 0.37),
                    DemoVolume("vol_8n2q7m4w6z1p0k5t", "pg_data", 10, "nrt", "4d89ee5c27a106", 0.37),
                ),
            cpuPercent = 9.0,
            memoryShare = 0.48,
            linesPerSecond = 0.12,
        ) { _, n ->
            if (n % 4 == 0) {
                "info" to "LOG:  checkpoint complete: wrote ${20 + n % 300} buffers (0.${n % 9}%); distance=${n * 7} kB"
            } else {
                "info" to "LOG:  duration: ${1 + n % 30}.${n % 1000} ms  statement: SELECT * FROM orders WHERE customer_id = \$1"
            }
        },
    )

private val ADD_ONS =
    listOf(
        mapOf(
            "type" to "upstash_redis",
            "id" to "demo-redis",
            "name" to "api-cache",
            "addOnPlan" to mapOf("displayName" to "Pay As You Go"),
            "primaryRegion" to "nrt",
            "readRegions" to listOf("iad"),
            "options" to mapOf("eviction" to true),
            "organization" to mapOf("slug" to "demo-team"),
        ),
        mapOf(
            "type" to "tigris",
            "id" to "demo-tigris",
            "name" to "demo-team-uploads",
            "addOnPlan" to null,
            "primaryRegion" to "",
            "readRegions" to null,
            "options" to emptyMap<String, Any>(),
            "organization" to mapOf("slug" to "demo-team"),
        ),
    )

private val MPG: Map<String, Any?> =
    mapOf("id" to "demo-mpg", "name" to "orders", "status" to "ready", "region" to "nrt", "plan" to "basic")

// A few of Fly.io's regions: code, name, area.
private val REGIONS =
    listOf(
        listOf("ams", "Amsterdam, Netherlands", "europe"),
        listOf("arn", "Stockholm, Sweden", "europe"),
        listOf("cdg", "Paris, France", "europe"),
        listOf("dfw", "Dallas, Texas (US)", "north_america"),
        listOf("ewr", "Secaucus, NJ (US)", "north_america"),
        listOf("fra", "Frankfurt, Germany", "europe"),
        listOf("gru", "Sao Paulo, Brazil", "south_america"),
        listOf("iad", "Ashburn, Virginia (US)", "north_america"),
        listOf("jnb", "Johannesburg, South Africa", "africa"),
        listOf("lax", "Los Angeles, California (US)", "north_america"),
        listOf("lhr", "London, United Kingdom", "europe"),
        listOf("nrt", "Tokyo, Japan", "asia_pacific"),
        listOf("ord", "Chicago, Illinois (US)", "north_america"),
        listOf("sin", "Singapore, Singapore", "asia_pacific"),
        listOf("sjc", "San Jose, California (US)", "north_america"),
        listOf("syd", "Sydney, Australia", "asia_pacific"),
        listOf("yyz", "Toronto, Canada", "north_america"),
    )
