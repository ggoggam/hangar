package dev.ggoggam.hangar.app.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable
    data object Apps : Route

    /** Databases and buckets across the user's organizations. */
    @Serializable
    data object Storage : Route

    /** The theme and signing out. */
    @Serializable
    data object Settings : Route

    /** Signing in to another account, opened from Settings. */
    @Serializable
    data object AddAccount : Route

    /** One organization's members and tokens. [name] titles the screen until the organization loads. */
    @Serializable
    data class Organization(
        val slug: String,
        val name: String,
    ) : Route

    /**
     * One Managed Postgres cluster. [org] is its organization's slug as flyctl shows it, which its
     * attached apps' dashboards take, and [rawOrg] the real one, which the Managed Postgres APIs
     * take. [name] titles the screen.
     */
    @Serializable
    data class PostgresCluster(
        val id: String,
        val name: String,
        val org: String,
        val rawOrg: String,
    ) : Route

    /** Fly.io's regions. */
    @Serializable
    data object Regions : Route

    /**
     * One app's dashboard. [org] is its organization's slug, which its metrics live under; [tab] is
     * the [dev.ggoggam.hangar.app.dashboard.DashboardTab] it opens on, by name, for a notification's link.
     */
    @Serializable
    data class App(
        val name: String,
        val org: String,
        val tab: String? = null,
    ) : Route

    /** One of [app]'s machines, opened from its dashboard, which it shares. */
    @Serializable
    data class Machine(
        val app: String,
        val org: String,
        val id: String,
    ) : Route

    /** One of [app]'s releases, opened from its dashboard, which it shares. */
    @Serializable
    data class Release(
        val app: String,
        val org: String,
        val version: Int,
    ) : Route

    /** One of [app]'s hostnames and its certificates, opened from the dashboard's Overview. */
    @Serializable
    data class Certificate(
        val app: String,
        val hostname: String,
    ) : Route
}
