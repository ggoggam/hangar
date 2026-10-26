package dev.ggoggam.hangar.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.app.dashboard.Load
import dev.ggoggam.hangar.app.dashboard.loadInto
import dev.ggoggam.hangar.core.FlyAccessToken
import dev.ggoggam.hangar.core.FlyApi
import dev.ggoggam.hangar.core.FlyMachines
import dev.ggoggam.hangar.core.FlyOrganization
import dev.ggoggam.hangar.core.FlyOrganizationDetail
import dev.ggoggam.hangar.core.FlyRegions
import dev.ggoggam.hangar.core.FlySession
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val email: Load<String> = Load.Loading,
    val organizations: Load<List<FlyOrganization>> = Load.Loading,
)

/** The settings tab's account and organizations: `fly auth whoami` and `fly orgs list`. */
class SettingsViewModel(
    private val http: HttpClient,
    private val session: FlySession,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.loadInto(_state, session, { FlyApi(http, it).whoami() }, { it.email }) { s, v -> s.copy(email = v) }
        viewModelScope.loadInto(_state, session, { FlyApi(http, it).organizations() }, { it.organizations }) { s, v ->
            s.copy(organizations = v)
        }
    }

    fun signOut() = session.signOut()
}

data class OrganizationState(
    val detail: Load<FlyOrganizationDetail> = Load.Loading,
    val tokens: Load<List<FlyAccessToken>> = Load.Loading,
)

/** One organization: `fly orgs show` and `fly tokens list --scope org`, read-only. */
class OrganizationViewModel(
    slug: String,
    http: HttpClient,
    session: FlySession,
) : ViewModel() {
    private val _state = MutableStateFlow(OrganizationState())
    val state: StateFlow<OrganizationState> = _state.asStateFlow()

    init {
        viewModelScope.loadInto(_state, session, { FlyApi(http, it).organization(slug) }, { it.detail }) { s, v -> s.copy(detail = v) }
        viewModelScope.loadInto(_state, session, { FlyApi(http, it).organizationTokens(slug) }, { it.tokens }) { s, v ->
            s.copy(tokens = v)
        }
    }
}

/** Fly.io's regions, as `fly platform regions` lists them. */
class RegionsViewModel(
    http: HttpClient,
    session: FlySession,
) : ViewModel() {
    private val _regions = MutableStateFlow<Load<FlyRegions>>(Load.Loading)
    val regions: StateFlow<Load<FlyRegions>> = _regions.asStateFlow()

    init {
        viewModelScope.loadInto(_regions, session, { FlyMachines(http, it).regions() }, { it }) { _, v -> v }
    }
}
