package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The Machines API at api.machines.dev, read-only: an app's machines, volumes, IPs and certificates. */
class FlyMachines(
    private val http: HttpClient,
    private val token: FlyToken,
    private val machinesUrl: String = FLY_MACHINES_URL,
) {
    /** The app's live machines, as `fly status` lists them: no destroyed, release-command or console ones. */
    suspend fun machines(app: String): List<FlyMachine> =
        http
            .getJson<List<FlyMachine>>("$machinesUrl/v1/apps/$app/machines", token.machinesHeader)
            .filter { it.isActive }

    /** The app's volumes, without the ones on their way out, as `fly volumes list` shows them. */
    suspend fun volumes(app: String): List<FlyVolume> =
        http
            .getJson<List<FlyVolume>>("$machinesUrl/v1/apps/$app/volumes", token.machinesHeader)
            .filter { it.state !in GONE_VOLUME_STATES }

    suspend fun ips(app: String): List<FlyIp> =
        http.getJson<IpAssignments>("$machinesUrl/v1/apps/$app/ip_assignments", token.machinesHeader).ips

    suspend fun certificates(app: String): List<FlyCertificate> =
        http.getJson<Certificates>("$machinesUrl/v1/apps/$app/certificates", token.machinesHeader).certificates

    /** Fly.io's regions, without deprecated ones, and the one nearest the caller, as `fly platform regions` lists them. */
    suspend fun regions(): FlyRegions {
        val data = http.getJson<RegionData>("$machinesUrl/v1/platform/regions", token.machinesHeader)
        return FlyRegions(
            data.regions
                .orEmpty()
                .filterNot { it.deprecated }
                .sortedBy { it.name },
            data.nearest,
        )
    }

    private companion object {
        // flyctl's volume list skips these.
        val GONE_VOLUME_STATES =
            setOf("scheduling_destroy", "fork_cleanup", "waiting_for_detach", "pending_destroy", "destroying", "destroyed")
    }
}

@Serializable
class FlyMachine(
    val id: String,
    val name: String = "",
    /** `started`, `stopped`, `suspended`, `created`, `failed`, `replacing` and so on. */
    val state: String,
    val region: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** `ok`, or `unknown` / `unreachable` when Fly.io can't reach the machine's host. */
    @SerialName("host_status") val hostStatus: String = "ok",
    @SerialName("image_ref") val imageRef: FlyImageRef? = null,
    // The config also holds env, which can carry secrets, so only these parts of it are decoded.
    val config: FlyMachineConfig? = null,
    @SerialName("checks") private val allChecks: List<FlyCheck> = emptyList(),
    val events: List<FlyMachineEvent> = emptyList(),
) {
    /** Health checks, without the internal `bg_deployments_compat` ones flyctl hides. */
    val checks: List<FlyCheck> get() = allChecks.filterNot { it.name.startsWith("bg_deployments_compat") }

    val processGroup: String? get() = metadata("fly_process_group")

    val releaseVersion: Int? get() = metadata("fly_release_version")?.toIntOrNull()

    private fun metadata(key: String): String? = (config?.metadata?.get(key) as? JsonPrimitive)?.content

    internal val isActive: Boolean
        get() =
            state !in setOf("destroyed", "destroying") &&
                metadata("process_group") != "release_command" &&
                processGroup != "fly_app_release_command" &&
                processGroup != "fly_app_console"
}

@Serializable
class FlyImageRef(
    val registry: String = "",
    val repository: String = "",
    val tag: String = "",
    val digest: String = "",
) {
    /** `repository:tag`, the short form flyctl prints. */
    val short: String get() = if (tag.isEmpty()) repository else "$repository:$tag"
}

@Serializable
class FlyMachineConfig(
    val guest: FlyGuest? = null,
    val metadata: JsonObject? = null,
    val mounts: List<FlyMount>? = null,
)

