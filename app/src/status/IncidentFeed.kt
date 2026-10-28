package dev.ggoggam.hangar.app.status

import dev.ggoggam.hangar.core.FlyIncident
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fly.io's open incidents, as `fly incidents list` lists them, shared by the banner over every
 * screen and the list at the top of the apps. [fetch] loads them through whichever client the
 * session is using, so the demo never reaches the network.
 */
class IncidentFeed(
    private val fetch: suspend () -> List<FlyIncident>,
) {
    private val _incidents = MutableStateFlow<List<FlyIncident>?>(null)

    /** The open incidents, or null until they've loaded. */
    val incidents: StateFlow<List<FlyIncident>?> = _incidents.asStateFlow()

    private val _dismissed = MutableStateFlow(emptySet<String>())

    /** The incidents the banner was dismissed for. One that opens later brings it back. */
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    /** Loads the incidents again. A feed that can't be reached leaves them as they were. */
    suspend fun refresh() {
        try {
            _incidents.value = fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep what was already shown.
        }
    }

    /** Hides the banner for the incidents open now. */
    fun dismiss() {
        _dismissed.update { it + _incidents.value.orEmpty().map { incident -> incident.id } }
    }
}
