package dev.ggoggam.hangar.app.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable
    data object Apps : Route

    /** One app's dashboard. [org] is its organization's slug, which its metrics live under. */
    @Serializable
    data class App(
        val name: String,
        val org: String,
    ) : Route
}
