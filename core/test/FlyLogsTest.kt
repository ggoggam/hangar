package dev.ggoggam.hangar.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

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

    private val second = 1_000_000_000L

    /** 2026-10-26T08:00:00Z, in nanoseconds. */
    private val base = 1_793_001_600 * second

    private fun iso(seconds: Int) = Instant.fromEpochSeconds(base / second + seconds).toString()

    private fun line(
        seconds: Int,
        id: String = "e$seconds",
    ) = FlyLogEntry(iso(seconds), "m", id = id)

    private fun json(
        seconds: List<Int>,
        next: Int?,
    ) = HttpStatusCode.OK to
        seconds.joinToString(
            prefix = """{"data":[""",
            postfix = """],"meta":{"next_token":"${next?.let { base + it * second } ?: ""}"}}""",
        ) { """{"id":"e$it","attributes":{"timestamp":"${iso(it)}","message":"m"}}""" }

    private fun nextToken(fly: MockFly) = fly.requests.map { it.url.parameters["next_token"]!!.toLong() }

    @Test
    fun readsForwardsFromAboutAPageBeforeTheOldestUntilItMeetsIt() =
        runTest {
            val fly = MockFly(json(listOf(610, 620), 620), json(listOf(630, 660), 660), json(listOf(661), 661))
            val logs = FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test")

            val older = logs.older("web", listOf(line(660), line(661)), instance = "m1")

            assertEquals(listOf("e610", "e620", "e630"), older.map { it.id })
            // A line a second, so 100 seconds should hold a page.
            assertEquals(listOf(base + 560 * second, base + 620 * second, base + 660 * second), nextToken(fly))
            assertEquals("m1", fly.requests[0].url.parameters["instance"])
        }

    @Test
    fun startsAMinuteBackWithOneLineToGoBy() =
        runTest {
            val fly = MockFly(json(listOf(3000), 3000), json(listOf(2950), 2950), json(listOf(3000), 3000), json(emptyList(), null))
            FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test").older("web", listOf(line(3000)))
            assertEquals(base + 2940 * second, nextToken(fly)[0])
        }

    @Test
    fun looksNoMoreThanAnHourBackAtFirst() =
        runTest {
            val fly = MockFly(json(listOf(50000), 50000), json(emptyList(), null), json(listOf(40000), 40000), json(listOf(50000), 50000))
            FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test").older("web", listOf(line(50000), line(90000)))
            assertEquals(base + (50000 - 3600) * second, nextToken(fly)[0])
        }

    @Test
    fun looksFurtherBackPastAQuietStretch() =
        runTest {
            val fly =
                MockFly(
                    // Only the oldest itself in the first minute back, and nothing after it yet.
                    json(listOf(3000), 3000),
                    json(emptyList(), null),
                    // Nothing in the four minutes before that, so past the end straight away.
                    json(listOf(3000), 3000),
                    json(listOf(2500), 2500),
                    json(listOf(3000), 3000),
                )
            val logs = FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test")

            val older = logs.older("web", listOf(line(3000)))

            assertEquals(listOf("e2500"), older.map { it.id })
            assertEquals(
                listOf(
                    base + 2940 * second,
                    base + 3000 * second,
                    base + 2700 * second,
                    base + 1740 * second,
                    base + 2500 * second,
                ),
                nextToken(fly),
            )
        }

    @Test
    fun stopsOnceBehindWhatFlyIoKeeps() =
        runTest {
            val pages = List(7) { json(listOf(3000), 3000) }.toTypedArray()
            val fly = MockFly(json(listOf(3000), 3000), json(emptyList(), null), *pages)

            assertEquals(emptyList(), FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test").older("web", listOf(line(3000))))
            assertEquals(9, fly.requests.size)
        }

    /** Enough to look an hour back: a line every 36 seconds is 100 lines an hour. */
    private val hourApart = listOf(line(10_000), line(10_036))

    private fun fullPage(from: Int) = json(List(100) { from + it }, from + 99)

    @Test
    fun cutsAStretchInHalfWhenFullPagesWontReachTheEnd() =
        runTest {
            // A line a second from 6400, which at five pages a read would never reach 10000.
            val fly = MockFly(fullPage(6400), json(listOf(9000), 9000), json(emptyList(), null))

            val older = FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test").older("web", hourApart)

            assertEquals(listOf("e9000"), older.map { it.id })
            val beforeLine = base + 6499 * second - 1
            assertEquals(
                listOf(base + 6400 * second, beforeLine + (base + 10_000 * second - beforeLine) / 2),
                nextToken(fly).take(2),
            )
        }

    @Test
    fun staysBetweenABusyStretchAndTheOldestPastSomethingEmpty() =
        runTest {
            // Nothing in the half nearest the oldest line, so the search goes on in the half before
            // it, rather than back past the lines it already ran into.
            val fly =
                MockFly(
                    fullPage(6400),
                    json(emptyList(), null),
                    json(listOf(7000), 7000),
                    json(emptyList(), null),
                )

            val older = FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test").older("web", hourApart)

            assertEquals(listOf("e7000"), older.map { it.id })
            val (_, half, quarter) = nextToken(fly)
            val beforeLine = base + 6499 * second - 1
            assertEquals(beforeLine + (half - beforeLine) / 2, quarter)
        }

    @Test
    fun keepsReadingFullPagesThatWillReachTheEnd() =
        runTest {
            // Three pages of a line a second, ending just short of the oldest: no need to cut it.
            val fly = MockFly(fullPage(9700), fullPage(9800), fullPage(9900), json(emptyList(), null))

            val older = FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test").older("web", hourApart)

            assertEquals(300, older.size)
            assertEquals(4, fly.requests.size)
        }

    @Test
    fun keepsALineAtTheSameMomentAsTheOldestByItsId() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"data":[{"id":"twin","attributes":{"timestamp":"${iso(60)}","message":"m"}},
                           {"id":"e60","attributes":{"timestamp":"${iso(
                            60,
                        )}","message":"m"}}],"meta":{"next_token":"${base + 60 * second}"}}""",
                    json(emptyList(), null),
                )

            val older = FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test").older("web", listOf(line(60)))

            assertEquals(listOf("twin"), older.map { it.id })
        }

    @Test
    fun readsBackFromNowWithNoLineToGoBy() =
        runTest {
            // Nothing in the last day, then a line in the four days before that.
            val fly = MockFly(json(emptyList(), null), json(listOf(60), 60), json(emptyList(), null))
            val now = base / second + 3 * DAY
            val logs = FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test") { now }

            val older = logs.older("web", emptyList(), instance = "m1")

            assertEquals(listOf("e60"), older.map { it.id })
            assertEquals(listOf(now - DAY, now - 5 * DAY).map { it * second }, nextToken(fly).take(2))
            assertEquals("m1", fly.requests[0].url.parameters["instance"])
        }

    @Test
    fun givesUpOnAQuietAppAfterLookingBackOverAllFlyIoKeeps() =
        runTest {
            val fly = MockFly(json(emptyList(), null), json(emptyList(), null), json(emptyList(), null))
            val logs = FlyLogs(fly.http, MIXED_TOKEN, "https://fly.test") { base / second }

            assertEquals(emptyList(), logs.older("web", emptyList()))
            assertEquals(3, fly.requests.size)
        }

    private companion object {
        const val DAY = 24 * 60 * 60L
    }
}
