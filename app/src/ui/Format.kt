package dev.ggoggam.hangar.app.ui

import androidx.compose.runtime.Composable
import dev.ggoggam.hangar.app.generated.resources.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant

/** [value] with at most [decimals] decimals and no trailing zeros, as `12`, `12.5` or `0.03`. */
fun decimal(
    value: Double,
    decimals: Int = 1,
): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = (abs(value) * scale).roundToLong()
    val whole = scaled / scale
    val fraction = (scaled % scale).toString().padStart(decimals, '0').trimEnd('0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}

/** A byte count in the largest unit that keeps it at least 1, as `512 MB` or `1.2 GB`. */
fun bytes(value: Double): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = value
    var unit = 0
    while (v >= 1024 && unit < units.lastIndex) {
        v /= 1024
        unit++
    }
    return "${decimal(v, if (v >= 100 || unit == 0) 0 else 1)} ${units[unit]}"
}

/** Milliseconds as `85 ms` or `1.2 s`. */
fun millis(value: Double): String = if (value < 1000) "${decimal(value, 0)} ms" else "${decimal(value / 1000, 1)} s"

/** An RFC 3339 time, or null when it doesn't parse. */
fun instantOf(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** How long ago [instant] was, as `5 min ago`. */
@Composable
fun ago(instant: Instant): String {
    val seconds = (Clock.System.now() - instant).inWholeSeconds
    return when {
        seconds < 60 -> stringResource(Res.string.time_just_now)
        seconds < 3_600 -> stringResource(Res.string.time_minutes_ago, (seconds / 60).toInt())
        seconds < 86_400 -> stringResource(Res.string.time_hours_ago, (seconds / 3_600).toInt())
        else -> stringResource(Res.string.time_days_ago, (seconds / 86_400).toInt())
    }
}

/** [iso] as how long ago it was, or empty when it doesn't parse. */
@Composable
fun ago(iso: String?): String = instantOf(iso)?.let { ago(it) }.orEmpty()

/** The local wall-clock time of [instant], as `14:03:27`. */
fun clockTime(instant: Instant): String {
    val t = instant.toLocalDateTime(TimeZone.currentSystemDefault())

    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(t.hour)}:${two(t.minute)}:${two(t.second)}"
}
