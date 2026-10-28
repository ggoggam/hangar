package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.kotlincrypto.hash.sha2.SHA256

/**
 * The background check behind notifications: what has gone wrong since the last look. It reads what
 * the screens read: Fly.io's incidents (`fly incidents list`), and for each deployed app its
 * machines (`fly status`) and host issues (`fly incidents hosts list`). It compares them with
 * [FlyWatchState], which keeps only fingerprints, so no app or machine name is written to disk.
 *
 * The first look, with no state, only takes note of what's wrong already: those problems are on
 * screen when the user opens the app, and a burst of notifications for them would say nothing new.
 */
class FlyWatch(
    private val http: HttpClient,
    private val session: FlySession,
    private val apiUrl: String = FLY_API_URL,
    private val machinesUrl: String = FLY_MACHINES_URL,
    private val incidentsUrl: String = FLY_INCIDENTS_URL,
) {
    /**
     * What's new since [previous], and the state to keep for next time. An app or feed that can't
     * be reached keeps its old state, so a network failure never reads as a problem resolved. Throws
     * [FlyApiException.Unauthorized] when the session is over. [previous] from another account is
     * ignored, so that account's first look only takes note too.
     */
    suspend fun check(
        previous: FlyWatchState?,
        nowSeconds: Long,
    ): FlyWatchResult =
        coroutineScope {
            // Another account's state, after signing in as someone else, says nothing about this one's.
            val account = fingerprint(session.token.value?.identity ?: "")
            val previous = previous?.takeIf { it.account == account }
            val incidents = async { runCatchingFly { FlyIncidents(http, incidentsUrl).active() } }
            val apps =
                session
                    .authorized { FlyApi(http, it, apiUrl).overview() }
                    .apps
                    // Suspended and undeployed apps have no machines running to go wrong.
                    .filter { it.deployed && it.status != "suspended" }
            val gate = Semaphore(PARALLEL_APPS)
            val looks =
                apps
                    .map { app ->
                        async {
                            gate.withPermit {
                                val key = fingerprint(app.name)
                                val look = runCatchingFly { look(app.name) }
                                Triple(app.name, key, look)
                            }
                        }
                    }.awaitAll()

            val alerts = mutableListOf<FlyAlert>()
            val baseline = previous == null

            val open = incidents.await()
            val incidentNames =
                if (open == null) {
                    previous?.incidents.orEmpty()
                } else {
                    val before = previous?.incidents.orEmpty()
                    if (!baseline) {
                        open.filter { it.id !in before }.forEach { alerts += FlyAlert.IncidentOpened(it) }
                        before.filterKeys { id -> open.none { it.id == id } }.forEach { (id, name) ->
                            alerts += FlyAlert.IncidentResolved(id, name)
                        }
                    }
                    open.associate { it.id to it.name }
                }

            val marks = mutableMapOf<String, FlyWatchState.Marks>()
            for ((app, key, look) in looks) {
                val before = previous?.apps?.get(key)
                if (look == null) {
                    before?.let { marks[key] = it }
                    continue
                }
                // Crashes since this app's last look; a new app's since the last look at all.
                val sinceMillis = (before?.checkedAt ?: previous?.checkedAt ?: nowSeconds) * 1000
                val problems = problems(app, look, sinceMillis)
                val seen = problems.map { it.mark }.toSet()
                val alerted =
                    before
                        ?.alerted
                        .orEmpty()
                        .intersect(seen)
                        .toMutableSet()
                for (problem in problems) {
                    val due =
                        when {
                            baseline -> false

                            problem.mark in alerted -> false

                            // Checks fail for a moment while a machine starts or deploys, so one
                            // counts once it was failing at the last look too.
                            problem.alert is FlyAlert.ChecksFailing -> problem.mark in before?.seen.orEmpty()

                            else -> true
                        }
                    if (due || baseline) alerted += problem.mark
                    if (due) alerts += problem.alert
                }
                marks[key] = FlyWatchState.Marks(nowSeconds, seen, alerted)
            }
            FlyWatchResult(alerts, FlyWatchState(account, nowSeconds, incidentNames, marks))
        }

    private suspend fun look(app: String): Look =
        coroutineScope {
            val machines = async { session.authorized { FlyMachines(http, it, machinesUrl).machines(app) } }
            val hostIssues = async { session.authorized { FlyApi(http, it, apiUrl).hostIssues(app) } }
            Look(machines.await(), hostIssues.await())
        }

    private fun problems(
        app: String,
        look: Look,
        sinceMillis: Long,
    ): List<Problem> =
        buildList {
            for (issue in look.hostIssues) {
                add(Problem(fingerprint(app, "host", issue.internalId), FlyAlert.HostIssue(app, issue)))
            }
            for (machine in look.machines) {
                if (machine.state == "failed") {
                    add(Problem(fingerprint(app, "failed", machine.id), FlyAlert.MachineFailed(app, machine)))
                }
                val critical = machine.checks.filter { it.status == "critical" }
                if (machine.state == "started" && critical.isNotEmpty()) {
                    add(
                        Problem(
                            fingerprint(app, "checks", machine.id),
                            FlyAlert.ChecksFailing(app, machine, critical.map { it.name }),
                        ),
                    )
                }
                // The latest exit since the last look that nobody asked for: a crash, or the kernel
                // killing it for memory. An exit the platform or flyctl asked for, such as an
                // autostop, a deploy or `fly machine stop`, says requested_stop.
                machine.events
                    .filter { it.type == "exit" && it.timestamp > sinceMillis && !it.requestedStop }
                    .filter { it.oomKilled || (it.exitCode ?: 0) != 0 }
                    .maxByOrNull { it.timestamp }
                    ?.let { exit ->
                        add(
                            Problem(
                                fingerprint(app, "exit", machine.id, exit.timestamp.toString()),
                                FlyAlert.MachineCrashed(app, machine, exit.exitCode, exit.oomKilled),
                            ),
                        )
                    }
            }
        }

    private class Look(
        val machines: List<FlyMachine>,
        val hostIssues: List<FlyHostIssue>,
    )

    private class Problem(
        val mark: String,
        val alert: FlyAlert,
    )

    private companion object {
        /** Apps looked at once: each look is two calls, and Fly.io rate-limits the Machines API. */
        const val PARALLEL_APPS = 4
    }
}

