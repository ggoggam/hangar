package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val FLY_API_URL = "https://api.fly.io"

/** The Machines API (flyctl's "flaps"), which also serves volumes, IPs and certificates. */
const val FLY_MACHINES_URL = "https://api.machines.dev"

/** Fly.io's incident feed behind `fly incidents list`, an app of Fly.io's own (fly-incidents.fly.dev). */
const val FLY_INCIDENTS_URL = "https://incidents.flyio.net"

/**
 * The HTTP client every Fly.io call goes through, on the platform's engine (OkHttp, Darwin), with
 * no response cache: logs, machines and the rest stay in memory, never on disk.
 */
fun flyHttpClient(): HttpClient =
    platformHttpClient {
        install(UserAgent) { agent = "hangar/0.1 (unofficial Fly.io client)" }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }

/** An HTTP client on this platform's engine, configured not to cache responses. */
internal expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

internal val FlyJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/**
 * GETs [url] with [authorization], or with no token for null, and decodes the JSON body, mapping
 * failures to [FlyApiException].
 */
internal suspend inline fun <reified T> HttpClient.getJson(
    url: String,
    authorization: String?,
    crossinline params: HttpRequestBuilder.() -> Unit = {},
): T =
    decodeJson(
        get(url) {
            if (authorization != null) header(HttpHeaders.Authorization, authorization)
            params()
        },
    )

/** As [getJson], but null for a 404: for endpoints Fly.io is still rolling out, which flyctl skips when they're missing. */
internal suspend inline fun <reified T> HttpClient.getJsonOrNull(
    url: String,
    authorization: String?,
): T? {
    val response = get(url) { if (authorization != null) header(HttpHeaders.Authorization, authorization) }
    return if (response.status == HttpStatusCode.NotFound) null else decodeJson(response)
}

/** [response]'s JSON body, or the [FlyApiException] for its status. */
internal suspend inline fun <reified T> decodeJson(response: HttpResponse): T {
    val text = response.bodyAsText()
    when {
        response.status == HttpStatusCode.Unauthorized -> throw FlyApiException.Unauthorized()
        !response.status.isSuccess() -> throw FlyApiException.Failed("HTTP ${response.status.value}: ${errorDetail(text)}")
    }
    return FlyJson.decodeFromString(text)
}

/** The `error` field of a Fly.io error body, or the body itself, cut short. */
internal fun errorDetail(body: String): String =
    runCatching {
        FlyJson
            .parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonPrimitive
            ?.content
    }.getOrNull()
        ?: body.take(200)
