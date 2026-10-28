package dev.ggoggam.hangar.core

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlyReleasesTest {
    private fun release(
        version: Int,
        image: String,
        status: String = "complete",
    ) = FlyRelease(createdAt = "2026-10-0${version}T00:00:00Z", status = status, version = version, imageRef = image)

    @Test
    fun readsWhoDeployedARelease() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"data":{"app":{"releases":{"nodes":[
                            {"id":"r7","version":7,"description":"Deploy image","reason":"deploy","status":"complete",
                             "imageRef":"registry.fly.io/web:deployment-07","stable":true,
                             "user":{"email":"a@b.c","name":"Ada"},"createdAt":"2026-10-25T13:42:10Z"}]}}}}""",
                )

            val release = FlyApi(fly.http, MIXED_TOKEN, "https://api.test").releases("web").single()

            assertTrue("user { email name }" in (fly.requests.single().body as TextContent).text)
            assertEquals("Ada", release.user?.name)
            assertEquals("registry.fly.io/web:deployment-07", release.imageRef)
        }

    @Test
    fun comparesWithTheReleaseBeforeThatWentOut() {
        val releases =
            listOf(
                release(5, "web:a"),
                release(4, "web:a"),
                release(3, "web:b", status = "failed"),
                release(2, "web:c"),
                release(1, "web:c"),
            )

        // A new image, compared with v2, since the failed v3 never went out.
        val v4 = assertIs<FlyReleaseChange.NewImage>(releaseChange(releases[1], releases))
        assertEquals(2, v4.previous.version)
        // The same image again: a config or secrets change.
        assertEquals(4, assertIs<FlyReleaseChange.SameImage>(releaseChange(releases[0], releases)).previous.version)
        assertIs<FlyReleaseChange.SameImage>(releaseChange(releases[3], releases))
        // Nothing before the oldest that was loaded.
        assertNull(releaseChange(releases[4], releases))
        assertNull(releaseChange(release(6, ""), releases))
    }

    @Test
    fun theDemoHasADeployAndASecretsChange() =
        runTest {
            val releases = FlyApi(flyDemoHttpClient { 1_793_000_000L }, DEMO_TOKEN).releases("api")

            assertIs<FlyReleaseChange.NewImage>(releaseChange(releases[0], releases))
            assertIs<FlyReleaseChange.SameImage>(releaseChange(releases[1], releases))
            assertTrue(releases.all { it.user?.name?.isNotEmpty() == true })
        }
}