/**
 * [block]'s result, or null when Fly.io can't be reached or answers with an error. A session
 * that's over still throws, since that ends the check.
 */
private suspend fun <T> runCatchingFly(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: FlyApiException.Unauthorized) {
        throw e
    } catch (e: Exception) {
        null
    }

/**
 * A short one-way fingerprint of [parts], so the state can say "this was already reported"
 * without holding app names or machine ids.
 */
internal fun fingerprint(vararg parts: String): String =
    SHA256()
        .digest(parts.joinToString("\n").encodeToByteArray())
        .take(8)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

class FlyWatchResult(
    /** Oldest kind first: incidents, then apps in the order Fly.io lists them. */
    val alerts: List<FlyAlert>,
    val state: FlyWatchState,
)

/** Something worth a notification. */
sealed class FlyAlert {
    /** The same for the same problem on every look, so a notification for it replaces the last. */
    abstract val key: String

    /** A Fly.io incident that opened since the last look. */
    class IncidentOpened(
        val incident: FlyIncident,
    ) : FlyAlert() {
        override val key get() = "incident:${incident.id}"
    }

    /** An incident from the last look that's no longer open. */
    class IncidentResolved(
        val id: String,
        val name: String,
    ) : FlyAlert() {
        override val key get() = "incident:$id"
    }

    /** A problem on a host under some of [app]'s machines. */
    class HostIssue(
        val app: String,
        val issue: FlyHostIssue,
    ) : FlyAlert() {
        override val key get() = "host:$app:${issue.internalId}"
    }

    /** A machine Fly.io couldn't create or start. */
    class MachineFailed(
        val app: String,
        val machine: FlyMachine,
    ) : FlyAlert() {
        override val key get() = "machine:$app:${machine.id}"
    }

    /** A started machine whose [checks] have been critical for two looks running. */
    class ChecksFailing(
        val app: String,
        val machine: FlyMachine,
        val checks: List<String>,
    ) : FlyAlert() {
        override val key get() = "checks:$app:${machine.id}"
    }

    /** A machine that exited on its own with [exitCode], or [oomKilled]. */
    class MachineCrashed(
        val app: String,
        val machine: FlyMachine,
        val exitCode: Int?,
        val oomKilled: Boolean,
    ) : FlyAlert() {
        override val key get() = "machine:$app:${machine.id}"
    }
}

/**
 * What the last look saw: the open incidents, which are Fly.io's public status, and for each app
 * only fingerprints of its problems, keyed by the app's fingerprint.
 */
@Serializable
class FlyWatchState(
    /** The signed-in account's fingerprint. */
    val account: String,
    val checkedAt: Long,
    /** Open incidents' names by id, to say which one resolved. */
    val incidents: Map<String, String> = emptyMap(),
    val apps: Map<String, Marks> = emptyMap(),
) {
    @Serializable
    class Marks(
        val checkedAt: Long,
        /** The problems seen at the last look. */
        val seen: Set<String> = emptySet(),
        /** The ones already notified, or already there at the first look. */
        val alerted: Set<String> = emptySet(),
    )

    fun encode(): String = FlyJson.encodeToString(serializer(), this)

    companion object {
        /** The state [encode] wrote, or null for anything else, which starts afresh. */
        fun decode(text: String): FlyWatchState? = runCatching { FlyJson.decodeFromString(serializer(), text) }.getOrNull()
    }
}
