package dev.ggoggam.hangar.app.di

import dev.ggoggam.hangar.app.apps.AppPins
import dev.ggoggam.hangar.app.apps.AppsViewModel
import dev.ggoggam.hangar.app.dashboard.AppDashboardViewModel
import dev.ggoggam.hangar.app.dashboard.CertificateViewModel
import dev.ggoggam.hangar.app.lock.AppLock
import dev.ggoggam.hangar.app.login.LoginViewModel
import dev.ggoggam.hangar.app.settings.OrganizationViewModel
import dev.ggoggam.hangar.app.settings.RegionsViewModel
import dev.ggoggam.hangar.app.settings.SettingsViewModel
import dev.ggoggam.hangar.app.settings.ThemeSettings
import dev.ggoggam.hangar.app.status.IncidentFeed
import dev.ggoggam.hangar.app.storage.ClusterViewModel
import dev.ggoggam.hangar.app.storage.StorageViewModel
import dev.ggoggam.hangar.app.watch.NotificationLinks
import dev.ggoggam.hangar.app.watch.WatchSettings
import dev.ggoggam.hangar.app.watch.Watcher
import dev.ggoggam.hangar.core.FlyDischarges
import dev.ggoggam.hangar.core.FlyIncidents
import dev.ggoggam.hangar.core.FlyLogin
import dev.ggoggam.hangar.core.FlyOrganization
import dev.ggoggam.hangar.core.FlySession
import dev.ggoggam.hangar.core.flyDemoHttpClient
import dev.ggoggam.hangar.core.flyHttpClient
import dev.ggoggam.hangar.core.isDemo
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/** What the approval page and Fly.io's token list call this login, such as "Hangar on Galaxy A23". */
val SESSION_NAME = named("sessionName")

/** The client that reaches Fly.io, for signing in and renewing tokens, which the demo never does. */
private val LIVE_HTTP = named("liveHttp")

private val DEMO_HTTP = named("demoHttp")

private val appModule =
    module {
        single(LIVE_HTTP) { flyHttpClient() }
        single(DEMO_HTTP) { flyDemoHttpClient() }
        // The screens' client, picked as each screen opens: the demo's while the demo is signed in,
        // so it never reaches the network. Signing in or out starts the screens afresh (see App).
        factory<HttpClient> { if (get<FlySession>().token.value?.isDemo == true) get(DEMO_HTTP) else get(LIVE_HTTP) }
        single { FlyLogin(get(LIVE_HTTP)) }
        single { FlyDischarges(get(LIVE_HTTP)) }
        // Renewals on the main thread, where the screens sign in and out, so the token only changes there.
        single { FlySession(get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
        single { ThemeSettings(get()) }
        // A single, so the lock outlives an Android activity recreated for a rotation.
        single { AppLock(get()) }
        single { WatchSettings(get()) }
        single { AppPins(get()) }
        single { NotificationLinks() }
        // Always the live client: the demo is never checked in the background.
        single { Watcher(get(LIVE_HTTP), get(), get(), get()) }
        // Fetches through the screens' client as each load starts, so the demo's never reaches the network.
        single { IncidentFeed { FlyIncidents(get()).active() } }
        viewModel { LoginViewModel(get(), get(), get(LIVE_HTTP), get(), get(SESSION_NAME)) }
        viewModel { AppsViewModel(get(), get(), get(), get()) }
        viewModel { StorageViewModel(get(), get()) }
        viewModel { (id: String, org: String, rawOrg: String) -> ClusterViewModel(id, FlyOrganization(org, rawOrg), get(), get()) }
        viewModel { SettingsViewModel(get(), get()) }
        viewModel { (slug: String) -> OrganizationViewModel(slug, get(), get()) }
        viewModel { RegionsViewModel(get(), get()) }
        viewModel { (app: String, org: String) -> AppDashboardViewModel(app, org, get(), get()) }
        viewModel { (app: String, hostname: String) -> CertificateViewModel(app, hostname, get(), get()) }
    }

/**
 * Starts dependency injection with the platform's services in [platform]: a
 * [dev.ggoggam.hangar.core.TokenStore], a
 * [dev.ggoggam.hangar.app.settings.SettingsStore], the [SESSION_NAME] string, and the
 * [dev.ggoggam.hangar.app.watch.BackgroundChecks] and [dev.ggoggam.hangar.app.watch.Notifier]. Called once by each
 * platform's entry point before the first composition; later calls do nothing.
 */
fun initHangar(platform: Module) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    startKoin { modules(appModule, platform) }
}
