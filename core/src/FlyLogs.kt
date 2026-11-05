package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * An app's logs over HTTP, the way `fly logs` polls when it can't use NATS: each page hands back a
 * cursor for the next one. Pages hold at most 100 entries.
 */
class FlyLogs(
    private val http: HttpClient,
    private val token: FlyToken,
    private val apiUrl: String = FLY_API_URL,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
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

    /**
     * The lines just before [known], which runs oldest first, oldest first themselves; empty when
     * Fly.io keeps none older. flyctl never reads backwards and the API only pages forwards, but
     * `next_token` is documented as a time in nanoseconds to read from, so this picks a time before
     * the oldest line and reads forwards until it meets it, leaving no gap. How far back to start
     * comes from how often [known] logged, to get about a page. A stretch with nothing in it is
     * looked at again further back. One with too much to read is cut in half, keeping the half
     * nearest the oldest line, until what's left fits. With no [known] lines, as for an app that's
     * been quiet for a while, it looks back from now.
     */
    suspend fun older(
        app: String,
        known: List<FlyLogEntry>,
        instance: String? = null,
    ): List<FlyLogEntry> {
        val oldest = if (known.isEmpty()) nowSeconds() * NANOS_PER_SECOND else nanosOf(known.first().timestamp) ?: return emptyList()
        val knownIds = known.mapTo(HashSet()) { it.id }.apply { remove("") }
        val furthest = oldest - OLDER_RETENTION_NANOS
        var end = oldest
        var span = firstSpan(known, oldest)
        var start = maxOf(end - span, furthest)
        // Reading from here is sure to find a line before end, once a read has run into one.
        var floor: Long? = null
        while (true) {
            val read = readUpTo(app, start, end, oldest, knownIds, instance)
            if (read.reachedEnd) {
                if (read.lines.isNotEmpty()) return read.lines.sortedBy { nanosOf(it.timestamp) }
                end = start
                val lineBefore = floor
                if (lineBefore != null) {
                    start = midway(lineBefore, end)
                } else {
                    if (start <= furthest) return emptyList()
                    span *= OLDER_SPAN_GROWTH
                    start = maxOf(end - span, furthest)
                }
            } else {
                val lineBefore = read.lastAt - 1
                floor = lineBefore
                val closer = midway(lineBefore, end)
                // Too many lines in a moment to cut any finer, so these will have to do.
                if (closer <= start) return read.lines.sortedBy { nanosOf(it.timestamp) }
                start = closer
            }
        }
    }

    private fun midway(
        from: Long,
        to: Long,
    ) = from + (to - from) / 2

    /** How long the app takes to log a page's worth, going by [known]. */
    private fun firstSpan(
        known: List<FlyLogEntry>,
        oldest: Long,
    ): Long {
        if (known.isEmpty()) return OLDER_QUIET_SPAN_NANOS
        val newest = nanosOf(known.last().timestamp) ?: oldest
        if (known.size < 2 || newest <= oldest) return OLDER_MIN_SPAN_NANOS * 60
        return ((newest - oldest) / (known.size - 1) * OLDER_LINES_WANTED).coerceIn(OLDER_MIN_SPAN_NANOS, OLDER_MAX_SPAN_NANOS)
    }

    /**
     * Reads forwards from [start], keeping what's new, for at most [OLDER_MAX_PAGES], and stopping
     * sooner when full pages show the rest of the way to [end] would take more than that.
     */
    private suspend fun readUpTo(
        app: String,
        start: Long,
        end: Long,
        oldest: Long,
        knownIds: Set<String>,
        instance: String?,
    ): OlderRead {
        val seen = knownIds.toHashSet()
        val lines = mutableListOf<FlyLogEntry>()
        var cursor = start.toString()
        var firstAt: Long? = null
        var lastAt = start
        for (pagesRead in 1..OLDER_MAX_PAGES) {
            val page = page(app, cursor, instance)
            for (entry in page.entries) {
                val at = nanosOf(entry.timestamp) ?: continue
                if (at > end) return OlderRead(lines, lastAt, reachedEnd = true)
                if (firstAt == null) firstAt = at
                lastAt = at
                // A line at the very moment of the oldest can only be told from it by its ID.
                val isNew = if (entry.id.isEmpty()) at < oldest else seen.add(entry.id)
                if (isNew) lines += entry
            }
            if (page.entries.isEmpty() || page.cursor == cursor) return OlderRead(lines, lastAt, reachedEnd = true)
            cursor = page.cursor ?: return OlderRead(lines, lastAt, reachedEnd = true)
            val perPage = (lastAt - (firstAt ?: lastAt)) / pagesRead
            val outOfReach = lastAt + perPage * (OLDER_MAX_PAGES - pagesRead) < end
            if (page.entries.size >= FULL_PAGE && outOfReach) return OlderRead(lines, lastAt, reachedEnd = false)
        }
        return OlderRead(lines, lastAt, reachedEnd = lastAt >= end)
    }

    private class OlderRead(
        val lines: List<FlyLogEntry>,
        val lastAt: Long,
        val reachedEnd: Boolean,
    )

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val FULL_PAGE = 100
        const val OLDER_LINES_WANTED = 100
        const val OLDER_MIN_SPAN_NANOS = NANOS_PER_SECOND
        const val OLDER_MAX_SPAN_NANOS = 60 * 60 * NANOS_PER_SECOND
        const val OLDER_SPAN_GROWTH = 4

        /** No lines at all means none lately: the latest page only goes back about a day. */
        const val OLDER_QUIET_SPAN_NANOS = 24 * OLDER_MAX_SPAN_NANOS

        /** Fly.io keeps logs for about 7 days, so a day more is sure to have passed them. */
        const val OLDER_RETENTION_NANOS = 8 * 24 * 60 * 60 * NANOS_PER_SECOND
        const val OLDER_MAX_PAGES = 5

        fun nanosOf(timestamp: String): Long? =
            runCatching { Instant.parse(timestamp) }
                .getOrNull()
                ?.let { it.epochSeconds * NANOS_PER_SECOND + it.nanosecondsOfSecond }
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
