package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An app's logs over HTTP, the way `fly logs` polls when it can't use NATS: each page hands back a
 * cursor for the next one. Pages hold at most 100 entries.
 */
class FlyLogs(
    private val http: HttpClient,
    private val token: FlyToken,
    private val apiUrl: String = FLY_API_URL,
) {
    /**
     * The entries after [cursor], or the latest ones when it's null, from one machine when
     * [instance] is set. An empty page has no cursor of its own, so it hands [cursor] back to poll
     * from again.
     */
    suspend fun page(
        app: String,
        cursor: String?,
        instance: String? = null,
    ): FlyLogPage {
        val body =
            http.getJson<LogsBody>("$apiUrl/api/v1/apps/$app/logs", token.machinesHeader) {
                parameter("next_token", cursor.orEmpty())
                instance?.let { parameter("instance", it) }
            }
        return FlyLogPage(
            entries = body.data.map { it.attributes.copyWithId(it.id) },
            cursor = body.meta?.nextToken?.ifEmpty { null } ?: cursor,
        )
    }
}

class FlyLogPage(
    val entries: List<FlyLogEntry>,
    val cursor: String?,
)

@Serializable
class FlyLogEntry(
    /** RFC 3339 with nanoseconds, in UTC. */
    val timestamp: String,
    val message: String,
    val level: String = "",
    /** The machine's ID. */
    val instance: String = "",
    val region: String = "",
    val id: String = "",
) {
    internal fun copyWithId(id: String) = FlyLogEntry(timestamp, message, level, instance, region, id)
}

@Serializable
private class LogsBody(
    val data: List<LogData> = emptyList(),
    val meta: LogsMeta? = null,
)

@Serializable
private class LogData(
    val id: String = "",
    val attributes: FlyLogEntry,
)

@Serializable
private class LogsMeta(
    @SerialName("next_token") val nextToken: String? = null,
)
