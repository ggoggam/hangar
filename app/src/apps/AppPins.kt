package dev.ggoggam.hangar.app.apps

import dev.ggoggam.hangar.app.settings.SettingsStore
import dev.ggoggam.hangar.core.FlyPins
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The apps pinned to the top of the app list, kept between launches as [FlyPins]' fingerprints. */
class AppPins(
    private val store: SettingsStore,
) {
    private val _pins = MutableStateFlow(FlyPins.decode(store.read(KEY_PINS)))
    val pins: StateFlow<FlyPins> = _pins.asStateFlow()

    /** Pins [app], or unpins it if it was pinned. */
    fun toggle(app: String) {
        val pins = _pins.value.toggle(app)
        store.write(KEY_PINS, pins.encode())
        _pins.value = pins
    }

    private companion object {
        const val KEY_PINS = "apps.pinned"
    }
}
