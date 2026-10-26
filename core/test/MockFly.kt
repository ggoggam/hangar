package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** An HTTP client that answers each request with the next of [responses] and records the requests. */
internal class MockFly(
    vararg responses: Pair<HttpStatusCode, String>,
) {
    val requests = mutableListOf<HttpRequestData>()
    private val queue = ArrayDeque(responses.toList())

    val http =
        HttpClient(
            MockEngine { request ->
                requests += request
                val (status, body) = queue.removeFirst()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
}

/** Two macaroons and an OAuth token, as `fly auth token` prints them. */
internal val MIXED_TOKEN = FlyToken("fm2_a,fm2_b,fo1_c")
