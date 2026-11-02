package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Fly.io's platform incidents, from status.flyio.net, else the feed `fly incidents list` reads. The
 * feed can sit empty while the status page reports an incident, so the page comes first. Neither
 * takes a token, so none is sent.
 */
class FlyIncidents(
    private val http: HttpClient,
    private val statusUrl: String = FLY_STATUS_URL,
    private val incidentsUrl: String = FLY_INCIDENTS_URL,
) {
    /** The incidents still open, from the status page, or from flyctl's feed when the page can't be read. */
    suspend fun active(): List<FlyIncident> =
        try {
            statusPage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            http.getJson<IncidentsResponse>("$incidentsUrl/v1/incidents", authorization = null).incidents.orEmpty()
        }

    /** The incidents on the status page's summary, which lists only unresolved ones. */
    private suspend fun statusPage(): List<FlyIncident> =
        http
            .getJson<IncidentsResponse>("$statusUrl/api/v2/summary.json", authorization = null)
            .incidents
            .orEmpty()
            .filter { it.status != "resolved" && it.status != "postmortem" }
}

@Serializable
class FlyIncident(
    val id: String = "",
    val name: String = "",
    /** Statuspage's `investigating`, `identified`, `monitoring` or `resolved`. */
    val status: String = "",
    @SerialName("components") private val affected: List<FlyIncidentComponent>? = null,
    @SerialName("started_at") private val started: String? = null,
    @SerialName("created_at") private val created: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** The parts of the platform it touches, such as `Machines API`. */
    val components: List<FlyIncidentComponent> get() = affected.orEmpty()

    /** When it started, or when it was posted, which is all the status page says. */
    val startedAt: String? get() = started ?: created
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
