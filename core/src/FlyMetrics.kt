package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Fly.io's managed Prometheus for an organization, at api.fly.io/prometheus/<org>. */
class FlyMetrics(
    private val http: HttpClient,
    private val token: FlyToken,
    private val apiUrl: String = FLY_API_URL,
) {
    /**
     * An app's CPU, memory, traffic and latency over the [windowSeconds] before [endSeconds] (Unix
     * time), about 60 points each. Fly.io only records HTTP metrics while there's traffic, so those
     * series can be empty.
     */
    suspend fun dashboard(
        org: String,
        app: String,
        endSeconds: Long,
        windowSeconds: Long = 3_600,
    ): FlyAppMetrics =
        coroutineScope {
            val step = (windowSeconds / 60).coerceAtLeast(15)
            val a = "app=\"${app.replace("\\", "\\\\").replace("\"", "\\\"")}\""

            fun range(query: String) = async { line(org, query, endSeconds - windowSeconds, endSeconds, step) }
            // flyctl has no metrics command; these follow the queries behind Fly.io's Grafana boards.
            val cpu = range("sum(rate(fly_instance_cpu{$a,mode!=\"idle\"}[5m])) / sum(rate(fly_instance_cpu{$a}[5m])) * 100")
            val memory = range("sum(fly_instance_memory_mem_total{$a} - fly_instance_memory_mem_available{$a})")
            val memoryTotal = range("sum(fly_instance_memory_mem_total{$a})")
            val requests = range("sum(rate(fly_edge_http_responses_count{$a}[5m])) * 60")
            val errors = range("sum(rate(fly_edge_http_responses_count{$a,status=~\"5.*\"}[5m])) * 60")
            val latency = range("histogram_quantile(0.95, sum by (le) (rate(fly_app_http_response_time_seconds_bucket{$a}[5m]))) * 1000")
            FlyAppMetrics(
                cpuPercent = cpu.await(),
                memoryBytes = memory.await(),
                memoryTotalBytes = memoryTotal.await(),
                requestsPerMinute = requests.await(),
                errorsPerMinute = errors.await(),
                p95Millis = latency.await(),
            )
        }

    /** A query that sums to one series, such as `sum(...)`, as its points; empty when nothing matched. */
    suspend fun line(
        org: String,
        query: String,
        startSeconds: Long,
        endSeconds: Long,
        stepSeconds: Long,
    ): List<FlyPoint> = range(org, query, startSeconds, endSeconds, stepSeconds).firstOrNull()?.points.orEmpty()

    /** A PromQL instant query at [timeSeconds]: one value per label set, skipping ones that aren't numbers. */
    suspend fun instant(
        org: String,
        query: String,
        timeSeconds: Long,
    ): List<FlySample> {
        val body =
            http.getJson<PromResponse>("$apiUrl/prometheus/$org/api/v1/query", token.machinesHeader) {
                parameter("query", query)
                parameter("time", timeSeconds)
            }
        if (body.status != "success") throw FlyApiException.Failed(body.error ?: body.status)
        return body.data?.result.orEmpty().mapNotNull { series ->
            val value =
                series.value
                    ?.getOrNull(1)
                    ?.jsonPrimitive
                    ?.content
                    ?.toDoubleOrNull()
            if (value == null || value.isNaN() || value.isInfinite()) null else FlySample(series.metric, value)
        }
    }

    /** A PromQL range query: one series per label set. */
    suspend fun range(
        org: String,
        query: String,
        startSeconds: Long,
        endSeconds: Long,
        stepSeconds: Long,
    ): List<FlySeries> {
        val body =
            http.getJson<PromResponse>("$apiUrl/prometheus/$org/api/v1/query_range", token.machinesHeader) {
                parameter("query", query)
                parameter("start", startSeconds)
                parameter("end", endSeconds)
                parameter("step", stepSeconds)
            }
        if (body.status != "success") throw FlyApiException.Failed(body.error ?: body.status)
        return body.data?.result.orEmpty().map { series ->
            FlySeries(
                labels = series.metric,
                points =
                    series.values.mapNotNull { pair ->
                        val time = pair.getOrNull(0)?.jsonPrimitive ?: return@mapNotNull null
                        val value =
                            pair
                                .getOrNull(1)
                                ?.jsonPrimitive
                                ?.content
                                ?.toDoubleOrNull()
                        val seconds = time.longOrNull ?: time.content.toDoubleOrNull()?.toLong()
                        if (seconds == null || value == null || value.isNaN() || value.isInfinite()) null else FlyPoint(seconds, value)
                    },
            )
        }
    }
}

/**
 * The app's board on Fly.io's managed Grafana, the page `fly dashboard metrics --grafana` opens in
 * the browser; null without the organization's number.
 */
fun grafanaUrl(app: FlyAppDetail): String? =
    app.organization.internalNumericId?.let { "https://fly-metrics.net/d/fly-app/fly-app?orgId=$it&var-app=${app.name}" }

class FlyAppMetrics(
    val cpuPercent: List<FlyPoint>,
    val memoryBytes: List<FlyPoint>,
    val memoryTotalBytes: List<FlyPoint>,
    val requestsPerMinute: List<FlyPoint>,
    val errorsPerMinute: List<FlyPoint>,
    val p95Millis: List<FlyPoint>,
)

class FlySeries(
    val labels: Map<String, String>,
    val points: List<FlyPoint>,
)

/** One label set's value from an instant query. */
class FlySample(
    val labels: Map<String, String>,
    val value: Double,
)

/** One sample: Unix seconds and its value. */
data class FlyPoint(
    val time: Long,
    val value: Double,
)

@Serializable
private class PromResponse(
    val status: String,
    val data: PromData? = null,
    val error: String? = null,
)

@Serializable
private class PromData(
    val result: List<PromSeries> = emptyList(),
)

@Serializable
private class PromSeries(
    val metric: Map<String, String> = emptyMap(),
    val values: List<JsonArray> = emptyList(),
    /** An instant query's `[time, "value"]`. */
    val value: JsonArray? = null,
)
