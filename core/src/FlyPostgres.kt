package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Managed Postgres clusters, from the two places `fly mpg list` looks: the Machines API's newer
 * `/v1/postgres`, which not every organization has yet, and the older list under
 * api.fly.io/api/v1, which still holds the clusters made before it and each cluster's disk size.
 * Fly.io's Go services send an empty list as null, so every list read here can be null.
 */
class FlyManagedPostgres(
    private val http: HttpClient,
    private val token: FlyToken,
    private val apiUrl: String = FLY_API_URL,
    private val machinesUrl: String = FLY_MACHINES_URL,
) {
    /**
     * [org]'s clusters, merged as flyctl's `listManagedClusters` merges them: the Machines API's
     * first, with the older list's details filled in by id, then the older list's own.
     */
    suspend fun clusters(org: FlyOrganization): List<FlyPostgresCluster> {
        // Both take the real slug, never `personal`.
        val slug = org.rawSlug ?: org.slug
        val current =
            http.getJsonOrNull<ClusterSummaries>("$machinesUrl/v1/postgres?org_slug=$slug", token.machinesHeader)?.data.orEmpty()
        val older = http.getJson<OlderClusters>("$apiUrl/api/v1/organizations/$slug/postgres", token.uiexHeader).data.orEmpty()
        val olderById = older.associateBy { it.id }
        val fromCurrent =
            current.map { c ->
                val o = olderById[c.id]
                FlyPostgresCluster(
                    id = c.id,
                    name = c.name,
                    org = org.slug,
                    region = c.region,
                    status = c.status,
                    plan = c.plan,
                    diskGb = o?.disk,
                    replicas = o?.replicas,
                    attachedApps = c.attachedApps.orEmpty().map { it.name },
                )
            }
        val seen = current.mapTo(HashSet()) { it.id }
        val fromOlder =
            older.filter { it.id !in seen }.map { o ->
                FlyPostgresCluster(
                    id = o.id,
                    name = o.name,
                    org = org.slug,
                    region = o.region,
                    status = o.status,
                    plan = o.plan,
                    diskGb = o.disk,
                    replicas = o.replicas,
                    attachedApps = o.attachedApps.orEmpty().map { it.name },
                )
            }
        return fromCurrent + fromOlder
    }
}

/** One Managed Postgres cluster, as a row of `fly mpg list`. */
class FlyPostgresCluster(
    val id: String,
    val name: String,
    /** The organization's slug as flyctl shows it, `personal` included. */
    val org: String,
    val region: String,
    /** `ready` once it's up; `provisioning`, `failed` or `error` otherwise. */
    val status: String,
    val plan: String,
    /** The disk in GB, which only the older list carries. */
    val diskGb: Int?,
    val replicas: Int?,
    val attachedApps: List<String>,
)

@Serializable
private class ClusterSummaries(
    val data: List<ClusterSummary>? = null,
)

// fly-go's flaps.ManagedPostgresClusterSummary.
@Serializable
private class ClusterSummary(
    val id: String,
    val name: String = "",
    val status: String = "",
    val region: String = "",
    val plan: String = "",
    @SerialName("attached_apps") val attachedApps: List<AttachedApp>? = null,
)

@Serializable
private class OlderClusters(
    val data: List<OlderCluster>? = null,
)

// flyctl's uiex/mpg/v1 ManagedCluster.
@Serializable
private class OlderCluster(
    val id: String,
    val name: String = "",
    val region: String = "",
    val status: String = "",
    val plan: String = "",
    val disk: Int? = null,
    val replicas: Int? = null,
    @SerialName("attached_apps") val attachedApps: List<AttachedApp>? = null,
)

@Serializable
private class AttachedApp(
    val name: String = "",
)
