package dev.ggoggam.hangar.app.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small settings that aren't secret, kept between launches: SharedPreferences or NSUserDefaults. */
interface SettingsStore {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )
}

/** Whether the app is dark or light, or null to follow the system until the user picks one. */
class ThemeSettings(
    private val store: SettingsStore,
) {
    private val _dark = MutableStateFlow(store.read(KEY_DARK)?.toBooleanStrictOrNull())
    val dark: StateFlow<Boolean?> = _dark.asStateFlow()

    fun setDark(dark: Boolean) {
        store.write(KEY_DARK, dark.toString())
        _dark.value = dark
    }

    private companion object {
        const val KEY_DARK = "theme.dark"
    }
}
