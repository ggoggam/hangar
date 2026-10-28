package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * What `fly machine start|stop|suspend|restart` and `fly apps restart` do, through the Machines API.
 * Each acts under the machines' leases, as flyctl takes them, so it can't cross a deploy or another
 * client acting on the same machines.
 *
 * Fly.io turning a call down here throws [FlyActionException.NotAllowed], not
 * [FlyApiException.Unauthorized]: a read-only token is turned down the same way, and that's no
 * reason to sign out. [FlySession.authorized] renews discharges before the call, so an expired
 * one is rare.
 */
class FlyMachineActions(
    private val http: HttpClient,
    private val token: FlyToken,
    private val machinesUrl: String = FLY_MACHINES_URL,
) {
    /** Starts a stopped or suspended machine, as `fly machine start` does. */
    suspend fun startMachine(
        app: String,
        id: String,
    ) = leased(app, listOf(id)) { nonces ->
        val response =
            FlyJson.decodeFromString<StartResponse>(
                send(HttpMethod.Post, machine(app, id, "/start"), nonces[id]).ifBlank { "{}" },
            )
        if (response.status == "error") throw FlyApiException.Failed(response.message)
    }

    /** Stops a machine with its own stop signal, as `fly machine stop` does, without waiting for it to stop. */
    suspend fun stopMachine(
        app: String,
        id: String,
    ) = leased(app, listOf(id)) { nonces ->
        send(HttpMethod.Post, machine(app, id, "/stop"), nonces[id], buildJsonObject { put("id", id) }.toString())
    }

    /** Suspends a machine, as `fly machine suspend` does, without waiting for it to be suspended. */
    suspend fun suspendMachine(
        app: String,
        id: String,
    ) = leased(app, listOf(id)) { nonces ->
        send(HttpMethod.Post, machine(app, id, "/suspend"), nonces[id])
    }

    /** Restarts a machine and waits for it to start and pass its checks, as `fly machine restart` does. */
    suspend fun restartMachine(
        app: String,
        id: String,
    ) = leased(app, listOf(id)) { nonces -> restart(app, id, nonces[id], checks = true) }

    /**
     * Restarts the app's running machines, as `fly apps restart` does: under all their leases, one
     * after another, each started and passing its checks before the next. Stopped ones stay stopped.
     * With [rolling] false they restart all at once, as the dashboard's immediate restart does, and
     * only their starting is waited for. [onProgress] hears how many of how many have restarted:
     * after each one when rolling, else once at the start and once at the end.
     * Postgres apps are turned down, as flyctl sends them to `fly pg restart`.
     */
    suspend fun restartApp(
        app: String,
        rolling: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val role = http.getJson<MachinesApp>("$machinesUrl/v1/apps/$app", token.machinesHeader).role
        if (role == POSTGRES_ROLE) throw FlyActionException.PostgresApp()
        val running = FlyMachines(http, token, machinesUrl).machines(app).filter { it.state == "started" }
        // flyctl leaves machines on hosts it can't reach out of the leasing, and so does this.
        leased(app, running.filter { it.hostStatus == "ok" }.map { it.id }) { nonces ->
            onProgress(0, running.size)
            if (rolling) {
                running.forEachIndexed { i, m ->
                    restart(app, m.id, nonces[m.id], checks = true)
                    onProgress(i + 1, running.size)
                }
            } else {
                coroutineScope { running.map { m -> async { restart(app, m.id, nonces[m.id], checks = false) } }.awaitAll() }
                onProgress(running.size, running.size)
            }
        }
    }

    /** fly-go's Restart, then flyctl's wait for the machine to start and, with [checks], for its checks to pass. */
    private suspend fun restart(
        app: String,
        id: String,
        nonce: String?,
        checks: Boolean,
    ) {
        send(HttpMethod.Post, machine(app, id, "/restart?force_stop=false"), nonce)
        waitStarted(app, id)
        if (checks) waitForChecks(app, id)
    }

    /**
     * Waits for the machine to start, as flyctl's WaitForStartOrStop does: the Machines API's wait,
     * asked again until five minutes are up. Each ask is short of the HTTP client's timeout.
     */
    private suspend fun waitStarted(
        app: String,
        id: String,
    ) {
        val deadline = TimeSource.Monotonic.markNow() + WAIT
        while (deadline.hasNotPassedNow()) {
            try {
                send(HttpMethod.Get, machine(app, id, "/wait?state=started&timeout=$WAIT_ASK_SECONDS"), null)
                return
            } catch (e: FlyApiException.Failed) {
                // A 408 when the ask runs out, or a blip on the way: ask again.
                delay(1_000)
            }
        }
        throw FlyActionException.TimedOut(id)
    }

    /** Waits for the machine's checks to pass, as flyctl's MachinesChecks does, for up to five minutes. */
    private suspend fun waitForChecks(
        app: String,
        id: String,
    ) {
        val deadline = TimeSource.Monotonic.markNow() + WAIT
        while (deadline.hasNotPassedNow()) {
            val m = http.getJson<FlyMachine>(machine(app, id, ""), token.machinesHeader)
            if (m.checks.all { it.status == "passing" }) return
            delay(2_000)
        }
        throw FlyActionException.TimedOut(id)
    }

    /**
     * [block] under a lease on each of [ids], as flyctl's AcquireLeases takes them, handing it each
     * machine's nonce. The leases are released afterwards, even when [block] fails or is cancelled.
     */
    private suspend fun <T> leased(
        app: String,
        ids: List<String>,
        block: suspend (Map<String, String>) -> T,
    ): T {
        val nonces = mutableMapOf<String, String>()
        try {
            for (id in ids) nonces[id] = acquire(app, id)
            return block(nonces)
        } finally {
            withContext(NonCancellable) {
                nonces.forEach { (id, nonce) ->
                    try {
                        send(HttpMethod.Delete, machine(app, id, "/lease"), nonce)
                    } catch (e: Exception) {
                        // It runs out on its own after LEASE_SECONDS.
                    }
                }
            }
        }
    }

    /**
     * A lease on the machine, as fly-go's AcquireLease takes one: tried again for a little while
     * when another holds it, as one does through a deploy.
     */
    private suspend fun acquire(
        app: String,
        id: String,
    ): String {
        repeat(LEASE_TRIES) { attempt ->
            try {
                val lease = FlyJson.decodeFromString<Lease>(send(HttpMethod.Post, machine(app, id, "/lease?ttl=$LEASE_SECONDS"), null))
                return lease.data?.nonce ?: throw FlyApiException.Failed(lease.message)
            } catch (e: FlyActionException.Busy) {
                if (attempt == LEASE_TRIES - 1) throw e
                delay(1_000L shl attempt)
            }
        }
        throw FlyActionException.Busy()
    }

    /** Sends a request with the lease's [nonce], if any, and hands back the body, mapping failures for an action. */
    private suspend fun send(
        method: HttpMethod,
        url: String,
        nonce: String?,
        body: String? = null,
    ): String {
        val response =
            http.request(url) {
                this.method = method
                header(HttpHeaders.Authorization, token.machinesHeader)
                if (nonce != null) header(NONCE_HEADER, nonce)
                if (body != null) setBody(TextContent(body, ContentType.Application.Json))
            }
        val text = response.bodyAsText()
        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden -> {
                throw FlyActionException.NotAllowed()
            }

            response.status == HttpStatusCode.Conflict -> {
                throw FlyActionException.Busy()
            }

            !response.status.isSuccess() -> {
                throw FlyApiException.Failed("HTTP ${response.status.value}: ${errorDetail(text)}")
            }
        }
        return text
    }

    private fun machine(
        app: String,
        id: String,
        path: String,
    ) = "$machinesUrl/v1/apps/$app/machines/$id$path"

    private companion object {
        /** fly-go's NonceHeader. */
        const val NONCE_HEADER = "fly-machine-lease-nonce"

        /** flyctl's lease length. */
        const val LEASE_SECONDS = 120
        const val LEASE_TRIES = 4

        /** flyctl's five minutes for a machine to start, and again for its checks, on the wall clock. */
        val WAIT = 5.minutes
        const val WAIT_ASK_SECONDS = 20

        /** flapsutil's PostgresAppRole. */
        const val POSTGRES_ROLE = "postgres_cluster"
    }
}

/** Why an action on a machine or an app didn't happen. */
sealed class FlyActionException(
    message: String,
) : Exception(message) {
    /** Fly.io turned it down: a read-only token, or a role in the organization that can't. */
    class NotAllowed : FlyActionException("not allowed")

    /** Another operation, such as a deploy, holds the machine's lease. */
    class Busy : FlyActionException("lease held elsewhere")

    /** A Postgres app, which flyctl restarts with `fly pg restart` instead. */
    class PostgresApp : FlyActionException("postgres app")

    /** The machine didn't start, or its checks didn't pass, within five minutes. */
    class TimedOut(
        val machine: String,
    ) : FlyActionException("timed out on $machine")
}

@Serializable
private class StartResponse(
    val status: String = "",
    val message: String = "",
)

@Serializable
private class Lease(
    val data: LeaseData? = null,
    val message: String = "",
)

@Serializable
private class LeaseData(
    val nonce: String = "",
)

/** The Machines API's view of an app, for its role. */
@Serializable
private class MachinesApp(
    @SerialName("app_role") val role: String = "",
)
