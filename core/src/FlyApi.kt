package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/** Fly.io's GraphQL API at api.fly.io/graphql, as [token]. */
class FlyApi(
    private val http: HttpClient,
    private val token: FlyToken,
    private val apiUrl: String = FLY_API_URL,
) {
    /** The signed-in user and every app in their organizations, following pages to the end. */
    suspend fun overview(): FlyOverview {
        var email = ""
        val apps = mutableListOf<FlyApp>()
        var after: String? = null
        do {
            val data = query<OverviewData>(OVERVIEW_QUERY, buildJsonObject { put("after", after) })
            email = data.viewer.email
            apps += data.apps.nodes
            after =
                data.apps.pageInfo.endCursor
                    .takeIf { data.apps.pageInfo.hasNextPage }
        } while (after != null)
        return FlyOverview(email, apps.sortedWith(compareBy({ it.organization.slug }, { it.name })))
    }

    /** One app as `fly status` shows it, with its current release. */
    suspend fun app(name: String): FlyAppDetail = query<AppData>(APP_QUERY, buildJsonObject { put("appName", name) }).app

    /** The app's latest [limit] releases, newest first, as `fly releases` lists them. */
    suspend fun releases(
        name: String,
        limit: Int = 25,
    ): List<FlyRelease> =
        query<ReleasesData>(
            RELEASES_QUERY,
            buildJsonObject {
                put("appName", name)
                put("limit", limit)
            },
        ).app.releases.nodes

    private suspend inline fun <reified T> query(
        query: String,
        variables: JsonObject,
    ): T {
        val body =
            buildJsonObject {
                put("query", query)
                put("variables", variables)
            }
        val response =
            http.post("$apiUrl/graphql") {
                header(HttpHeaders.Authorization, token.graphqlHeader)
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        if (response.status == HttpStatusCode.Unauthorized) throw FlyApiException.Unauthorized()
        val result = FlyJson.decodeFromString<GraphQlResponse>(response.bodyAsText())
        result.errors?.firstOrNull()?.let { error ->
            if ((error.extensions?.get("code") as? JsonPrimitive)?.content == "UNAUTHORIZED") throw FlyApiException.Unauthorized()
            throw FlyApiException.Failed(error.message)
        }
        val data = result.data ?: throw FlyApiException.Failed("HTTP ${response.status.value} with no data")
        return FlyJson.decodeFromJsonElement(data)
    }

    private companion object {
        // fly-go's getAppsPage, trimmed to what the app shows.
        const val OVERVIEW_QUERY = """
            query(${'$'}after: String) {
              viewer { email }
              apps(type: "container", first: 200, after: ${'$'}after) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  name
                  deployed
                  status
                  organization { slug }
                  currentRelease { createdAt status }
                }
              }
            }
        """

        const val RELEASE_FIELDS = "id version description reason status imageRef stable user { email } createdAt"

        // fly-go's GetAppCompact, the app half of `fly status`.
        const val APP_QUERY = """
            query(${'$'}appName: String!) {
              app(name: ${'$'}appName) {
                name
                hostname
                deployed
                status
                platformVersion
                organization { slug }
                currentRelease: currentReleaseUnprocessed { $RELEASE_FIELDS }
              }
            }
        """

        // fly-go's GetAppReleasesMachines, behind `fly releases`.
        const val RELEASES_QUERY = """
            query(${'$'}appName: String!, ${'$'}limit: Int!) {
              app(name: ${'$'}appName) {
                releases: releasesUnprocessed(first: ${'$'}limit) { nodes { $RELEASE_FIELDS } }
              }
            }
        """
    }
}

class FlyOverview(
    val email: String,
    val apps: List<FlyApp>,
)

@Serializable
class FlyApp(
    val name: String,
    /** `deployed`, `pending` or `suspended`, as Fly.io reports it. */
    val status: String,
    val deployed: Boolean = false,
    val organization: FlyOrganization,
    val currentRelease: FlyRelease? = null,
)

@Serializable
class FlyOrganization(
    val slug: String,
)

/** One app's page: what `fly status` shows above its machines. */
@Serializable
class FlyAppDetail(
    val name: String,
    val hostname: String? = null,
    val deployed: Boolean = false,
    val status: String,
    /** `machines` for apps on Fly Machines, `nomad` or `detached` for older ones. */
    val platformVersion: String? = null,
    val organization: FlyOrganization,
    val currentRelease: FlyRelease? = null,
)

@Serializable
class FlyRelease(
    val createdAt: String,
    /** `complete`, `failed`, `running`, `interrupted` and so on. */
    val status: String,
    val id: String = "",
    val version: Int = 0,
    val description: String = "",
    val reason: String = "",
    val imageRef: String = "",
    val stable: Boolean = false,
    val user: FlyUser? = null,
)

@Serializable
class FlyUser(
    val email: String = "",
)

sealed class FlyApiException(
    message: String,
) : Exception(message) {
    /** The token was revoked or has expired. */
    class Unauthorized : FlyApiException("unauthorized")

    class Failed(
        detail: String,
    ) : FlyApiException(detail)
}

@Serializable
private class GraphQlResponse(
    val data: JsonElement? = null,
    val errors: List<GraphQlError>? = null,
)

@Serializable
private class GraphQlError(
    val message: String = "",
    val extensions: JsonObject? = null,
)

@Serializable
private class OverviewData(
    val viewer: Viewer,
    val apps: AppPage,
)

@Serializable
private class AppData(
    val app: FlyAppDetail,
)

@Serializable
private class ReleasesData(
    val app: ReleasesApp,
)

@Serializable
private class ReleasesApp(
    val releases: ReleaseNodes,
)

@Serializable
private class ReleaseNodes(
    val nodes: List<FlyRelease>,
)

@Serializable
private class Viewer(
    val email: String,
)

@Serializable
private class AppPage(
    val pageInfo: PageInfo,
    val nodes: List<FlyApp>,
)

@Serializable
private class PageInfo(
    val hasNextPage: Boolean,
    val endCursor: String? = null,
)
