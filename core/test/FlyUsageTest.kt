package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
            val queries = mutableListOf<String>()
            val http =
                HttpClient(
                    MockEngine { request ->
                        val url = request.url
                        val body =
                            when {
                                url.encodedPath.endsWith("/api/v1/query") -> {
                                    val q = url.parameters["query"].orEmpty()
                                    queries += q
                                    if ("fly_instance_info" in q) {
                                        // 30 days' minutes of a shared-cpu-1x 512MB in iad, and 60 minutes of a performance-1x 2GB in nrt.
                                        """{"status":"success","data":{"resultType":"vector","result":[
                                            {"metric":{"app":"web","region":"iad","cpu_kind":"shared","cpu_count":"1","memory_mb":"512"},"value":[$now,"43200"]},
                                            {"metric":{"app":"job","region":"nrt","cpu_kind":"performance","cpu_count":"1","memory_mb":"2048"},"value":[$now,"60"]}]}}"""
                                    } else {
                                        """{"status":"success","data":{"resultType":"vector","result":[
                                            {"metric":{"app":"web","region":"iad"},"value":[$now,"2000000000"]},
                                            {"metric":{"app":"web","region":"sin"},"value":[$now,"1000000000"]},
                                            {"metric":{"app":"web","region":"ams"},"value":[$now,"NaN"]}]}}"""
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
            assertNear(3.69, web.computeUsd)
            assertEquals(3e9, web.dataOutBytes)
            assertNear(2 * 0.02 + 0.04, web.egressUsd, within = 1e-9)
            assertEquals(10, web.volumeGb)
            val week = (now - 1_799_395_200L).toDouble()
            assertNear(10 * 0.15 * week / FlyPrices.SECONDS_PER_30_DAYS, web.volumeUsd, within = 1e-9)
            assertNear(3_600 * 1.2732e-5 * 1.307692308, usage.app("job")!!.computeUsd, within = 1e-6)
            assertEquals(43_260 * 60.0, usage.machineSeconds)
            assertTrue("[1209600s:1m]" in queries.first { "fly_instance_info" in it })
        }
}
