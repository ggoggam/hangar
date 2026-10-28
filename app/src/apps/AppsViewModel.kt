package dev.ggoggam.hangar.app.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.app.dashboard.Load
import dev.ggoggam.hangar.app.dashboard.loadInto
import dev.ggoggam.hangar.app.status.IncidentFeed
import dev.ggoggam.hangar.core.FlyApi
import dev.ggoggam.hangar.core.FlyApiException
import dev.ggoggam.hangar.core.FlyApp
import dev.ggoggam.hangar.core.FlyIncident
import dev.ggoggam.hangar.core.FlyOrgUsage
import dev.ggoggam.hangar.core.FlySession
import dev.ggoggam.hangar.core.FlyUsage
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class AppsState(
    val email: String? = null,
    val apps: List<FlyApp>? = null,
    /** Each organization's estimated usage, by slug, once the apps have loaded. */
    val usage: Load<Map<String, FlyOrgUsage>> = Load.Loading,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

class AppsViewModel(
    private val http: HttpClient,
    private val session: FlySession,
    private val feed: IncidentFeed,
) : ViewModel() {
    private val _state = MutableStateFlow(AppsState())
    val state: StateFlow<AppsState> = _state.asStateFlow()

    /** Fly.io's open incidents, listed above the apps. The banner over every screen keeps them current. */
    val incidents: StateFlow<List<FlyIncident>?> = feed.incidents

    private var load: Job? = null

    init {
        // A new account: drop the old one's apps and load again. Renewed discharges are the same account.
        viewModelScope.launch {
            session.token.map { it?.identity }.distinctUntilChanged().collect { identity ->
                _state.value = AppsState()
                if (identity != null) loadApps()
            }
        }
    }

    /** Loads the apps again, and the incidents above them, as pulling down the list asks. */
    fun refresh() {
        viewModelScope.launch { feed.refresh() }
        loadApps()
    }

    private fun loadApps() {
        if (session.token.value == null) return
        load?.cancel()
        _state.update { it.copy(loading = true, failed = false) }
        load =
            viewModelScope.launch {
                try {
                    val overview = session.authorized { FlyApi(http, it).overview() }
                    _state.update { it.copy(email = overview.email, apps = overview.apps, loading = false, failed = false) }
                    loadUsage(overview.apps)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FlyApiException.Unauthorized) {
                    // Signed out, and the login screen is up.
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, failed = true) }
                }
            }
    }

    /**
     * Estimates every organization's usage at once, each from its own metrics. A failed reload
     * keeps the estimate already shown.
     */
    private fun loadUsage(apps: List<FlyApp>) =
        viewModelScope.loadInto(
            _state,
            session,
            { token ->
                val now = Clock.System.now().epochSeconds
                coroutineScope {
                    apps
                        .groupBy { it.organization.slug }
                        .map { (org, inOrg) -> async { org to FlyUsage(http, token).estimate(org, inOrg.map { it.name }, now) } }
                        .awaitAll()
                        .toMap()
                }
            },
            { it.usage },
        ) { s, v -> s.copy(usage = v) }
}
