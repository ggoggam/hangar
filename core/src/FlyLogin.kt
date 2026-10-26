package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fly.io's browser login, the one `fly auth login` runs: start a CLI session with a PKCE
 * challenge, have the user approve it on the page at [PendingLogin.approvalUrl], then redeem the
 * one-time code that page shows together with the verifier.
 *
 * The endpoints are flyctl's (`fly-go/cli_session.go`), not a documented API.
 */
class FlyLogin(
    private val http: HttpClient,
    private val apiUrl: String = FLY_API_URL,
) {
    /** Starts a login named [sessionName], which the approval page shows. */
    suspend fun start(sessionName: String): PendingLogin {
        val pkce = Pkce.random()
        val body =
            buildJsonObject {
                put("name", sessionName)
                put("signup", false)
                put("target", "auth")
                put("code_challenge", pkce.challenge)
                put("code_challenge_method", "S256")
                put("state", randomToken(16))
            }
        val response = http.post("$apiUrl/api/v1/cli_sessions") { setBody(TextContent(body.toString(), ContentType.Application.Json)) }
        if (response.status != HttpStatusCode.Created) {
            throw FlyLoginException.Unavailable("starting a CLI session returned ${response.status.value}")
        }
        val session = FlyJson.decodeFromString<CliSession>(response.bodyAsText())
        // A server that predates PKCE hands the token to whoever polls with the session id.
        if (!session.pkce || session.authUrl == null) throw FlyLoginException.Unavailable("the server didn't accept PKCE")
        return PendingLogin(session.id, session.authUrl, pkce.verifier)
    }

    /** Redeems the [code] the approval page showed. Works once; the session is gone afterwards. */
    suspend fun redeem(
        login: PendingLogin,
        code: String,
    ): FlyToken {
        val body =
            buildJsonObject {
                put("code", code.trim())
                put("code_verifier", login.verifier)
            }
        val response =
            http.post("$apiUrl/api/v1/cli_sessions/${login.id}/redeem") {
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        val text = response.bodyAsText()
        when (response.status) {
            HttpStatusCode.OK -> {
                val token = FlyJson.decodeFromString<CliSession>(text).accessToken?.let(::FlyToken)
                if (token == null || token.isBlank) throw FlyLoginException.Rejected("no token in the redeem response")
                return token
            }

            HttpStatusCode.NotFound -> {
                throw FlyLoginException.Expired()
            }

            else -> {
                val detail = runCatching { FlyJson.decodeFromString<ApiError>(text).error }.getOrNull()
                throw FlyLoginException.Rejected(detail ?: "redeem returned ${response.status.value}")
            }
        }
    }
}

/** A login waiting for approval. Fly.io keeps it for 15 minutes. */
class PendingLogin internal constructor(
    val id: String,
    val approvalUrl: String,
    internal val verifier: String,
)

sealed class FlyLoginException(
    message: String,
) : Exception(message) {
    /** The session is gone: it expired, or its code was already redeemed. */
    class Expired : FlyLoginException("the login session expired")

    /** Fly.io refused the code. */
    class Rejected(
        detail: String,
    ) : FlyLoginException(detail)

    /** Fly.io didn't start a login this client can finish safely. */
    class Unavailable(
        detail: String,
    ) : FlyLoginException(detail)
}

@Serializable
private class CliSession(
    val id: String = "",
    @SerialName("auth_url") val authUrl: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    val pkce: Boolean = false,
)

@Serializable
private class ApiError(
    val error: String? = null,
)
