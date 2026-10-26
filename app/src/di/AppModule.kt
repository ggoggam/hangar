package dev.ggoggam.hangar.app.di

import dev.ggoggam.hangar.app.apps.AppsViewModel
import dev.ggoggam.hangar.app.dashboard.AppDashboardViewModel
import dev.ggoggam.hangar.app.login.LoginViewModel
import dev.ggoggam.hangar.app.settings.ThemeSettings
import dev.ggoggam.hangar.core.FlyDischarges
import dev.ggoggam.hangar.core.FlyLogin
import dev.ggoggam.hangar.core.FlySession
import dev.ggoggam.hangar.core.flyHttpClient
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

private val appModule =
    module {
        single { flyHttpClient() }
        single { FlyLogin(get()) }
        single { FlyDischarges(get()) }
        // Renewals on the main thread, where the screens sign in and out, so the token only changes there.
        single { FlySession(get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
        single { ThemeSettings(get()) }
        viewModel { LoginViewModel(get(), get(), get(), get(), get(SESSION_NAME)) }
        viewModel { AppsViewModel(get(), get()) }
        viewModel { (app: String, org: String) -> AppDashboardViewModel(app, org, get(), get()) }
    }

/**
 * Starts dependency injection with the platform's services in [platform]: a
 * [dev.ggoggam.hangar.core.TokenStore], a
 * [dev.ggoggam.hangar.app.settings.SettingsStore] and the [SESSION_NAME] string. Called once by each
 * platform's entry point before the first composition; later calls do nothing.
 */
fun initHangar(platform: Module) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    startKoin { modules(appModule, platform) }
}