@Serializable
class FlyGuest(
    @SerialName("cpu_kind") val cpuKind: String = "shared",
    val cpus: Int = 1,
    @SerialName("memory_mb") val memoryMb: Int = 0,
    val gpus: Int = 0,
    @SerialName("gpu_kind") val gpuKind: String? = null,
) {
    /** The preset name flyctl shows, such as `shared-cpu-1x` or `performance-2x`. */
    val size: String get() = if (cpuKind == "shared") "shared-cpu-${cpus}x" else "$cpuKind-${cpus}x"
}

@Serializable
class FlyMount(
    val volume: String = "",
    val path: String = "",
    val name: String = "",
)

@Serializable
class FlyCheck(
    val name: String,
    /** `passing`, `warning` or `critical`. */
    val status: String,
    /** What the check last returned; often a short body or JSON. */
    val output: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
class FlyMachineEvent(
    /** `launch`, `start`, `stop`, `exit`, `update`, `restart` and so on. */
    val type: String,
    val status: String = "",
    /** `flyd` for the platform, `user` for flyctl or the API. */
    val source: String = "",
    /** Milliseconds since the epoch. */
    val timestamp: Long,
    private val request: JsonObject? = null,
) {
    private val exitEvent: JsonObject? get() = runCatching { request?.get("exit_event")?.jsonObject }.getOrNull()

    /** For an `exit` event, the process's exit code. */
    val exitCode: Int? get() = exitEvent?.get("exit_code")?.jsonPrimitive?.intOrNull

    /** For an `exit` event, whether the kernel killed it for running out of memory. */
    val oomKilled: Boolean get() = exitEvent?.get("oom_killed")?.jsonPrimitive?.booleanOrNull == true
}

@Serializable
class FlyVolume(
    val id: String,
    val name: String = "",
    val state: String = "",
    @SerialName("size_gb") val sizeGb: Int = 0,
    val region: String = "",
    val encrypted: Boolean = false,
    @SerialName("attached_machine_id") val attachedMachineId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    // Not in Fly.io's API docs, but the API returns them; null when it doesn't.
    @SerialName("bytes_used") val bytesUsed: Long? = null,
    @SerialName("bytes_total") val bytesTotal: Long? = null,
)

@Serializable
class FlyIp(
    val ip: String,
    /** `global` for anycast addresses; null for some. */
    val region: String? = null,
    val shared: Boolean = false,
    val egress: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
) {
    /** fly-go's IPAssignment.Type(). */
    val type: FlyIpType
        get() =
            when {
                egress -> if (':' in ip) FlyIpType.EgressV6 else FlyIpType.EgressV4
                ip.startsWith("fdaa:") -> FlyIpType.PrivateV6
                shared -> FlyIpType.SharedV4
                ':' in ip -> FlyIpType.V6
                else -> FlyIpType.V4
            }
}

enum class FlyIpType { V4, V6, SharedV4, PrivateV6, EgressV4, EgressV6 }

@Serializable
class FlyCertificate(
    val hostname: String,
    /** `Ready` once issued; other values while Fly.io waits on DNS or the CA. */
    val status: String = "",
    val configured: Boolean = false,
)

class FlyRegions(
    val regions: List<FlyRegion>,
    /** The code of the region nearest the phone, as Fly.io's edge sees it. */
    val nearest: String?,
)

@Serializable
class FlyRegion(
    val code: String,
    val name: String = "",
    /** `africa`, `asia_pacific`, `europe`, `north_america` or `south_america`. */
    @SerialName("geo_region") val geoRegion: String = "",
    @SerialName("mpg_available") val mpgAvailable: Boolean = false,
    @SerialName("requires_paid_plan") val requiresPaidPlan: Boolean = false,
    val deprecated: Boolean = false,
)

// fly-go's flaps.RegionData, whose keys are capitalized as Go's encoder left them, the nearest's aside.
@Serializable
private class RegionData(
    @SerialName("Regions") val regions: List<FlyRegion>? = null,
    @SerialName("nearest") val nearest: String? = null,
)

@Serializable
private class IpAssignments(
    val ips: List<FlyIp> = emptyList(),
)

@Serializable
private class Certificates(
    val certificates: List<FlyCertificate> = emptyList(),
)
