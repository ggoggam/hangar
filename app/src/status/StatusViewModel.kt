package dev.ggoggam.hangar.app.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.app.dashboard.Load
import dev.ggoggam.hangar.core.FlyIncident
import dev.ggoggam.hangar.core.FlyIncidents
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusState(
    val incidents: Load<List<FlyIncident>> = Load.Loading,
    val refreshing: Boolean = false,
)

/** Fly.io's open incidents, as `fly incidents list` shows them. The feed takes no token. */
class StatusViewModel(
    private val http: HttpClient,
) : ViewModel() {
    private val _state = MutableStateFlow(StatusState())
    val state: StateFlow<StatusState> = _state.asStateFlow()

    init {
        refresh(quiet = true)
    }

    /** Loads the incidents again; [quiet] keeps the pull-to-refresh spinner hidden, for timed reloads. */
    fun refresh(quiet: Boolean = false) {
        if (!quiet) _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val result =
                try {
                    Load.Ready(FlyIncidents(http).active())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Load.Failed
                }
            // A failed reload keeps what was already shown.
            _state.update { s ->
                val incidents = if (result is Load.Failed && s.incidents is Load.Ready) s.incidents else result
                s.copy(incidents = incidents, refreshing = false)
            }
        }
    }
}
