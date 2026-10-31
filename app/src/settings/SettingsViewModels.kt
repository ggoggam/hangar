package dev.ggoggam.hangar.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.app.dashboard.Load
import dev.ggoggam.hangar.app.dashboard.loadInto
import dev.ggoggam.hangar.app.dashboard.value
import dev.ggoggam.hangar.core.FlyAccessToken
import dev.ggoggam.hangar.core.FlyAccount
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
import kotlinx.coroutines.launch

data class SettingsState(
    val email: Load<String> = Load.Loading,
    val organizations: Load<List<FlyOrganization>> = Load.Loading,
)

/**
 * The settings tab's account and organizations, `fly auth whoami` and `fly orgs list`, and the
 * other accounts signed in on this device.
 */
class SettingsViewModel(
    private val http: HttpClient,
    private val session: FlySession,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Every account signed in on this device, the current one among them. */
    val accounts: StateFlow<List<FlyAccount>> = session.accounts

    init {
        // The screens start afresh for each account (see App), so this is the account they show.
        val account = session.accounts.value.firstOrNull { it.current }
        val email = viewModelScope.loadInto(_state, session, { FlyApi(http, it).whoami() }, { it.email }) { s, v -> s.copy(email = v) }
        // An account from before accounts had names gets its email once it's known.
        viewModelScope.launch {
            email.join()
            val known = _state.value.email.value
            if (account != null && account.label == null && !account.demo && !known.isNullOrEmpty()) session.setLabel(account.id, known)
        }
        viewModelScope.loadInto(_state, session, { FlyApi(http, it).organizations() }, { it.organizations }) { s, v ->
            s.copy(organizations = v)
        }
    }

    fun switchTo(account: FlyAccount) = session.switchTo(account.id)

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
