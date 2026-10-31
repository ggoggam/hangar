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
import io.ktor.http.decodeURLPart
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

                        "secrets" -> {
                            obj("secrets" to secrets(app))
                        }

                        else -> {
                            obj("certificates" to (app.hostnames + app.unverified).map { h -> certificateSummary(app, h) })
                        }
                    }
                }
                MANAGED_POSTGRES.matchEntire(path)?.let {
                    if (it.groupValues[1] != MPG["id"]) throw NotInDemo()
                    return managedPostgresCluster(it.groupValues[2])
                }
                VOLUME_SNAPSHOTS.matchEntire(path)?.let {
                    val app = app(it.groupValues[1])
                    val volume = app.volumes.firstOrNull { v -> v.id == it.groupValues[2] } ?: throw NotInDemo()
                    return j(snapshots(volume))
                }
                CERTIFICATE.matchEntire(path)?.let {
                    val app = app(it.groupValues[1])
                    val hostname = it.groupValues[2].decodeURLPart()
                    if (hostname !in app.hostnames + app.unverified) throw NotInDemo()
                    return certificate(app, hostname)
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
                    obj(
                        "addOns" to
                            mapOf(
                                "nodes" to
                                    ADD_ONS.filter { it["type"] == variable("addOnType") }.map { it - "type" - "app" - "createdDaysAgo" },
                            ),
                    )
                }

                "Bucket" -> {
                    val bucket =
                        ADD_ONS.firstOrNull { it["type"] == variable("provider") && it["name"] == variable("name") }
                            ?: throw NotInDemo()
                    obj(
                        "addOn" to
                            mapOf(
                                "id" to bucket["id"],
                                "name" to bucket["name"],
                                "status" to "ready",
                                "errorMessage" to null,
                                "createdAt" to iso(now - (bucket["createdDaysAgo"] as Int) * DAY),
                                "options" to bucket["options"],
                                "organization" to bucket["organization"],
                                "app" to mapOf("name" to bucket["app"]),
                            ),
                    )
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
            "imageRef" to "registry.fly.io/${app.name}:${imageTag(app, version)}",
            "stable" to !failed,
            "user" to if (version % 3 == 0) mapOf("email" to "sam@example.com", "name" to "Sam Rivera") else viewer() + ("name" to "Demo"),
            "createdAt" to releasedAt(app, version),
            "updatedAt" to releasedAt(app, version),
        )
    }

    /**
     * The image a release deployed: each its own, but the one before the latest kept the image before
     * it, as setting a secret does.
     */
    private fun imageTag(
        app: DemoApp,
        version: Int,
    ): String {
        val built = if (version == app.releases - 1) version - 1 else version
        return "deployment-${built.toString().padStart(4, '0')}"
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
        // Until an action restarts it, the machine that ran out of memory fails its check.
        val failing = m.outOfMemory && m.id !in states.value
        // The same made-up id for a machine each time, in a ULID's alphabet and length.
        val instance = ("01K8" + m.id.uppercase() + "QX7R2M3N5P").take(26)
        // In Fly.io's private fdaa::/16 range, which isn't reachable from the internet.
        val privateIp = "fdaa:0:${app.org.numericId.toInt().toString(16)}:a7b:${m.id.take(4)}:${m.id.substring(4, 8)}:2"
        val oomAt = now - 40 * 60
        return mapOf(
            "id" to m.id,
            "name" to m.name,
            "state" to stateOf(m),
            "region" to m.region,
            "host_status" to "ok",
            "instance_id" to instance,
            "version" to instance,
            "private_ip" to privateIp,
            "created_at" to iso(started - 20 * DAY),
            "updated_at" to iso(if (m.outOfMemory) oomAt else started),
            "image_ref" to
                mapOf(
                    "registry" to "registry.fly.io",
                    "repository" to app.name,
                    "tag" to imageTag(app, app.releases),
                    "digest" to "sha256:${m.id}",
                ),
            "config" to
                mapOf(
                    "guest" to mapOf("cpu_kind" to m.cpuKind, "cpus" to m.cpus, "memory_mb" to m.memoryMb),
                    "metadata" to mapOf("fly_process_group" to m.group, "fly_release_version" to app.releases.toString()),
                    "mounts" to
                        app.volumes.filter { it.machine == m.id }.map {
                            mapOf("volume" to it.id, "path" to "/data", "name" to it.name, "size_gb" to it.sizeGb)
                        },
                    "services" to services(app),
                ),
            "checks" to
                when {
                    m.check == null || stateOf(m) != "started" -> {
                        emptyList()
                    }

                    failing -> {
                        listOf(
                            mapOf(
                                "name" to m.check,
                                "status" to "critical",
                                "output" to
                                    "Get \"http://[$privateIp]:8080/healthz\": " +
                                    "context deadline exceeded (Client.Timeout exceeded while awaiting headers)",
                                "updated_at" to iso(now - 9),
                            ),
                        )
                    }

                    else -> {
                        listOf(mapOf("name" to m.check, "status" to "passing", "output" to "OK", "updated_at" to iso(now - 12)))
                    }
                },
            "events" to
                listOfNotNull(
                    if (m.outOfMemory) {
                        mapOf("type" to "start", "status" to "started", "source" to "flyd", "timestamp" to (oomAt + 2) * 1000)
                    } else {
                        null
                    },
                    if (m.outOfMemory) {
                        exit(oomAt, code = 137, oom = true, requested = false)
                    } else {
                        null
                    },
                    if (m.state == "stopped") exit(started + 6 * HOUR, code = 0, oom = false, requested = true) else null,
                    mapOf("type" to "start", "status" to "started", "source" to "flyd", "timestamp" to started * 1000),
                    mapOf("type" to "launch", "status" to "created", "source" to "user", "timestamp" to (started - 4) * 1000),
                ),
        )
    }

    private fun exit(
        at: Long,
        code: Int,
        oom: Boolean,
        requested: Boolean,
    ) = mapOf(
        "type" to "exit",
        "status" to "stopped",
        "source" to "flyd",
        "timestamp" to at * 1000,
        "request" to mapOf("exit_event" to mapOf("exit_code" to code, "oom_killed" to oom, "requested_stop" to requested)),
    )

    /** An HTTP service on 8080 for an app with a hostname, as `fly launch` sets one up. */
    private fun services(app: DemoApp): List<Map<String, Any?>> =
        if (app.hostnames.isEmpty()) {
            emptyList()
        } else {
            listOf(
                mapOf(
                    "protocol" to "tcp",
                    "internal_port" to 8080,
                    "autostop" to "stop",
                    "autostart" to true,
                    "min_machines_running" to 1,
                    "ports" to
                        listOf(
                            mapOf("port" to 80, "handlers" to listOf("http"), "force_https" to true),
                            mapOf("port" to 443, "handlers" to listOf("tls", "http")),
                        ),
                ),
            )
        }

    /**
     * The usual secrets, set well before the release the machines run, and on `web` one set since,
     * which `fly secrets deploy` hasn't put on the machines yet.
     */
    private fun secrets(app: DemoApp): List<Map<String, Any?>> {
        val names = if (app.postgres) listOf("OPERATOR_PASSWORD", "REPL_PASSWORD", "SU_PASSWORD") else DEMO_SECRETS
        val set = names.map { it to now - 19 * DAY - (noise(app.seed, it.hashCode().toLong()) * 9 * DAY).toLong() }
        val staged = if (app.name == "web") listOf("STRIPE_WEBHOOK_SECRET" to now - 25 * 60) else emptyList()
        return (set + staged).map { (name, at) ->
            mapOf("name" to name, "digest" to fingerprint(app.name, name), "created_at" to iso(at), "updated_at" to iso(at))
        }
    }

    private fun certificateSummary(
        app: DemoApp,
        hostname: String,
    ): Map<String, Any?> {
        val verified = hostname in app.hostnames
        return mapOf(
            "hostname" to hostname,
            "status" to if (verified) "active" else "pending",
            "configured" to verified,
            "acme_requested" to true,
            "acme_dns_configured" to false,
            "acme_alpn_configured" to verified,
            "acme_http_configured" to verified,
            "has_custom_certificate" to false,
            "has_fly_certificate" to verified,
        )
    }

    /**
     * A hostname's certificates, as `fly certs show` reads them: Let's Encrypt's RSA and ECDSA
     * certificates for one that points at the app, and for one that doesn't yet, no certificate and
     * Fly.io's word on why.
     */
    private fun certificate(
        app: DemoApp,
        hostname: String,
    ): JsonElement {
        val verified = hostname in app.hostnames
        val issuedAt = now - 30 * DAY - (noise(app.seed, hostname.hashCode().toLong()) * 20 * DAY).toLong()
        val expiresAt = issuedAt + 90 * DAY
        val ip = app.ipSuffix
        val certificates =
            if (verified) {
                listOf(
                    mapOf(
                        "source" to "fly",
                        "status" to "active",
                        "created_at" to iso(now - 120 * DAY),
                        "expires_at" to iso(expiresAt),
                        "issuer" to null,
                        "issued" to
                            listOf("rsa", "ecdsa").map {
                                mapOf("type" to it, "expires_at" to iso(expiresAt), "certificate_authority" to "lets_encrypt")
                            },
                    ),
                )
            } else {
                emptyList()
            }
        val errors =
            if (verified) {
                emptyList()
            } else {
                listOf(
                    mapOf(
                        "code" to "dns_not_configured",
                        "message" to "$hostname doesn't resolve to this app's IP addresses.",
                        "remediation" to "Add the A and AAAA records below at your DNS provider, or a CNAME to ${app.name}.fly.dev.",
                        "timestamp" to iso(now - 20 * 60),
                    ),
                )
            }
        return obj(
            "hostname" to hostname,
            "configured" to verified,
            "acme_requested" to true,
            "status" to if (verified) "active" else "pending",
            "rate_limited_until" to null,
            "certificates" to certificates,
            "validation" to
                mapOf(
                    "dns_configured" to false,
                    "alpn_configured" to verified,
                    "http_configured" to verified,
                    "ownership_txt_configured" to false,
                ),
            "dns_requirements" to
                mapOf(
                    "a" to listOf("203.0.113.$ip"),
                    "aaaa" to listOf("2001:db8::$ip"),
                    "cname" to "${app.name}.fly.dev",
                    "acme_challenge" to
                        mapOf("name" to "_acme-challenge.$hostname", "target" to "$hostname.${fingerprint(app.name).take(6)}.flydns.net"),
                    "ownership" to
                        mapOf(
                            "name" to "_fly-ownership.$hostname",
                            "app_value" to "app-${fingerprint(app.name, "app").take(10)}",
                            "org_value" to "org-${fingerprint(app.org.slug).take(10)}",
                        ),
                ),
            "validation_errors" to errors,
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
            "zone" to fingerprint(v.region, v.machine).take(4),
            "encrypted" to true,
            "attached_machine_id" to v.machine,
            "created_at" to iso(now - 41 * DAY),
            "bytes_used" to (total * v.used).toLong(),
            "bytes_total" to total,
            "snapshot_retention" to SNAPSHOT_RETENTION_DAYS,
            "auto_backup_enabled" to v.dailySnapshots,
        )
    }

    /**
     * A volume's daily snapshots, as Fly.io takes them: one a night for as many days as it keeps
     * them, oldest first, each about as big as what the volume held then. None when they're off.
     */
    private fun snapshots(v: DemoVolume): List<Map<String, Any?>> {
        if (!v.dailySnapshots) return emptyList()
        val total = v.sizeGb * 1_000_000_000L
        // Within the hour after 03:00 UTC: this morning's, or yesterday's until 04:00.
        val latest = (now - 4 * HOUR) / DAY * DAY + 3 * HOUR + (noise(v.id.hashCode(), 0) * HOUR).toLong()
        return (SNAPSHOT_RETENTION_DAYS - 1 downTo 0).map { daysBack ->
            val takenAt = latest - daysBack * DAY
            // A little less in it each day further back.
            val held = total * v.used * (1 - 0.012 * daysBack)
            mapOf(
                "id" to "vs_${fingerprint(v.id, "$takenAt")}",
                "size" to (held * (0.55 + 0.05 * noise(v.id.hashCode(), takenAt))).toLong(),
                "volume_size" to total,
                "digest" to fingerprint(v.id, "$takenAt", "digest") + fingerprint(v.id, "$takenAt", "sha"),
                "created_at" to iso(takenAt),
                "status" to "created",
                "retention_days" to SNAPSHOT_RETENTION_DAYS,
            )
        }
    }

    private fun ips(app: DemoApp) =
        if (app.hostnames.isEmpty() && !app.postgres) {
            emptyList()
        } else {
            // Documentation ranges (RFC 5737 and RFC 3849), so no real address appears.
            val n = app.ipSuffix
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

    // The cluster's page, as the Machines API's `/v1/postgres/{id}` and the lists under it answer.
    // Backups every six hours, a full one each midnight (UTC) and incremental ones between.

    private fun managedPostgresCluster(list: String): JsonElement =
        when (list) {
            "/databases" -> {
                obj("data" to listOf("fly-db", "orders").map { mapOf("name" to it) })
            }

            "/users" -> {
                obj(
                    "data" to
                        listOf("fly-user" to "schema_admin", "orders_app" to "writer", "metabase" to "reader").map { (name, role) ->
                            mapOf("username" to name, "role" to role)
                        },
                )
            }

            "/backups" -> {
                val latest = now / (6 * HOUR) * (6 * HOUR)
                obj(
                    "data" to
                        (0 until 8).map { i ->
                            val start = latest - i * 6 * HOUR
                            val full = start % DAY == 0L
                            mapOf(
                                "id" to "demo-backup-${start / HOUR}",
                                "status" to "completed",
                                "type" to if (full) "full" else "incr",
                                "size_bytes" to if (full) 1_288_490_189L else 41_943_040L + i * 1_048_576L,
                                "started_at" to iso(start),
                                "finished_at" to iso(start + if (full) 240 else 30),
                            )
                        },
                )
            }

            else -> {
                obj(
                    "data" to
                        MPG +
                        mapOf(
                            "disk_size_gb" to 10,
                            "storage_used_bytes" to 2_469_606_195L,
                            "storage_provisioned_bytes" to 10_737_418_240L,
                            "cpus" to 2,
                            "cpu_kind" to "shared",
                            "memory_mb" to 1024,
                            "replicas" to 1,
                            "pg_major_version" to "17",
                            "postgis_enabled" to false,
                            "endpoints" to
                                mapOf(
                                    "primary" to
                                        mapOf(
                                            "direct" to mapOf("host" to "direct.demo-mpg.flympg.net", "port" to 5432),
                                            "pooler" to mapOf("host" to "pgbouncer.demo-mpg.flympg.net", "port" to 5432),
                                        ),
                                ),
                            "organization" to mapOf("name" to TEAM.name, "slug" to TEAM.rawSlug),
                            "created_at" to iso(now - 41 * DAY),
                            "attached_apps" to listOf(mapOf("name" to "api")),
                        ),
                )
            }
        }

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
        val MANAGED_POSTGRES = Regex("/v1/postgres/([^/]+)(|/databases|/users|/backups)")
        val MACHINES_API = Regex("/v1/apps/([^/]+)/(machines|volumes|ip_assignments|certificates|secrets)")
        val CERTIFICATE = Regex("/v1/apps/([^/]+)/certificates/([^/]+)")
        val VOLUME_SNAPSHOTS = Regex("/v1/apps/([^/]+)/volumes/([^/]+)/snapshots")
        val MACHINE = Regex("/v1/apps/([^/]+)/machines/([^/]+)(|/lease|/wait|/start|/stop|/suspend|/restart)")
        val APP = Regex("/v1/apps/([^/]+)")
        val OPERATION = Regex("""query\s+(\w+)""")
        val APP_LABEL = Regex("""app="([^"]+)"""")
        val WINDOW = Regex("""\[(\d+)s""")

        const val HOUR = 3_600L
        const val DAY = 86_400L

        /** Fly.io's default, which `fly volumes create` sets unless told otherwise. */
        const val SNAPSHOT_RETENTION_DAYS = 5
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

private val DEMO_SECRETS = listOf("DATABASE_URL", "REDIS_URL", "SECRET_KEY_BASE", "SENTRY_DSN")

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

// The sample data: a personal organization with a small blog and a few side projects, and a team
// running an API, a website, a worker, their databases and some smaller apps.

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
    /** Ran out of memory a while ago, was restarted by Fly.io, and has failed its check since. */
    val outOfMemory: Boolean = false,
)

private class DemoVolume(
    val id: String,
    val name: String,
    val sizeGb: Int,
    val region: String,
    val machine: String,
    val used: Double,
    /** Whether Fly.io snapshots it every night, as it does unless they're turned off. */
    val dailySnapshots: Boolean = true,
)

private class DemoApp(
    val name: String,
    val org: DemoOrg,
    val releases: Int,
    val machines: List<DemoMachine>,
    val status: String = "deployed",
    val volumes: List<DemoVolume> = emptyList(),
    val hostnames: List<String> = emptyList(),
    /** Hostnames added to the app whose DNS doesn't point at it yet, so they have no certificate. */
    val unverified: List<String> = emptyList(),
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

    /** The last part of its IP addresses, which its hostnames' A and AAAA records point at. */
    val ipSuffix: Int get() = seed % 200 + 10
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
            // Nearly full, so Overview's warning shows in the demo.
            volumes = listOf(DemoVolume("vol_r63w8m1oq0ze7p4v", "blog_data", 1, "nrt", "6e82d4f1c03e98", 0.93)),
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
                    DemoMachine("9185e3a6f0d221", "steady-pine-3377", "iad", cpus = 2, memoryMb = 1024, outOfMemory = true),
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
            unverified = listOf("shop.example.com"),
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
                    // The replica's snapshots are off, so the demo has a volume with none.
                    DemoVolume("vol_8n2q7m4w6z1p0k5t", "pg_data", 10, "nrt", "4d89ee5c27a106", 0.37, dailySnapshots = false),
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
        // Smaller apps, so the list is long enough to search and pin in.
        DemoApp(
            name = "api-staging",
            org = TEAM,
            releases = 64,
            machines = listOf(DemoMachine("0e9c3a71d4b852", "pale-dawn-6103", "nrt", memoryMb = 512)),
            hostnames = listOf("staging.api.example.com"),
            cpuPercent = 4.0,
            memoryShare = 0.38,
            requestsPerMinute = 6.0,
            p95Millis = 92.0,
            bytesOutPerDay = 15e6,
            linesPerSecond = 0.08,
        ) { _, n ->
            "info" to
                """{"level":"info","msg":"request","method":"GET","path":"${PATHS[n % PATHS.size]}","status":200,"duration_ms":${10 + n % 80}}"""
        },
        DemoApp(
            name = "admin",
            org = TEAM,
            releases = 22,
            machines = listOf(DemoMachine("5b07e2d9c81f43", "sharp-reed-2215", "nrt", memoryMb = 512)),
            hostnames = listOf("admin.example.com"),
            cpuPercent = 2.0,
            memoryShare = 0.33,
            requestsPerMinute = 3.0,
            p95Millis = 58.0,
            bytesOutPerDay = 8e6,
            linesPerSecond = 0.05,
        ) { _, n ->
            val page = listOf("/orders", "/customers", "/refunds", "/login")[n % 4]
            "info" to "GET $page 200 ${12 + n % 70}ms"
        },
        DemoApp(
            name = "docs",
            org = TEAM,
            releases = 118,
            machines =
                listOf(
                    DemoMachine("a4f16c0e92d375", "green-hill-3348", "nrt"),
                    DemoMachine("c27d58b1e0a964", "loud-bay-8801", "ams"),
                ),
            hostnames = listOf("docs.example.com"),
            cpuPercent = 2.0,
            memoryShare = 0.29,
            requestsPerMinute = 95.0,
            p95Millis = 12.0,
            bytesOutPerDay = 1.1e9,
            linesPerSecond = 0.25,
        ) { _, n ->
            val page = listOf("/", "/quickstart", "/api/orders", "/api/webhooks", "/search.json")[n % 5]
            "info" to "GET $page ${if (n % 41 == 0) 404 else 200} ${1 + n % 6}ms"
        },
        DemoApp(
            name = "image-resizer",
            org = TEAM,
            releases = 9,
            machines =
                listOf(
                    DemoMachine("31c8f0a5e7d290", "cool-sand-5527", "nrt", cpuKind = "performance", memoryMb = 2048, check = null),
                ),
            cpuPercent = 18.0,
            memoryShare = 0.52,
            requestsPerMinute = 40.0,
            p95Millis = 210.0,
            bytesOutPerDay = 3.2e9,
            linesPerSecond = 0.2,
        ) { _, n ->
            "info" to "resized ${1000 + n}.jpg to ${listOf(320, 640, 1280)[n % 3]}w in ${30 + n % 150}ms"
        },
        DemoApp(
            name = "portfolio",
            org = PERSONAL,
            releases = 7,
            machines = listOf(DemoMachine("8d3e61b0f5a927", "soft-wind-1730", "nrt")),
            hostnames = listOf("demo.example.com"),
            cpuPercent = 1.0,
            memoryShare = 0.3,
            requestsPerMinute = 2.0,
            p95Millis = 9.0,
            bytesOutPerDay = 5e6,
            linesPerSecond = 0.03,
        ) { _, n ->
            "info" to "GET ${listOf("/", "/projects", "/contact")[n % 3]} 200 ${1 + n % 5}ms"
        },
        DemoApp(
            name = "discord-bot",
            org = PERSONAL,
            releases = 12,
            machines = listOf(DemoMachine("f60a2c8e13b749", "dry-cliff-4492", "iad", check = null)),
            cpuPercent = 1.0,
            memoryShare = 0.4,
            linesPerSecond = 0.05,
        ) { _, n ->
            "info" to "handled /${listOf("status", "deploy", "uptime")[n % 3]} in ${40 + n % 200}ms"
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
        // A public bucket on a custom domain, and a private one filling itself from S3.
        mapOf(
            "type" to "tigris",
            "id" to "demo-tigris",
            "name" to "demo-team-uploads",
            "addOnPlan" to null,
            "primaryRegion" to "",
            "readRegions" to null,
            "options" to
                mapOf(
                    "public" to true,
                    "accelerate" to false,
                    "website" to mapOf("domain_name" to "cdn.example.com"),
                ),
            "organization" to mapOf("slug" to "demo-team"),
            "app" to "image-resizer",
            "createdDaysAgo" to 140,
        ),
        mapOf(
            "type" to "tigris",
            "id" to "demo-tigris-backups",
            "name" to "portfolio-backups",
            "addOnPlan" to null,
            "primaryRegion" to "",
            "readRegions" to null,
            "options" to
                mapOf(
                    "public" to false,
                    "accelerate" to false,
                    "website" to mapOf("domain_name" to ""),
                    "shadow_bucket" to
                        mapOf(
                            "name" to "portfolio-backups-old",
                            "endpoint" to "https://s3.us-east-1.amazonaws.com",
                            "region" to "us-east-1",
                            "write_through" to true,
                            "access_key" to "DEMO-ACCESS-KEY",
                            "secret_key" to "DEMO-SECRET-KEY",
                        ),
                ),
            "organization" to mapOf("slug" to "personal"),
            "app" to "portfolio",
            "createdDaysAgo" to 31,
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
