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
    fun readsActiveIncidentsWithoutAToken() =
        runTest {
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"incidents":[{"id":"i1","name":"Elevated API errors","status":"investigating",
                            "components":[{"id":"c1","name":"Machines API"}],"started_at":"2026-10-26T10:00:00Z",
                            "updated_at":"2026-10-26T10:30:00Z","created_at":"2026-10-26T10:00:00Z","resolved_at":null}]}""",
                )

            val incident = FlyIncidents(fly.http, "https://incidents.test").active().single()

            assertEquals("Elevated API errors", incident.name)
            assertEquals(listOf("Machines API"), incident.components.map { it.name })
            assertEquals("2026-10-26T10:30:00Z", incident.updatedAt)
            assertEquals(
                "/v1/incidents",
                fly.requests
                    .single()
                    .url.encodedPath,
            )
            assertNull(fly.requests.single().headers[HttpHeaders.Authorization])
        }

    @Test
    fun noIncidentsIsAnEmptyList() =
        runTest {
            assertEquals(emptyList(), FlyIncidents(MockFly(HttpStatusCode.OK to """{"incidents":[]}""").http, "https://i.test").active())
        }

    @Test
    fun aFailingFeedFails() =
        runTest {
            val fly = MockFly(HttpStatusCode.BadGateway to "bad gateway")
            assertFailsWith<FlyApiException.Failed> { FlyIncidents(fly.http, "https://i.test").active() }
        }
}
