package dev.ggoggam.hangar.core

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlyMetricsTest {
    @Test
    fun readsARangeAndSkipsNaN() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"status":"success","data":{"resultType":"matrix","result":[
                            {"metric":{"instance":"m1"},"values":[[1700000000,"1.5"],[1700000060,"NaN"],[1700000120.5,"2"]]}]}}""",
                )

            val series = FlyMetrics(fly.http, MIXED_TOKEN, "https://fly.test").range("personal", "up", 10, 20, 5).single()

            assertEquals(mapOf("instance" to "m1"), series.labels)
            assertEquals(listOf(FlyPoint(1700000000, 1.5), FlyPoint(1700000120, 2.0)), series.points)
            val url = fly.requests.single().url
            assertEquals("/prometheus/personal/api/v1/query_range", url.encodedPath)
            assertEquals("up", url.parameters["query"])
            assertEquals("FlyV1 fm2_a,fm2_b", fly.requests.single().headers[HttpHeaders.Authorization])
        }

    @Test
    fun aFailedQueryCarriesPrometheusError() =
        runTest {
            val fly = MockFly(HttpStatusCode.OK to """{"status":"error","errorType":"bad_data","error":"parse error"}""")
            val e =
                assertFailsWith<FlyApiException.Failed> { FlyMetrics(fly.http, MIXED_TOKEN, "https://fly.test").range("o", "(", 0, 1, 1) }
            assertEquals("parse error", e.message)
        }

    @Test
    fun theDashboardScopesEveryQueryToTheApp() =
        runTest {
            val empty = HttpStatusCode.OK to """{"status":"success","data":{"result":[]}}"""
            val fly = MockFly(*Array(6) { empty })

            val metrics = FlyMetrics(fly.http, MIXED_TOKEN, "https://fly.test").dashboard("personal", "web", endSeconds = 7_200)

            assertEquals(emptyList(), metrics.requestsPerMinute)
            assertEquals(6, fly.requests.size)
            fly.requests.forEach { r ->
                assertEquals(true, r.url.parameters["query"]?.contains("app=\"web\""))
                assertEquals("3600", r.url.parameters["start"])
                assertEquals("60", r.url.parameters["step"])
            }
        }
}
