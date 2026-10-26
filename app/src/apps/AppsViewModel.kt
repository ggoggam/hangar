package dev.ggoggam.hangar.app.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.app.session.Session
import dev.ggoggam.hangar.core.FlyApi
import dev.ggoggam.hangar.core.FlyApiException
import dev.ggoggam.hangar.core.FlyApp
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
    val loading: Boolean = false,
    val failed: Boolean = false,
)

class AppsViewModel(
    private val http: HttpClient,
    private val session: Session,
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
                try {
                    val token = session.fresh() ?: return@launch
                    val overview = FlyApi(http, token).overview()
                    _state.value = AppsState(email = overview.email, apps = overview.apps)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FlyApiException.Unauthorized) {
                    session.signOut()
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, failed = true) }
                }
            }
    }

    fun signOut() = session.signOut()
}
