package dev.ggoggam.hangar.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.core.FlyCertificateDetail
import dev.ggoggam.hangar.core.FlyMachines
import dev.ggoggam.hangar.core.FlySession
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CertificateState(
    val detail: Load<FlyCertificateDetail> = Load.Loading,
    val refreshing: Boolean = false,
)

/** One of an app's hostnames: its certificates and the DNS records it needs, as `fly certs show` prints them. */
class CertificateViewModel(
    private val appName: String,
    val hostname: String,
    private val http: HttpClient,
    private val session: FlySession,
) : ViewModel() {
    private val _state = MutableStateFlow(CertificateState())
    val state: StateFlow<CertificateState> = _state.asStateFlow()

    init {
        refresh(quiet = true)
    }

    /** Reads it again; [quiet] keeps the pull-to-refresh spinner hidden. */
    fun refresh(quiet: Boolean = false) {
        if (session.token.value == null) return
        if (!quiet) _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            viewModelScope
                .loadInto(_state, session, { FlyMachines(http, it).certificate(appName, hostname) }, { it.detail }) { s, v ->
                    s.copy(detail = v)
                }.join()
            _state.update { it.copy(refreshing = false) }
        }
    }
}
