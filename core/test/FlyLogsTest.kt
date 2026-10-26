package dev.ggoggam.hangar.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FlyLogsTest {
    private val page =
        """
        {"data":[{"id":"e1","type":"logs","attributes":{"timestamp":"2026-10-26T08:27:26.566836403Z","message":"hello",
          "level":"info","instance":"m1","region":"nrt","meta":{"http":null}}}],
         "meta":{"next_token":"t2"}}
        """

    @Test
    fun readsAPageAndPassesTheCursorAndMachine() =
        runTest {
            val fly = MockFly(HttpStatusCode.OK to page, HttpStatusCode.OK to """{"data":[],"meta":{"next_token":""}}""")
            val logs = FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test")

            val first = logs.page("web", cursor = null)
            assertEquals("hello", first.entries.single().message)
            assertEquals("e1", first.entries.single().id)
            assertEquals("t2", first.cursor)
            assertEquals("https://fly.test/api/v1/apps/web/logs?next_token=", fly.requests[0].url.toString())

            // An empty page has no cursor of its own: keep polling from the last one.
            val second = logs.page("web", cursor = "t2", instance = "m1")
            assertEquals(emptyList(), second.entries)
            assertEquals("t2", second.cursor)
            assertEquals("https://fly.test/api/v1/apps/web/logs?next_token=t2&instance=m1", fly.requests[1].url.toString())
        }
}
