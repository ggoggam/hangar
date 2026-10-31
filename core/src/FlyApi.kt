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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

    /** Problems on the hosts under the app's machines, as `fly incidents hosts list` lists them. */
    suspend fun hostIssues(name: String): List<FlyHostIssue> =
        query<HostIssuesData>(HOST_ISSUES_QUERY, buildJsonObject { put("appName", name) })
            .app.hostIssues.nodes
            .orEmpty()

    /** The signed-in user's email, as `fly auth whoami` prints it, for a user or a macaroon. */
    suspend fun whoami(): String = query<WhoamiData>(WHOAMI_QUERY, buildJsonObject {}).viewer.email

    /** One organization with its members, as `fly orgs show` shows it. */
    suspend fun organization(slug: String): FlyOrganizationDetail {
        val org = query<OrganizationData>(ORGANIZATION_QUERY, buildJsonObject { put("slug", slug) }).organization
        return FlyOrganizationDetail(
            slug = org.slug,
            name = org.name,
            type = org.type,
            viewerRole = org.viewerRole,
            members =
                org.members?.edges.orEmpty().map {
                    FlyMember(
                        it.node?.name.orEmpty(),
                        it.node?.email.orEmpty(),
                        it.role,
                        it.joinedAt,
                    )
                },
        )
    }

    /** The organization's access tokens, revoked ones too, as `fly tokens list --scope org` lists them. */
    suspend fun organizationTokens(slug: String): List<FlyAccessToken> =
        query<OrganizationTokensData>(ORGANIZATION_TOKENS_QUERY, buildJsonObject { put("slug", slug) })
            .organization
            ?.limitedAccessTokens
            ?.nodes
            .orEmpty()

    /**
     * The organization's billing status, as `fly launch` checks it: `CURRENT`, `SOURCE_REQUIRED`,
     * `PAST_DUE`, `DELINQUENT`, `TRIAL_ACTIVE`, `TRIAL_ENDED` or `SUSPENDED`. Null when Fly.io
     * doesn't say, as it may not to a member who can't see billing.
     */
    suspend fun billingStatus(slug: String): String? =
        query<OrganizationBillingData>(ORGANIZATION_BILLING_QUERY, buildJsonObject { put("slug", slug) })
            .organization
            ?.billingStatus

    /** The organizations the user belongs to, as `fly orgs list` lists them. */
    suspend fun organizations(): List<FlyOrganization> =
        query<OrganizationsData>(ORGANIZATIONS_QUERY, buildJsonObject {}).organizations.nodes.sortedBy { it.slug }

    /**
     * Every extension of one [type] across the user's organizations, such as `upstash_redis` for
     * `fly redis list` or `tigris` for `fly storage list`.
     */
    suspend fun addOns(type: String): List<FlyAddOn> =
        query<AddOnsData>(ADD_ONS_QUERY, buildJsonObject { put("addOnType", type) })
            .addOns.nodes
            .sortedWith(compareBy({ it.organization?.slug }, { it.name }))

    /**
     * One Tigris bucket, as `fly storage status` shows it. Its options carry a shadow bucket's
     * access keys, which [FlyBucket] leaves behind.
     */
    suspend fun bucket(name: String): FlyBucket =
        query<BucketData>(
            BUCKET_QUERY,
            buildJsonObject {
                put("name", name)
                put("provider", "tigris")
            },
        ).addOn?.toBucket() ?: throw FlyApiException.Failed("no bucket named $name")

    /** The unmanaged Postgres clusters, which are apps of their own, as `fly postgres list` finds them. */
    suspend fun postgresApps(): List<FlyApp> {
        val apps = mutableListOf<FlyApp>()
        var after: String? = null
        do {
            val page =
                query<RoleAppsData>(
                    ROLE_APPS_QUERY,
                    buildJsonObject {
                        put("after", after)
                        put("role", "postgres_cluster")
                    },
                ).apps
            apps += page.nodes
            after = page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage }
        } while (after != null)
        return apps.sortedWith(compareBy({ it.organization.slug }, { it.name }))
    }

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
        // Each query is named, as fly-go's generated ones are; the demo answers them by name.

        // fly-go's getAppsPage, trimmed to what the app shows, with each app's latest release in any
        // state, which the background check reads for deploys that finished or failed.
        const val OVERVIEW_QUERY = """
            query Overview(${'$'}after: String) {
              viewer { email }
              apps(type: "container", first: 200, after: ${'$'}after) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  name
                  deployed
                  status
                  organization { slug }
                  currentRelease { createdAt status }
                  latestRelease: currentReleaseUnprocessed { version status createdAt updatedAt user { email } }
                }
              }
            }
        """

        const val RELEASE_FIELDS = "id version description reason status imageRef stable user { email name } createdAt"

        // fly-go's GetAppCompact, the app half of `fly status`.
        const val APP_QUERY = """
            query App(${'$'}appName: String!) {
              app(name: ${'$'}appName) {
                name
                hostname
                deployed
                status
                platformVersion
                organization { slug internalNumericId }
                currentRelease: currentReleaseUnprocessed { $RELEASE_FIELDS }
              }
            }
        """

        // fly-go's GetAppReleasesMachines, behind `fly releases`.
        const val RELEASES_QUERY = """
            query Releases(${'$'}appName: String!, ${'$'}limit: Int!) {
              app(name: ${'$'}appName) {
                releases: releasesUnprocessed(first: ${'$'}limit) { nodes { $RELEASE_FIELDS } }
              }
            }
        """

        // fly-go's GetAppHostIssues, behind `fly incidents hosts list`.
        const val HOST_ISSUES_QUERY = """
            query HostIssues(${'$'}appName: String!) {
              app(name: ${'$'}appName) {
                hostIssues { nodes { internalId message createdAt updatedAt } }
              }
            }
        """

        // fly-go's GetOrganizations, trimmed to what the app shows.
        const val ORGANIZATIONS_QUERY = """
            query Organizations {
              organizations { nodes { slug rawSlug name type viewerRole } }
            }
        """

        // fly-go's GetCurrentUser, behind `fly auth whoami`.
        const val WHOAMI_QUERY = """
            query Whoami {
              viewer {
                ... on User { email }
                ... on Macaroon { email }
              }
            }
        """

        // fly-go's GetDetailedOrganizationBySlug, behind `fly orgs show`.
        const val ORGANIZATION_QUERY = """
            query Organization(${'$'}slug: String!) {
              organization(slug: ${'$'}slug) {
                slug
                name
                type
                viewerRole
                members { edges { node { id name email } joinedAt role } }
              }
            }
        """

        // flyctl's GetOrganization, trimmed to the billing status `fly launch` checks before it deploys.
        const val ORGANIZATION_BILLING_QUERY = """
            query OrganizationBilling(${'$'}slug: String!) {
              organization(slug: ${'$'}slug) { billingStatus }
            }
        """

        // fly-go's GetOrgLimitedAccessTokens, behind `fly tokens list --scope org`.
        const val ORGANIZATION_TOKENS_QUERY = """
            query OrganizationTokens(${'$'}slug: String!) {
              organization(slug: ${'$'}slug) {
                limitedAccessTokens { nodes { id name expiresAt revokedAt user { email } } }
              }
            }
        """

        // flyctl's ListAddOns, behind `fly redis list` and `fly storage list`.
        const val ADD_ONS_QUERY = """
            query AddOns(${'$'}addOnType: AddOnType) {
              addOns(type: ${'$'}addOnType) {
                nodes {
                  id
                  name
                  addOnPlan { displayName }
                  primaryRegion
                  readRegions
                  options
                  organization { slug }
                }
              }
            }
        """

        // flyctl's GetAddOn, behind `fly storage status`, without the password and SSO link it asks for too.
        const val BUCKET_QUERY = """
            query Bucket(${'$'}name: String, ${'$'}provider: String) {
              addOn(name: ${'$'}name, provider: ${'$'}provider) {
                id
                name
                status
                errorMessage
                createdAt
                options
                organization { slug }
                app { name }
              }
            }
        """

        // fly-go's getAppsPage with a role, behind `fly postgres list`.
        const val ROLE_APPS_QUERY = """
            query RoleApps(${'$'}after: String, ${'$'}role: String) {
              apps(type: "container", first: 200, after: ${'$'}after, role: ${'$'}role) {
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
    /** The latest release whatever its status, a deploy still running included; only from the app list. */
    val latestRelease: FlyRelease? = null,
)

@Serializable
class FlyOrganization(
    /** The slug flyctl shows, which is `personal` for the user's personal organization. */
    val slug: String,
    /** The real slug, which flyctl sends to the Managed Postgres APIs; null when not asked for. */
    val rawSlug: String? = null,
    val name: String? = null,
    /** `PERSONAL` or `SHARED`; null when not asked for. */
    val type: String? = null,
    /** The user's role in it, such as `admin` or `member`; null when not asked for. */
    val viewerRole: String? = null,
    // A BigInt, which Fly.io's GraphQL sends as a string.
    @SerialName("internalNumericId") private val numericId: JsonPrimitive? = null,
) {
    /** The organization's number on Fly.io's Grafana, fly-metrics.net; null when not asked for. */
    val internalNumericId: String? get() = numericId?.content
}

/** An organization as `fly orgs show` shows it. */
class FlyOrganizationDetail(
    val slug: String,
    val name: String,
    /** `PERSONAL` or `SHARED`. */
    val type: String,
    /** The user's own role in it. */
    val viewerRole: String,
    val members: List<FlyMember>,
)

/** Someone in an organization: their name, email and role (`ADMIN`, `MEMBER` and so on). */
class FlyMember(
    val name: String,
    val email: String,
    val role: String,
    val joinedAt: String?,
)

/** An access token made with `fly tokens create`, as `fly tokens list` lists it. Never the token itself. */
@Serializable
class FlyAccessToken(
    val id: String = "",
    val name: String = "",
    val expiresAt: String? = null,
    val revokedAt: String? = null,
    @SerialName("user") private val creator: FlyUser? = null,
) {
    /** Who made it. */
    val createdBy: String? get() = creator?.email?.ifEmpty { null }
}

@Serializable
private class OrganizationDetail(
    val slug: String,
    val name: String = "",
    val type: String = "",
    val viewerRole: String = "",
    val members: MemberEdges? = null,
)

@Serializable
private class MemberEdges(
    val edges: List<MemberEdge>? = null,
)

@Serializable
private class MemberEdge(
    val node: MemberNode? = null,
    val joinedAt: String? = null,
    val role: String = "",
)

@Serializable
private class MemberNode(
    val name: String? = null,
    val email: String? = null,
)

/** A problem on a host under some of an app's machines. */
@Serializable
class FlyHostIssue(
    val internalId: String = "",
    val message: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** An extension provisioned through Fly.io, such as an Upstash Redis database or a Tigris bucket. */
@Serializable
class FlyAddOn(
    val id: String = "",
    val name: String = "",
    @SerialName("addOnPlan") private val plan: FlyAddOnPlan? = null,
    // Tigris buckets come with an empty region and null read regions.
    @SerialName("primaryRegion") private val region: String? = null,
    @SerialName("readRegions") private val replicaRegions: List<String>? = null,
    private val options: JsonElement? = null,
    val organization: FlyOrganization? = null,
) {
    val planName: String? get() = plan?.displayName

    val primaryRegion: String? get() = region?.ifBlank { null }

    val readRegions: List<String> get() = replicaRegions.orEmpty()

    /** Whether Redis evicts keys when it's full, which `fly redis list` shows. */
    val eviction: Boolean get() = option("eviction")

    /** Whether the Upstash production pack is on, which `fly redis list` shows. */
    val prodPack: Boolean get() = option("prod_pack")

    /** Whether a Tigris bucket's objects can be read without a key, as `fly storage update --public` sets. */
    val public: Boolean get() = option("public")

    private fun option(key: String) = ((options as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull == true
}

@Serializable
class FlyAddOnPlan(
    val displayName: String? = null,
)

/**
 * A Tigris bucket, as `fly storage status` shows it, with the settings `fly storage update` changes.
 * Never its shadow bucket's access keys.
 */
class FlyBucket(
    val id: String,
    val name: String,
    /** As the extension reports it; null when Fly.io doesn't say. */
    val status: String?,
    /** Why, when [status] is `error`. */
    val errorMessage: String?,
    val createdAt: String?,
    val org: String?,
    /** The app it was made for, whose secrets hold its keys; null for one made on its own. */
    val app: String?,
    /** Whether anyone can read its objects without a key (`--public`). */
    val public: Boolean,
    /** Whether objects are cached in every region as they're written (`--accelerate`). */
    val accelerate: Boolean,
    /** The domain it serves its objects on as a website (`--custom-domain`). */
    val customDomain: String?,
    /** The bucket elsewhere it reads objects it doesn't have from (`--shadow-name` and the rest). */
    val shadow: FlyShadowBucket?,
) {
    /** Where a public bucket's objects are, by key under it. */
    val publicUrl: String get() = "https://$name.fly.storage.tigris.dev"

    /** What [customDomain]'s CNAME record points at, as `fly storage update --custom-domain` says. */
    val cnameTarget: String get() = "$name.t3.tigrisbucket.io"

    companion object {
        /** The S3 endpoint every bucket on Fly.io answers at, which `fly storage create` sets as `AWS_ENDPOINT_URL_S3`. */
        const val ENDPOINT = "https://fly.storage.tigris.dev"
    }
}

/** A bucket on another S3-compatible service that a Tigris bucket fills itself from. */
class FlyShadowBucket(
    val name: String,
    val endpoint: String?,
    val region: String?,
    /** Whether new objects are written to it too, not only read from it. */
    val writeThrough: Boolean,
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
    val updatedAt: String = "",
) {
    /** Whether it went out, as opposed to failing, being interrupted or still running. */
    val succeeded: Boolean get() = status == "complete" || status == "succeeded"
}

@Serializable
class FlyUser(
    val email: String = "",
    val name: String? = null,
)

/** What a release changed from the one that went out before it. */
sealed interface FlyReleaseChange {
    /** The release before it that went out. */
    val previous: FlyRelease

    /** It deployed a different image from [previous]'s. */
    class NewImage(
        override val previous: FlyRelease,
    ) : FlyReleaseChange

    /** It kept [previous]'s image, so its config or secrets changed, or it was the same deploy again. */
    class SameImage(
        override val previous: FlyRelease,
    ) : FlyReleaseChange
}

/**
 * What [release] changed from the latest earlier release in [releases] that went out, by their
 * image refs, as comparing `fly releases --image` rows would show. Null when no earlier one in
 * [releases] went out, or either has no image ref.
 */
fun releaseChange(
    release: FlyRelease,
    releases: List<FlyRelease>,
): FlyReleaseChange? {
    val previous = releases.filter { it.version < release.version && it.succeeded }.maxByOrNull { it.version } ?: return null
    if (release.imageRef.isEmpty() || previous.imageRef.isEmpty()) return null
    return if (release.imageRef == previous.imageRef) FlyReleaseChange.SameImage(previous) else FlyReleaseChange.NewImage(previous)
}

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
private class HostIssuesData(
    val app: HostIssuesApp,
)

@Serializable
private class HostIssuesApp(
    val hostIssues: HostIssueNodes,
)

@Serializable
private class HostIssueNodes(
    val nodes: List<FlyHostIssue>? = null,
)

@Serializable
private class WhoamiData(
    val viewer: Viewer,
)

@Serializable
private class OrganizationData(
    val organization: OrganizationDetail,
)

@Serializable
private class OrganizationBillingData(
    val organization: OrganizationBilling? = null,
)

@Serializable
private class OrganizationBilling(
    val billingStatus: String? = null,
)

@Serializable
private class OrganizationTokensData(
    val organization: OrganizationTokens? = null,
)

@Serializable
private class OrganizationTokens(
    val limitedAccessTokens: AccessTokenNodes? = null,
)

@Serializable
private class AccessTokenNodes(
    val nodes: List<FlyAccessToken>? = null,
)

@Serializable
private class OrganizationsData(
    val organizations: OrganizationNodes,
)

@Serializable
private class OrganizationNodes(
    val nodes: List<FlyOrganization> = emptyList(),
)

@Serializable
private class AddOnsData(
    val addOns: AddOnNodes,
)

@Serializable
private class AddOnNodes(
    val nodes: List<FlyAddOn> = emptyList(),
)

@Serializable
private class BucketData(
    val addOn: BucketNode? = null,
)

@Serializable
private class BucketNode(
    val id: String = "",
    val name: String = "",
    val status: String? = null,
    val errorMessage: String? = null,
    val createdAt: String? = null,
    val options: JsonElement? = null,
    val organization: FlyOrganization? = null,
    val app: BucketApp? = null,
) {
    // Takes the settings out of the options by name, so the shadow bucket's keys go no further.
    fun toBucket(): FlyBucket {
        val options = options as? JsonObject
        val website = options?.get("website") as? JsonObject
        val shadow = options?.get("shadow_bucket") as? JsonObject
        return FlyBucket(
            id = id,
            name = name,
            status = status?.ifBlank { null },
            errorMessage = errorMessage?.ifBlank { null },
            createdAt = createdAt,
            org = organization?.slug,
            app = app?.name?.ifBlank { null },
            public = options.flag("public"),
            accelerate = options.flag("accelerate"),
            customDomain = website.text("domain_name"),
            // `fly storage update --clear-shadow` leaves an empty object behind.
            shadow =
                shadow.text("name")?.let {
                    FlyShadowBucket(it, shadow.text("endpoint"), shadow.text("region"), shadow.flag("write_through"))
                },
        )
    }

    private fun JsonObject?.flag(key: String) = (this?.get(key) as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
}

@Serializable
private class BucketApp(
    val name: String = "",
)

@Serializable
private class RoleAppsData(
    val apps: AppPage,
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
