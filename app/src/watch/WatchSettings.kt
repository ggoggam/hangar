package dev.ggoggam.hangar.app.watch

import dev.ggoggam.hangar.app.settings.SettingsStore
import dev.ggoggam.hangar.core.FlyWatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Hangar checks Fly.io in the background, what it notifies about, and the last check's
 * [FlyWatchState], which holds fingerprints rather than names.
 */
class WatchSettings(
    private val store: SettingsStore,
) {
    private val _enabled = MutableStateFlow(store.read(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false)

    /** Off until the user turns it on, which is also when the system asks to allow notifications. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _incidents = MutableStateFlow(store.read(KEY_INCIDENTS)?.toBooleanStrictOrNull() ?: true)

    /** Fly.io incidents opening and resolving. */
    val incidents: StateFlow<Boolean> = _incidents.asStateFlow()

    private val _apps = MutableStateFlow(store.read(KEY_APPS)?.toBooleanStrictOrNull() ?: true)

    /** Failed and crashing machines, failing health checks and host issues. */
    val apps: StateFlow<Boolean> = _apps.asStateFlow()

    fun setEnabled(enabled: Boolean) = set(KEY_ENABLED, _enabled, enabled)

    fun setIncidents(on: Boolean) = set(KEY_INCIDENTS, _incidents, on)

    fun setApps(on: Boolean) = set(KEY_APPS, _apps, on)

    /** The last check's state, or null before the first. */
    fun state(): FlyWatchState? = store.read(KEY_STATE)?.let(FlyWatchState::decode)

    /** Keeps [state] for the next check; null forgets it, so the next one only takes note. */
    fun saveState(state: FlyWatchState?) = store.write(KEY_STATE, state?.encode().orEmpty())

    private fun set(
        key: String,
        flow: MutableStateFlow<Boolean>,
        value: Boolean,
    ) {
        store.write(key, value.toString())
        flow.value = value
    }

    private companion object {
        const val KEY_ENABLED = "watch.enabled"
        const val KEY_INCIDENTS = "watch.incidents"
        const val KEY_APPS = "watch.apps"
        const val KEY_STATE = "watch.state"
    }
}
