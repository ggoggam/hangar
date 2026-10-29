package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlyUsageTest {
    private fun assertNear(
        expected: Double,
        actual: Double,
        within: Double = 0.005,
    ) = assertTrue(abs(expected - actual) <= within, "expected $expected, was $actual")

    @Test
    fun pricesMatchFlyIoTable() {
        val month = FlyPrices.SECONDS_PER_30_DAYS.toDouble()
        // The figures docs.fly.io/about/pricing shows for iad.
        assertNear(2.19, FlyPrices.machinePerSecond("shared", 1, 256, "iad") * month)
        assertNear(3.69, FlyPrices.machinePerSecond("shared", 1, 512, "iad") * month)
        assertNear(6.70, FlyPrices.machinePerSecond("shared", 1, 1024, "iad") * month)
        assertNear(13.39, FlyPrices.machinePerSecond("shared", 2, 2048, "iad") * month)
        assertNear(33.00, FlyPrices.machinePerSecond("performance", 1, 2048, "iad") * month)
        assertNear(66.00, FlyPrices.machinePerSecond("performance", 2, 4096, "iad") * month)
        // Tokyo's markup, and a region the table doesn't know priced as iad.
        assertNear(3.69 * 1.307692308, FlyPrices.machinePerSecond("shared", 1, 512, "nrt") * month, within = 0.01)
        assertNear(2.19, FlyPrices.machinePerSecond("shared", 1, 256, "xyz") * month)
        assertEquals(0.04, FlyPrices.egressPerGb("nrt"))
    }

    @Test
    fun estimatesFromRunningTimeDataOutAndVolumes() =
        runTest {
            val now = 1_800_000_000L
            // FlyUsage sends its queries at once and the mock engine answers them on several threads,
            // so the list is only touched under the lock, or an add can be lost.
            val queries = mutableListOf<String>()
            val queriesLock = Mutex()
            val http =
                HttpClient(
                    MockEngine { request ->
                        val url = request.url
                        val body =
                            when {
                                url.encodedPath.endsWith("/api/v1/query") -> {
                                    val q = url.parameters["query"].orEmpty()
                                    queriesLock.withLock { queries += q }
                                    val seconds = Regex("""\[(\d+)s""").find(q)!!.groupValues[1].toLong()
                                    val latest = url.parameters["time"] == "$now"
                                    if ("fly_instance_info" in q) {
                                        // A shared-cpu-1x 512MB in iad all along, and a second one for the last ten
                                        // minutes. Lately, a CI Machine in nrt that ran for four minutes' worth of
                                        // samples, as a performance-1x 2GB and then resized to a shared-cpu-2x 512MB.
                                        // And a builder, which Fly.io doesn't charge for.
                                        val result =
                                            listOfNotNull(
                                                """{"metric":{"app":"web","region":"iad","instance":"w1","cpu_kind":"shared","cpu_count":"1","memory_mb":"512"},"value":[$now,"${seconds / 60}"]}""",
                                                """{"metric":{"app":"web","region":"iad","instance":"w2","cpu_kind":"shared","cpu_count":"1","memory_mb":"512"},"value":[$now,"10"]}"""
                                                    .takeIf { latest },
                                                """{"metric":{"app":"job","region":"nrt","instance":"j1","cpu_kind":"performance","cpu_count":"1","memory_mb":"2048"},"value":[$now,"2"]}"""
                                                    .takeIf { latest },
                                                """{"metric":{"app":"job","region":"nrt","instance":"j1","cpu_kind":"shared","cpu_count":"2","memory_mb":"512"},"value":[$now,"2"]}"""
                                                    .takeIf { latest },
                                                """{"metric":{"app":"fly-builder-quiet-sun-1234","region":"nrt","instance":"b1","cpu_kind":"shared","cpu_count":"8","memory_mb":"16384"},"value":[$now,"100"]}""",
                                            )
                                        """{"status":"success","data":{"resultType":"vector","result":[${result.joinToString()}]}}"""
                                    } else if ("fly_instance_cpu" in q) {
                                        // w1 ran the whole time, w2 reported no CPU time, and j1 really ran for 90
                                        // vCPU-seconds: 30 seconds at each size.
                                        val result =
                                            listOfNotNull(
                                                """{"metric":{"app":"web","instance":"w1"},"value":[$now,"$seconds"]}""",
                                                """{"metric":{"app":"job","instance":"j1"},"value":[$now,"90"]}""".takeIf { latest },
                                                """{"metric":{"app":"fly-builder-quiet-sun-1234","instance":"b1"},"value":[$now,"48000"]}""",
                                            )
                                        """{"status":"success","data":{"resultType":"vector","result":[${result.joinToString()}]}}"""
                                    } else {
                                        """{"status":"success","data":{"resultType":"vector","result":[
                                            {"metric":{"app":"web","region":"iad"},"value":[$now,"2000000000"]},
                                            {"metric":{"app":"web","region":"sin"},"value":[$now,"1000000000"]},
                                            {"metric":{"app":"web","region":"ams"},"value":[$now,"NaN"]},
                                            {"metric":{"app":"fly-builder-quiet-sun-1234","region":"nrt"},"value":[$now,"5000000000"]}]}}"""
                                    }
                                }

                                url.encodedPath == "/v1/apps/web/volumes" -> {
                                    // Made a week before now, so a week of its 10 GB counts.
                                    """[{"id":"vol_1","state":"created","size_gb":10,"created_at":"2027-01-08T08:00:00Z"}]"""
                                }

                                else -> {
                                    "[]"
                                }
                            }
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                )

            val usage = FlyUsage(http, MIXED_TOKEN, "https://fly.test", "https://m.test").estimate("personal", listOf("web", "job"), now)

            assertEquals(listOf("web", "job"), usage.apps.map { it.app })
            val web = usage.app("web")!!
            // Fourteen days of w1, and w2's ten minutes as they stand.
            assertNear((14 * 86_400 + 600) * FlyPrices.machinePerSecond("shared", 1, 512, "iad"), web.computeUsd, within = 1e-9)
            assertEquals(3e9, web.dataOutBytes)
            assertNear(2 * 0.02 + 0.04, web.egressUsd, within = 1e-9)
            assertEquals(10, web.volumeGb)
            val week = (now - 1_799_395_200L).toDouble()
            assertNear(10 * 0.15 * week / FlyPrices.SECONDS_PER_30_DAYS, web.volumeUsd, within = 1e-9)
            assertNear(
                30 * (FlyPrices.machinePerSecond("performance", 1, 2048, "nrt") + FlyPrices.machinePerSecond("shared", 2, 512, "nrt")),
                usage.app("job")!!.computeUsd,
                within = 1e-9,
            )
            assertNear(14 * 86_400 + 600 + 60.0, usage.machineSeconds, within = 1e-6)
            // Asked about two days at a time, which Fly.io answers from every sample.
            assertEquals(
                List(7) { "[172800s:1m]" },
                queries.filter { "fly_instance_info" in it }.map { Regex("""\[\d+s:1m]""").find(it)!!.value },
            )
        }
}
