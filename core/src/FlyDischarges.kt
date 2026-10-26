package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.takeFrom
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * Renews the short-lived discharges a user token needs, with superfly/macaroon's third-party
 * protocol: post each caveat's ticket to its third party, authenticated with the token's OAuth
 * part, and add the discharge that comes back. This is what flyctl does before most commands.
 */
class FlyDischarges(
    private val http: HttpClient,
    /** Third parties allowed to see the OAuth token; tickets name their own location, so this guards it. */
    private val trustedHosts: Set<String> = setOf("api.fly.io", "auth.fly.io"),
) {
    /**
     * [token] with a discharge for every third-party caveat that needs one, or [token] itself when
     * none do. Throws [FlyApiException.Unauthorized] when Fly.io won't hand one out without the user
     * signing in again.
     */
    suspend fun refresh(
        token: FlyToken,
        nowSeconds: Long,
    ): FlyToken {
        val needed = token.undischarged(nowSeconds)
        if (needed.isEmpty()) return token
        val oauth = token.oauth ?: throw FlyApiException.Unauthorized()
        return token.withDischarges(needed.map { discharge(it, oauth) }, nowSeconds)
    }

    private suspend fun discharge(
        caveat: ThirdPartyCaveat,
        oauth: String,
    ): String {
        val base = Url(caveat.location)
        if (base.protocol != URLProtocol.HTTPS || base.host !in trustedHosts) throw FlyApiException.Unauthorized()
        val body = buildJsonObject { put("ticket", Base64.Default.encode(caveat.ticket)) }
        val response =
            http.post(caveat.location.trimEnd('/') + "/.well-known/macfly/3p") {
                header(HttpHeaders.Authorization, "Bearer $oauth")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        val reply = read(response)
        reply.discharge?.let { return it }
        // Fly.io answers at once when the OAuth token is good; a page for the user means signing in again.
        val poll = reply.pollUrl ?: throw FlyApiException.Unauthorized()
        repeat(POLLS) {
            delay(1_000)
            val polled =
                http.get(URLBuilder().takeFrom(base).takeFrom(poll).build()) {
                    header(HttpHeaders.Authorization, "Bearer $oauth")
                }
            if (polled.status != HttpStatusCode.Accepted) {
                return read(polled).discharge ?: throw FlyApiException.Unauthorized()
            }
        }
        throw FlyApiException.Failed("discharge timed out")
    }

    private suspend fun read(response: HttpResponse): DischargeReply {
        val text = response.bodyAsText()
        val reply = runCatching { FlyJson.decodeFromString<DischargeReply>(text) }.getOrNull()
        return when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden -> {
                throw FlyApiException
                    .Unauthorized()
            }

            reply == null -> {
                throw FlyApiException.Failed("HTTP ${response.status.value}: ${errorDetail(text)}")
            }

            reply.error != null && response.status.value in 400..499 -> {
                throw FlyApiException.Unauthorized()
            }

            reply.error != null -> {
                throw FlyApiException.Failed(reply.error)
            }

            reply.userInteractive != null && reply.discharge == null -> {
                throw FlyApiException.Unauthorized()
            }

            else -> {
                reply
            }
        }
    }

    private companion object {
        const val POLLS = 10
    }
}

@Serializable
private class DischargeReply(
    val discharge: String? = null,
    @SerialName("poll_url") val pollUrl: String? = null,
    @SerialName("user_interactive") val userInteractive: JsonObject? = null,
    val error: String? = null,
)
