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
                    rawOrg = slug,
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
                    rawOrg = slug,
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

    /**
     * One cluster as `fly mpg status` shows it, found as flyctl's `ClusterFromArgOrSelect` finds it:
     * the Machines API's `/v1/postgres/{id}` (fly-go's `GetManagedPostgresCluster`, which
     * `cmdv2.RunStatus` shows). For a cluster the Machines API doesn't have, flyctl falls back to
     * api.fly.io/api/v1/postgres/{id}, but that answer carries a password and connection string,
     * so Hangar reads the cluster's row in [org]'s older list instead: the same fields, without them.
     */
    suspend fun cluster(
        id: String,
        org: FlyOrganization,
    ): FlyPostgresClusterDetail {
        http.getJsonOrNull<ClusterEnvelope>("$machinesUrl/v1/postgres/$id", token.machinesHeader)?.let { return it.data.detail(org.slug) }
        val slug = org.rawSlug ?: org.slug
        val older =
            http
                .getJson<OlderClusters>("$apiUrl/api/v1/organizations/$slug/postgres", token.uiexHeader)
                .data
                .orEmpty()
                .firstOrNull { it.id == id }
                ?: throw FlyApiException.Failed("No Managed Postgres cluster $id")
        return older.detail(org.slug)
    }

    /** [cluster]'s databases' names, as `fly mpg databases list` lists them. */
    suspend fun databases(cluster: FlyPostgresClusterDetail): List<String> =
        clusterList<ClusterDatabase>(cluster, "databases").map { it.name }

    /** [cluster]'s users and their roles, as `fly mpg users list` lists them. Passwords are a separate call, never made. */
    suspend fun users(cluster: FlyPostgresClusterDetail): List<FlyPostgresUser> =
        clusterList<ClusterUser>(cluster, "users").map { FlyPostgresUser(it.username ?: it.name.orEmpty(), it.role) }

    /** [cluster]'s backups, newest first, as `fly mpg backup list --all` lists them. */
    suspend fun backups(cluster: FlyPostgresClusterDetail): List<FlyPostgresBackup> =
        clusterList<ClusterBackup>(cluster, "backups")
            .map { FlyPostgresBackup(it.id, it.type, it.status, it.startedAt ?: it.start, it.finishedAt ?: it.stop, it.sizeBytes) }
            .sortedByDescending { it.startedAt.orEmpty() }

    /**
     * The list at `/{what}` under [cluster], from where flyctl reads it: the Machines API, falling
     * back to the older `postgresv2` endpoints where it's missing (`cmdv2`'s `RunDatabasesList`,
     * `listUsers` and `RunBackupList`), or for a first-version cluster the older `postgres` ones
     * (`cmdv1`'s).
     */
    private suspend inline fun <reified T> clusterList(
        cluster: FlyPostgresClusterDetail,
        what: String,
    ): List<T> {
        val id = cluster.id
        if (cluster.version != 2) return http.getJson<DataList<T>>("$apiUrl/api/v1/postgres/$id/$what", token.uiexHeader).data.orEmpty()
        val list =
            http.getJsonOrNull<DataList<T>>("$machinesUrl/v1/postgres/$id/$what", token.machinesHeader)
                ?: http.getJson<DataList<T>>("$apiUrl/api/v1/postgresv2/$id/$what", token.uiexHeader)
        return list.data.orEmpty()
    }
}

/** One Managed Postgres cluster, as a row of `fly mpg list`. */
class FlyPostgresCluster(
    val id: String,
    val name: String,
    /** The organization's slug as flyctl shows it, `personal` included. */
    val org: String,
    /** The organization's real slug, which the Managed Postgres APIs take. */
    val rawOrg: String,
    val region: String,
    /** `ready` once it's up; `provisioning`, `failed` or `error` otherwise. */
    val status: String,
    val plan: String,
    /** The disk in GB, which only the older list carries. */
    val diskGb: Int?,
    val replicas: Int?,
    val attachedApps: List<String>,
)

/**
 * One cluster as `fly mpg status` shows it. From the older list, for a cluster the Machines API
 * doesn't have, the size, version, usage, pooler and creation time are null.
 */
class FlyPostgresClusterDetail(
    val id: String,
    val name: String,
    /** The organization's slug as flyctl shows it, `personal` included. */
    val org: String,
    val region: String,
    /** `ready` once it's up; `provisioning`, `failed` or `error` otherwise. */
    val status: String,
    val plan: String,
    /** `shared` or `performance`. */
    val cpuKind: String?,
    val cpus: Int?,
    val memoryMb: Int?,
    val diskGb: Int?,
    val storageUsedBytes: Long?,
    val storageProvisionedBytes: Long?,
    /** Standbys beside the primary, for high availability. */
    val replicas: Int?,
    /** The major version, such as `17`. */
    val postgresVersion: String?,
    val postGis: Boolean,
    /** Where the primary takes connections directly. */
    val direct: FlyPostgresEndpoint?,
    /** Where it takes them through PgBouncer. */
    val pooler: FlyPostgresEndpoint?,
    val attachedApps: List<String>,
    val createdAt: String?,
    /** 1 for a cluster on the first Managed Postgres, whose lists are under other endpoints. */
    internal val version: Int,
)

