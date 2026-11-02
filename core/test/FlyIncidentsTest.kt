package dev.ggoggam.hangar.core

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FlyIncidentsTest {
    @Test
    fun readsTheStatusPageWithoutAToken() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"page":{"id":"p1","name":"Fly.io"},"status":{"indicator":"minor"},
                            "components":[{"id":"c1","name":"Machines API","status":"degraded_performance"}],
                            "incidents":[{"id":"i1","name":"Elevated Machine start failures in ARN","status":"investigating",
                            "created_at":"2026-11-02T12:34:10Z","updated_at":"2026-11-02T12:40:00Z","impact":"minor",
                            "incident_updates":[]}]}""",
                )

            val incident = FlyIncidents(fly.http, "https://status.test", "https://incidents.test").active().single()

            assertEquals("Elevated Machine start failures in ARN", incident.name)
            assertEquals("investigating", incident.status)
            assertEquals("2026-11-02T12:34:10Z", incident.startedAt)
            assertEquals("2026-11-02T12:40:00Z", incident.updatedAt)
            assertEquals(
                "/api/v2/summary.json",
                fly.requests
                    .single()
                    .url.encodedPath,
            )
            assertNull(fly.requests.single().headers[HttpHeaders.Authorization])
        }

    @Test
    fun leavesOutResolvedIncidents() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"incidents":[{"id":"i1","name":"Fixed","status":"resolved"},{"id":"i2","name":"Open","status":"monitoring"}]}""",
                )

            assertEquals(listOf("Open"), FlyIncidents(fly.http, "https://s.test", "https://i.test").active().map { it.name })
        }

    @Test
    fun fallsBackToFlyctlsFeed() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.NotFound to "<!DOCTYPE html>",
                    HttpStatusCode.OK to
                        """{"incidents":[{"id":"i1","name":"Elevated API errors","status":"investigating",
                            "components":[{"id":"c1","name":"Machines API"}],"started_at":"2026-10-26T10:00:00Z",
                            "updated_at":"2026-10-26T10:30:00Z","created_at":"2026-10-26T09:55:00Z","resolved_at":null}]}""",
                )

            val incident = FlyIncidents(fly.http, "https://status.test", "https://incidents.test").active().single()

            assertEquals("Elevated API errors", incident.name)
            assertEquals(listOf("Machines API"), incident.components.map { it.name })
            assertEquals("2026-10-26T10:00:00Z", incident.startedAt)
            assertEquals(
                "/v1/incidents",
                fly.requests
                    .last()
                    .url.encodedPath,
            )
            assertNull(fly.requests.last().headers[HttpHeaders.Authorization])
        }

    @Test
    fun noIncidentsIsAnEmptyList() =
        runTest {
            val fly = MockFly(HttpStatusCode.OK to """{"incidents":[]}""")
            assertEquals(emptyList(), FlyIncidents(fly.http, "https://s.test", "https://i.test").active())
        }

    @Test
    fun failsWhenNeitherCanBeRead() =
        runTest {
            val fly = MockFly(HttpStatusCode.BadGateway to "bad gateway", HttpStatusCode.BadGateway to "bad gateway")
            assertFailsWith<FlyApiException.Failed> { FlyIncidents(fly.http, "https://s.test", "https://i.test").active() }
        }
}
