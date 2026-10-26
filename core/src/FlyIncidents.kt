package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Fly.io's platform incidents, from the feed `fly incidents list` reads. It takes no token, so none
 * is sent.
 */
class FlyIncidents(
    private val http: HttpClient,
    private val incidentsUrl: String = FLY_INCIDENTS_URL,
) {
    /** The incidents still open, as `fly incidents list` lists them. */
    suspend fun active(): List<FlyIncident> =
        http.getJson<IncidentsResponse>("$incidentsUrl/v1/incidents", authorization = null).incidents.orEmpty()
}

@Serializable
class FlyIncident(
    val id: String = "",
    val name: String = "",
    /** Statuspage's `investigating`, `identified`, `monitoring` or `resolved`. */
    val status: String = "",
    @SerialName("components") private val affected: List<FlyIncidentComponent>? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** The parts of the platform it touches, such as `Machines API`. */
    val components: List<FlyIncidentComponent> get() = affected.orEmpty()
}

@Serializable
class FlyIncidentComponent(
    val id: String = "",
    val name: String = "",
)

@Serializable
private class IncidentsResponse(
    val incidents: List<FlyIncident>? = null,
)