/** A host and port to connect to. Hangar never asks for the credentials that go with them. */
class FlyPostgresEndpoint(
    val host: String,
    val port: Int?,
)

/** A database user and its role, such as `schema_admin`, `writer` or `reader`. */
class FlyPostgresUser(
    val name: String,
    val role: String,
)

/** A backup, as a row of `fly mpg backup list`. */
class FlyPostgresBackup(
    val id: String,
    /** `full` or `incr`. */
    val type: String,
    /** Such as `completed`. */
    val status: String,
    val startedAt: String?,
    val finishedAt: String?,
    /** Only the Machines API reports it. */
    val sizeBytes: Long?,
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
private class ClusterEnvelope(
    val data: PublicCluster,
)

// fly-go's flaps.ManagedPostgresCluster, which carries no credentials.
@Serializable
private class PublicCluster(
    val id: String,
    val name: String = "",
    val status: String = "",
    val region: String = "",
    val plan: String = "",
    @SerialName("disk_size_gb") val diskGb: Int? = null,
    @SerialName("storage_used_bytes") val storageUsedBytes: Long? = null,
    @SerialName("storage_provisioned_bytes") val storageProvisionedBytes: Long? = null,
    val cpus: Int? = null,
    @SerialName("cpu_kind") val cpuKind: String? = null,
    @SerialName("memory_mb") val memoryMb: Int? = null,
    val replicas: Int? = null,
    @SerialName("pg_major_version") val postgresVersion: String? = null,
    @SerialName("postgis_enabled") val postGis: Boolean = false,
    val endpoints: Endpoints? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("attached_apps") val attachedApps: List<AttachedApp>? = null,
) {
    fun detail(org: String) =
        FlyPostgresClusterDetail(
            id = id,
            name = name,
            org = org,
            region = region,
            status = status,
            plan = plan,
            cpuKind = cpuKind?.ifEmpty { null },
            cpus = cpus?.takeIf { it > 0 },
            memoryMb = memoryMb?.takeIf { it > 0 },
            diskGb = diskGb?.takeIf { it > 0 },
            storageUsedBytes = storageUsedBytes,
            storageProvisionedBytes = storageProvisionedBytes,
            replicas = replicas,
            postgresVersion = postgresVersion?.ifEmpty { null },
            postGis = postGis,
            direct = endpoints?.primary?.direct?.endpoint(),
            pooler = endpoints?.primary?.pooler?.endpoint(),
            attachedApps = attachedApps.orEmpty().map { it.name },
            createdAt = createdAt?.ifEmpty { null },
            version = 2,
        )
}

@Serializable
private class Endpoints(
    val primary: PrimaryEndpoints? = null,
)

@Serializable
private class PrimaryEndpoints(
    val direct: Endpoint? = null,
    val pooler: Endpoint? = null,
)

@Serializable
private class Endpoint(
    val host: String = "",
    val port: Int? = null,
) {
    fun endpoint() = if (host.isEmpty()) null else FlyPostgresEndpoint(host, port?.takeIf { it > 0 })
}

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
    /** 2 for a cluster on the second Managed Postgres; anything else is the first. */
    val version: Int? = null,
    @SerialName("ip_assignments") val ipAssignments: ClusterIps? = null,
) {
    fun detail(org: String) =
        FlyPostgresClusterDetail(
            id = id,
            name = name,
            org = org,
            region = region,
            status = status,
            plan = plan,
            cpuKind = null,
            cpus = null,
            memoryMb = null,
            diskGb = disk,
            storageUsedBytes = null,
            storageProvisionedBytes = null,
            replicas = replicas,
            postgresVersion = null,
            postGis = false,
            // What `fly mpg status` shows as the Direct IP; the list gives no port with it.
            direct = ipAssignments?.direct?.ifEmpty { null }?.let { FlyPostgresEndpoint(it, null) },
            pooler = null,
            attachedApps = attachedApps.orEmpty().map { it.name },
            createdAt = null,
            version = if (version == 2) 2 else 1,
        )
}

@Serializable
private class ClusterIps(
    val direct: String? = null,
)

@Serializable
private class AttachedApp(
    val name: String = "",
)

@Serializable
private class DataList<T>(
    val data: List<T>? = null,
)

@Serializable
private class ClusterDatabase(
    val name: String = "",
)

// The Machines API names a user `username`, the older endpoints `name`. Neither sends a password in a list.
@Serializable
private class ClusterUser(
    val username: String? = null,
    val name: String? = null,
    val role: String = "",
)

// The Machines API's times are `started_at` and `finished_at`, the older endpoints' `start` and `stop`.
@Serializable
private class ClusterBackup(
    val id: String = "",
    val status: String = "",
    val type: String = "",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val start: String? = null,
    val stop: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)
