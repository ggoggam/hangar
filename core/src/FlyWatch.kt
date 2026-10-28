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
import kotlin.time.Instant

/**
 * The background check behind notifications: what has gone wrong since the last look. It reads what
 * the screens read: Fly.io's incidents (`fly incidents list`), each app's latest release from the app
 * list, and for each deployed app its machines (`fly status`) and host issues (`fly incidents hosts
 * list`); if asked, each app's volumes (`fly volumes list`), and each organization's billing status
 * and projected spend. It compares them with [FlyWatchState], which keeps only fingerprints, so no
 * app or machine name is written to disk.
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
     * ignored, so that account's first look only takes note too. [options] adds volumes and
     * billing, which cost calls for each app or organization, so they're only looked at when asked for.
     */
    suspend fun check(
        previous: FlyWatchState?,
        nowSeconds: Long,
        options: FlyWatchOptions = FlyWatchOptions(),
    ): FlyWatchResult =
        coroutineScope {
            // Another account's state, after signing in as someone else, says nothing about this one's.
            val account = fingerprint(session.token.value?.identity ?: "")
            val previous = previous?.takeIf { it.account == account }
            val incidents = async { runCatchingFly { FlyIncidents(http, incidentsUrl).active() } }
            val billing = async { if (options.billing) runCatchingFly { billingStatuses() } else null }
            val allApps = session.authorized { FlyApi(http, it, apiUrl).overview() }.apps
            val spendDue =
                options.monthlyBudgetUsd != null &&
                    previous?.spendCheckedAt?.let { nowSeconds - it >= SPEND_EVERY_SECONDS } != false
            val spend = async { if (spendDue) runCatchingFly { projectedSpend(allApps, nowSeconds) } else null }
            // Suspended and undeployed apps have no machines running to go wrong.
            val apps = allApps.filter { it.deployed && it.status != "suspended" }
            val gate = Semaphore(PARALLEL_APPS)
            val looks =
                apps
                    .map { app ->
                        async {
                            gate.withPermit {
                                app.name to runCatchingFly { look(app.name, options.volumes) }
                            }
                        }
                    }.awaitAll()
                    .toMap()

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

            // Every app, for its deploys; an app that's running, for its machines too.
            val appMarks = mutableMapOf<String, FlyWatchState.Marks>()
            for (app in allApps) {
                val key = fingerprint(app.name)
                val before = previous?.apps?.get(key)
                val look = looks[app.name]
                if (app.name in looks && look == null) {
                    before?.let { appMarks[key] = it }
                    continue
                }
                // Crashes and deploys since this app's last look; a new app's since the last look at all.
                val sinceMillis = (before?.checkedAt ?: previous?.checkedAt ?: nowSeconds) * 1000
                val problems = look?.let { problems(app, it, sinceMillis) }.orEmpty() + deploy(app, before, sinceMillis)
                appMarks[key] = track(before, problems, baseline, nowSeconds, alerts)
            }

            // Volumes, kept from the last look while they aren't asked for, or for an app whose can't be read.
            val volumeMarks = previous?.volumes.orEmpty().toMutableMap()
            if (options.volumes) {
                volumeMarks.clear()
                for (app in apps) {
                    val key = fingerprint(app.name)
                    val before = previous?.volumes?.get(key)
                    val volumes = looks[app.name]?.volumes
                    if (volumes == null) {
                        before?.let { volumeMarks[key] = it }
                        continue
                    }
                    val problems = volumes.mapNotNull { nearlyFull(app, it, before) }
                    volumeMarks[key] = track(before, problems, baseline, nowSeconds, alerts)
                }
            }

            // Billing, kept from the last look while it isn't asked for or can't be reached.
            val orgMarks = previous?.orgs.orEmpty().toMutableMap()
            billing.await()?.let { statuses ->
                orgMarks.clear()
                for ((org, read) in statuses) {
                    val key = fingerprint(org.slug)
                    val before = previous?.orgs?.get(key)
                    val status =
                        read.getOrElse {
                            before?.let { orgMarks[key] = it }
                            continue
                        }
                    val problems =
                        listOfNotNull(
                            status?.takeIf { it in BILLING_PROBLEMS }?.let {
                                Problem(fingerprint(org.slug, "billing", it), FlyAlert.BillingProblem(org.slug, org.name ?: org.slug, it))
                            },
                        )
                    orgMarks[key] = track(before, problems, baseline, nowSeconds, alerts)
                }
            }
            val spendMarks = previous?.spend.orEmpty().toMutableMap()
            val spent = spend.await()
            if (spent != null) {
                val budget = options.monthlyBudgetUsd!!
                spendMarks.clear()
                for ((org, projected) in spent) {
                    val key = fingerprint(org)
                    val problems =
                        listOfNotNull(
                            // The budget is in the mark, so a new budget that's passed already is news.
                            Problem(fingerprint(org, "spend", budget.toString()), FlyAlert.OverBudget(org, projected, budget))
                                .takeIf { projected > budget },
                        )
                    spendMarks[key] = track(previous?.spend?.get(key), problems, baseline, nowSeconds, alerts)
                }
            }

            FlyWatchResult(
                alerts,
                FlyWatchState(
                    account,
                    nowSeconds,
                    incidentNames,
                    appMarks,
                    orgMarks,
                    spendMarks,
                    spendCheckedAt = if (spent != null) nowSeconds else previous?.spendCheckedAt,
                    volumes = volumeMarks,
                ),
            )
        }

    /**
     * The marks for one app or organization with [problems] seen now, adding to [alerts] the ones
     * that are news: not notified already, and for failing checks, failing at the last look too.
     * At the [baseline] look nothing is news, and everything is taken as notified.
     */
    private fun track(
        before: FlyWatchState.Marks?,
        problems: List<Problem>,
        baseline: Boolean,
        nowSeconds: Long,
        alerts: MutableList<FlyAlert>,
    ): FlyWatchState.Marks {
        val seen = problems.map { it.mark }.toSet()
        val alerted =
            before
                ?.alerted
                .orEmpty()
                .intersect(seen)
                .toMutableSet()
        for (problem in problems) {
            if (problem.alert == null) continue
            val due =
                when {
                    baseline -> false

                    problem.mark in alerted -> false

                    // Checks fail for a moment while a machine starts or deploys, so one counts
                    // once it was failing at the last look too.
                    problem.alert is FlyAlert.ChecksFailing -> problem.mark in before?.seen.orEmpty()

                    else -> true
                }
            if (due || baseline) alerted += problem.mark
            if (due) alerts += problem.alert
        }
        return FlyWatchState.Marks(nowSeconds, seen, alerted)
    }

    /** Each organization's billing status, as `fly launch` checks it, or a failure where it can't be read. */
    private suspend fun billingStatuses(): List<Pair<FlyOrganization, Result<String?>>> =
        coroutineScope {
            val orgs = session.authorized { FlyApi(http, it, apiUrl).organizations() }
            orgs
                .map { org ->
                    async {
                        val status =
                            runCatchingFly { Result.success(session.authorized { FlyApi(http, it, apiUrl).billingStatus(org.slug) }) }
                        org to (status ?: Result.failure(FlyApiException.Failed("billing status")))
                    }
                }.awaitAll()
        }

    /**
     * Each organization's spend this month, in dollars, at the rate [FlyUsage] estimates for the
     * last [SPEND_WINDOW_SECONDS]: the shortest look back that Fly.io's Prometheus answers in one
     * piece, so it fits in the time iOS gives a background refresh.
     */
    private suspend fun projectedSpend(
        apps: List<FlyApp>,
        nowSeconds: Long,
    ): List<Pair<String, Double>> =
        coroutineScope {
            apps
                .groupBy { it.organization.slug }
                .map { (org, inOrg) ->
                    async {
                        val usage =
                            session.authorized {
                                FlyUsage(http, it, apiUrl, machinesUrl).estimate(
                                    org,
                                    inOrg.map { app ->
                                        app.name
                                    },
                                    nowSeconds,
                                    SPEND_WINDOW_SECONDS,
                                )
                            }
                        org to usage.estimatedUsd * FlyPrices.SECONDS_PER_30_DAYS / SPEND_WINDOW_SECONDS
                    }
                }.awaitAll()
        }

    /** [app]'s machines and host issues, and if asked for its [volumes] (`fly volumes list`). */
    private suspend fun look(
        app: String,
        volumes: Boolean,
    ): Look =
        coroutineScope {
            val machines = async { session.authorized { FlyMachines(http, it, machinesUrl).machines(app) } }
            val hostIssues = async { session.authorized { FlyApi(http, it, apiUrl).hostIssues(app) } }
            // Volumes that can't be read leave the machines' look be, and keep what was known of them.
            val volumeList =
                async {
                    if (volumes) runCatchingFly { session.authorized { FlyMachines(http, it, machinesUrl).volumes(app) } } else null
                }
            Look(machines.await(), hostIssues.await(), volumeList.await())
        }

    /**
     * [volume] as a problem if it's nearly full. Once it is, it stays so until it's back under
     * [VOLUME_CLEAR], so one hovering at the line isn't notified again on every look; and while
     * Fly.io doesn't say how full it is, it stays as it was.
     */
    private fun nearlyFull(
        app: FlyApp,
        volume: FlyVolume,
        before: FlyWatchState.Marks?,
    ): Problem? {
        val mark = fingerprint(app.name, "volume", volume.id)
        val was = mark in before?.seen.orEmpty()
        val used = volume.usedFraction
        val full = if (used == null) was else volume.nearlyFull || (was && used >= VOLUME_CLEAR)
        return Problem(mark, FlyAlert.VolumeNearlyFull(app.name, app.organization.slug, volume)).takeIf { full }
    }

    private fun problems(
        flyApp: FlyApp,
        look: Look,
        sinceMillis: Long,
    ): List<Problem> =
        buildList {
            val app = flyApp.name
            val org = flyApp.organization.slug
            for (issue in look.hostIssues) {
                add(Problem(fingerprint(app, "host", issue.internalId), FlyAlert.HostIssue(app, org, issue)))
            }
            for (machine in look.machines) {
                if (machine.state == "failed") {
                    add(Problem(fingerprint(app, "failed", machine.id), FlyAlert.MachineFailed(app, org, machine)))
                }
                val critical = machine.checks.filter { it.status == "critical" }
                if (machine.state == "started" && critical.isNotEmpty()) {
                    add(
                        Problem(
                            fingerprint(app, "checks", machine.id),
                            FlyAlert.ChecksFailing(app, org, machine, critical.map { it.name }),
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
                                FlyAlert.MachineCrashed(app, org, machine, exit.exitCode, exit.oomKilled),
                            ),
                        )
                    }
            }
        }

    /**
     * [app]'s latest release as a problem: one still deploying is only taken note of, and one that
     * ended is news if it ended since [sinceMillis] or was still deploying at the last look, which
     * catches a long deploy and a phone clock that runs ahead of Fly.io's.
     */
    private fun deploy(
        app: FlyApp,
        before: FlyWatchState.Marks?,
        sinceMillis: Long,
    ): List<Problem> {
        val release = app.latestRelease ?: return emptyList()
        val deploying = fingerprint(app.name, "deploying", release.version.toString())
        if (release.status in DEPLOYING) return listOf(Problem(deploying, null))
        if (release.status !in DEPLOY_ENDED) return emptyList()
        val endedMillis = runCatching { Instant.parse(release.updatedAt).toEpochMilliseconds() }.getOrNull() ?: 0
        if (endedMillis <= sinceMillis && deploying !in before?.seen.orEmpty()) return emptyList()
        return listOf(
            Problem(
                fingerprint(app.name, "deploy", release.version.toString(), release.status),
                FlyAlert.Deploy(app.name, app.organization.slug, release),
            ),
        )
    }

    private class Look(
        val machines: List<FlyMachine>,
        val hostIssues: List<FlyHostIssue>,
        /** Null when not asked for or not read. */
        val volumes: List<FlyVolume>?,
    )

    /** Something seen, under its [mark]; with no [alert], only noted for the next look. */
    private class Problem(
        val mark: String,
        val alert: FlyAlert?,
    )

    private companion object {
        /** Apps looked at once: each look is two calls, three with volumes, and Fly.io rate-limits the Machines API. */
        const val PARALLEL_APPS = 4

        /** Where a nearly full volume counts as cleared, a little under [FlyVolume.NEARLY_FULL]. */
        const val VOLUME_CLEAR = 0.85

        /** Spend moves slowly and costs a few Prometheus queries an organization, so four times a day. */
        const val SPEND_EVERY_SECONDS = 6 * 3_600L

        const val SPEND_WINDOW_SECONDS = FlyUsage.PIECE_SECONDS

        /**
         * The statuses worth a notification: a payment that failed or is overdue, or an
         * organization that needs a payment method to keep running Machines, as `fly launch` puts it.
         */
        val BILLING_PROBLEMS = setOf("PAST_DUE", "DELINQUENT", "SUSPENDED", "SOURCE_REQUIRED", "TRIAL_ENDED")

        /** Release statuses while flyctl is deploying it. */
        val DEPLOYING = setOf("pending", "running")

        /** Release statuses once a deploy is over, for better or worse. */
        val DEPLOY_ENDED = setOf("complete", "failed", "interrupted")
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

/** What to look at besides incidents and apps. */
class FlyWatchOptions(
    /** Each organization's billing status. */
    val billing: Boolean = false,
    /** Notify when an organization's projected spend this month passes this; null for never. */
    val monthlyBudgetUsd: Double? = null,
    /** Running apps' volumes, for the ones nearly full: a call for each app. */
    val volumes: Boolean = false,
)

class FlyWatchResult(
    /** Incidents, then apps in the order Fly.io lists them, then their volumes, then billing. */
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

    /** Something about one app, in the organization [org]. */
    sealed interface OfApp {
        val app: String
        val org: String
    }

    /** A problem on a host under some of [app]'s machines. */
    class HostIssue(
        override val app: String,
        override val org: String,
        val issue: FlyHostIssue,
    ) : FlyAlert(),
        OfApp {
        override val key get() = "host:$app:${issue.internalId}"
    }

    /** A machine Fly.io couldn't create or start. */
    class MachineFailed(
        override val app: String,
        override val org: String,
        val machine: FlyMachine,
    ) : FlyAlert(),
        OfApp {
        override val key get() = "machine:$app:${machine.id}"
    }

    /** A started machine whose [checks] have been critical for two looks running. */
    class ChecksFailing(
        override val app: String,
        override val org: String,
        val machine: FlyMachine,
        val checks: List<String>,
    ) : FlyAlert(),
        OfApp {
        override val key get() = "checks:$app:${machine.id}"
    }

    /** A machine that exited on its own with [exitCode], or [oomKilled]. */
    class MachineCrashed(
        override val app: String,
        override val org: String,
        val machine: FlyMachine,
        val exitCode: Int?,
        val oomKilled: Boolean,
    ) : FlyAlert(),
        OfApp {
        override val key get() = "machine:$app:${machine.id}"
    }

    /** A deploy of [app] that ended since the last look, as [release]'s status says: `complete`, `failed` or `interrupted`. */
    class Deploy(
        override val app: String,
        override val org: String,
        val release: FlyRelease,
    ) : FlyAlert(),
        OfApp {
        override val key get() = "deploy:$app"
    }

    /** A [volume] of [app]'s that's [FlyVolume.nearlyFull], notified once each time it gets there. */
    class VolumeNearlyFull(
        override val app: String,
        override val org: String,
        val volume: FlyVolume,
    ) : FlyAlert(),
        OfApp {
        override val key get() = "volume:$app:${volume.id}"
    }

    /** An organization whose billing [status] is one of the problems, such as `PAST_DUE`. */
    class BillingProblem(
        val slug: String,
        val name: String,
        val status: String,
    ) : FlyAlert() {
        override val key get() = "billing:$slug"
    }

    /** An organization on pace to spend [projectedUsd] this month, over the user's [budgetUsd]. */
    class OverBudget(
        val org: String,
        val projectedUsd: Double,
        val budgetUsd: Double,
    ) : FlyAlert() {
        override val key get() = "spend:$org"
    }
}

/**
 * What the last look saw: the open incidents, which are Fly.io's public status, and for each app
 * and organization only fingerprints of its problems, keyed by its own fingerprint.
 */
@Serializable
class FlyWatchState(
    /** The signed-in account's fingerprint. */
    val account: String,
    val checkedAt: Long,
    /** Open incidents' names by id, to say which one resolved. */
    val incidents: Map<String, String> = emptyMap(),
    val apps: Map<String, Marks> = emptyMap(),
    /** Organizations' billing status problems. */
    val orgs: Map<String, Marks> = emptyMap(),
    /** Organizations over the spend alert. */
    val spend: Map<String, Marks> = emptyMap(),
    /** When spend was last estimated, which is less often than every look. */
    val spendCheckedAt: Long? = null,
    /** Apps' volumes nearly full. */
    val volumes: Map<String, Marks> = emptyMap(),
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
