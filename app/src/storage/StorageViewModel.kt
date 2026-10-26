package dev.ggoggam.hangar.app.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.app.dashboard.Load
import dev.ggoggam.hangar.app.dashboard.loadInto
import dev.ggoggam.hangar.core.FlyAddOn
import dev.ggoggam.hangar.core.FlyApi
import dev.ggoggam.hangar.core.FlyApp
import dev.ggoggam.hangar.core.FlyManagedPostgres
import dev.ggoggam.hangar.core.FlyPostgresCluster
import dev.ggoggam.hangar.core.FlySession
import dev.ggoggam.hangar.core.FlyToken
import io.ktor.client.HttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageState(
    val managedPostgres: Load<List<FlyPostgresCluster>> = Load.Loading,
    val postgresApps: Load<List<FlyApp>> = Load.Loading,
    val redis: Load<List<FlyAddOn>> = Load.Loading,
    val tigris: Load<List<FlyAddOn>> = Load.Loading,
    val refreshing: Boolean = false,
)

/**
 * The databases and buckets across the user's organizations: `fly mpg list`, `fly postgres list`,
 * `fly redis list` and `fly storage list` in one place. Each loads on its own, as the dashboard's
 * sections do.
 */
class StorageViewModel(
    private val http: HttpClient,
    private val session: FlySession,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageState())
    val state: StateFlow<StorageState> = _state.asStateFlow()

    init {
        refresh(quiet = true)
    }

    /** Loads every section again; [quiet] keeps the pull-to-refresh spinner hidden. */
    fun refresh(quiet: Boolean = false) {
        if (session.token.value == null) return
        if (!quiet) _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            listOf(
                load({ managedPostgres(it) }, { it.managedPostgres }) { s, v -> s.copy(managedPostgres = v) },
                load({ FlyApi(http, it).postgresApps() }, { it.postgresApps }) { s, v -> s.copy(postgresApps = v) },
                load({ FlyApi(http, it).addOns("upstash_redis") }, { it.redis }) { s, v -> s.copy(redis = v) },
                load({ FlyApi(http, it).addOns("tigris") }, { it.tigris }) { s, v -> s.copy(tigris = v) },
            ).forEach { it.join() }
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** `fly mpg list` for every organization, since it lists one at a time. */
    private suspend fun managedPostgres(token: FlyToken): List<FlyPostgresCluster> =
        coroutineScope {
            val postgres = FlyManagedPostgres(http, token)
            FlyApi(http, token)
                .organizations()
                .map { org -> async { postgres.clusters(org) } }
                .awaitAll()
                .flatten()
        }

    private fun <T> load(
        fetch: suspend (FlyToken) -> T,
        get: (StorageState) -> Load<T>,
        set: (StorageState, Load<T>) -> StorageState,
    ): Job = viewModelScope.loadInto(_state, session, fetch, get, set)
}
