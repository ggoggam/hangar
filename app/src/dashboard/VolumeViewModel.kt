package dev.ggoggam.hangar.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.core.FlyMachines
import dev.ggoggam.hangar.core.FlySession
import dev.ggoggam.hangar.core.FlyVolumeSnapshot
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VolumeState(
    val snapshots: Load<List<FlyVolumeSnapshot>> = Load.Loading,
    val refreshing: Boolean = false,
)

/**
 * One of an app's volumes' snapshots, as `fly volumes snapshots list` lists them. The volume itself
 * is the dashboard's, from the list it has loaded.
 */
class VolumeViewModel(
    private val appName: String,
    val volumeId: String,
    private val http: HttpClient,
    private val session: FlySession,
) : ViewModel() {
    private val _state = MutableStateFlow(VolumeState())
    val state: StateFlow<VolumeState> = _state.asStateFlow()

    init {
        refresh(quiet = true)
    }

    /** Reads them again; [quiet] keeps the pull-to-refresh spinner hidden. */
    fun refresh(quiet: Boolean = false) {
        if (session.token.value == null) return
        if (!quiet) _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            viewModelScope
                .loadInto(_state, session, { FlyMachines(http, it).volumeSnapshots(appName, volumeId) }, { it.snapshots }) { s, v ->
                    s.copy(snapshots = v)
                }.join()
            _state.update { it.copy(refreshing = false) }
        }
    }
}
