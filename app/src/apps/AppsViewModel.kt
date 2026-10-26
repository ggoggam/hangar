package dev.ggoggam.hangar.app.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.core.FlyApi
import dev.ggoggam.hangar.core.FlyApiException
import dev.ggoggam.hangar.core.FlyApp
import dev.ggoggam.hangar.core.FlyIncident
import dev.ggoggam.hangar.core.FlyIncidents
import dev.ggoggam.hangar.core.FlySession
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppsState(
    val email: String? = null,
    val apps: List<FlyApp>? = null,
    /** Fly.io's open incidents, or null until they've loaded or when the feed can't be reached. */
    val incidents: List<FlyIncident>? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

class AppsViewModel(
    private val http: HttpClient,
    private val session: FlySession,
) : ViewModel() {
    private val _state = MutableStateFlow(AppsState())
    val state: StateFlow<AppsState> = _state.asStateFlow()

    private var load: Job? = null

    init {
        // A new account: drop the old one's apps and load again. Renewed discharges are the same account.
        viewModelScope.launch {
            session.token.map { it?.identity }.distinctUntilChanged().collect { identity ->
                _state.value = AppsState()
                if (identity != null) refresh()
            }
        }
    }

    fun refresh() {
        if (session.token.value == null) return
        load?.cancel()
        _state.update { it.copy(loading = true, failed = false) }
        load =
            viewModelScope.launch {
                // Beside the apps, as flyctl checks for incidents beside its commands. The list
                // doesn't wait for it, and a feed that can't be reached leaves the banner as it was.
                launch {
                    try {
                        val incidents = FlyIncidents(http).active()
                        _state.update { it.copy(incidents = incidents) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Leave the banner as it was.
                    }
                }
                try {
                    val overview = session.authorized { FlyApi(http, it).overview() }
                    _state.update { it.copy(email = overview.email, apps = overview.apps, loading = false, failed = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FlyApiException.Unauthorized) {
                    // Signed out, and the login screen is up.
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, failed = true) }
                }
            }
    }
}
