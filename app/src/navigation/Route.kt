package dev.ggoggam.hangar.app.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable
    data object Apps : Route

    /** Fly.io's open incidents. */
    @Serializable
    data object Status : Route

    /** Databases and buckets across the user's organizations. */
    @Serializable
    data object Storage : Route

    /** The theme and signing out. */
    @Serializable
    data object Settings : Route

    /** One organization's members and tokens. [name] titles the screen until the organization loads. */
    @Serializable
    data class Organization(
        val slug: String,
        val name: String,
    ) : Route

    /** Fly.io's regions. */
    @Serializable
    data object Regions : Route

    /** One app's dashboard. [org] is its organization's slug, which its metrics live under. */
    @Serializable
    data class App(
        val name: String,
        val org: String,
    ) : Route
}
