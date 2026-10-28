package dev.ggoggam.hangar.core

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FlySecretsTest {
    @Test
    fun listsNamesAndDigestsSortedWithoutAskingForValues() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"secrets":[
                            {"name":"SENTRY_DSN","digest":"b1","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-02T00:00:00Z"},
                            {"name":"DATABASE_URL","digest":"a1","value":"postgres://never"}]}""",
                )

            val secrets = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test").secrets("web")

            val request = fly.requests.single()
            assertEquals("https://m.test/v1/apps/web/secrets", request.url.toString())
            assertFalse("show_secrets" in request.url.parameters)
            assertEquals("FlyV1 fm2_a,fm2_b", request.headers[HttpHeaders.Authorization])
            assertEquals(listOf("DATABASE_URL", "SENTRY_DSN"), secrets.map { it.name })
            assertEquals("2026-10-02T00:00:00Z", secrets[1].updatedAt)
        }

    private fun machine(
        id: String,
        version: Int,
    ) = FlyJson.decodeFromString(
        FlyMachine.serializer(),
        """{"id":"$id","state":"started","config":{"metadata":{"fly_release_version":"$version"}}}""",
    )

    private fun release(
        version: Int,
        createdAt: String,
    ) = FlyRelease(createdAt = createdAt, status = "complete", version = version)

    private fun secret(updatedAt: String?) = FlySecret("API_KEY", updatedAt = updatedAt)

    private val releases =
        listOf(release(2, "2026-10-10T00:00:00Z"), release(3, "2026-10-20T00:00:00Z"))

    @Test
    fun aSecretIsDeployedWhereItsMachinesReleaseCameAfterIt() {
        val machines = listOf(machine("m1", 3), machine("m2", 3))
        assertEquals(FlySecretStatus.Deployed, secretStatus(secret("2026-10-15T00:00:00Z"), machines, releases))
        // Set at the very moment of the release counts, as flyctl's "not after" does.
        assertEquals(FlySecretStatus.Deployed, secretStatus(secret("2026-10-20T00:00:00Z"), machines, releases))
    }

    @Test
    fun aSecretSetSinceEveryMachinesReleaseIsStaged() {
        val machines = listOf(machine("m1", 3))
        assertEquals(FlySecretStatus.Staged, secretStatus(secret("2026-10-21T00:00:00Z"), machines, releases))
        // An app with no machines has nowhere to have deployed it.
        assertEquals(FlySecretStatus.Staged, secretStatus(secret("2026-10-01T00:00:00Z"), emptyList(), releases))
    }

    @Test
    fun aSecretOnSomeMachinesIsPartial() {
        val machines = listOf(machine("m1", 3), machine("m2", 2))
        assertEquals(FlySecretStatus.Partial, secretStatus(secret("2026-10-15T00:00:00Z"), machines, releases))
        // A machine on a release that isn't in the list can't be said to have it.
        val unknownRelease = listOf(machine("m1", 3), machine("m2", 1))
        assertEquals(FlySecretStatus.Partial, secretStatus(secret("2026-10-01T00:00:00Z"), unknownRelease, releases))
    }

    @Test
    fun noTimeOrTooManyMachinesSaySoRatherThanGuess() {
        assertEquals(FlySecretStatus.Unknown, secretStatus(secret(null), listOf(machine("m1", 3)), releases))
        val many = (1..101).map { machine("m$it", 3) }
        assertNull(secretStatus(secret("2026-10-15T00:00:00Z"), many, releases))
    }

    @Test
    fun theDemoHasDeployedSecretsAndOneStaged() =
        runTest {
            val http = flyDemoHttpClient()
            val token = DEMO_TOKEN
            val api = FlyApi(http, token)
            val machines = FlyMachines(http, token)
            val statuses =
                machines.secrets("web").associate { it.name to secretStatus(it, machines.machines("web"), api.releases("web")) }

            assertEquals(FlySecretStatus.Staged, statuses["STRIPE_WEBHOOK_SECRET"])
            assertEquals(setOf(FlySecretStatus.Deployed), (statuses - "STRIPE_WEBHOOK_SECRET").values.toSet())
        }
}
