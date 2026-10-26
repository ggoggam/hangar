package dev.ggoggam.hangar.app.di

import dev.ggoggam.hangar.app.apps.AppsViewModel
import dev.ggoggam.hangar.app.dashboard.AppDashboardViewModel
import dev.ggoggam.hangar.app.login.LoginViewModel
import dev.ggoggam.hangar.app.session.Session
import dev.ggoggam.hangar.core.FlyDischarges
import dev.ggoggam.hangar.core.FlyLogin
import dev.ggoggam.hangar.core.flyHttpClient
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
        single { Session(get(), get()) }
        viewModel { LoginViewModel(get(), get(), get(), get(), get(SESSION_NAME)) }
        viewModel { AppsViewModel(get(), get()) }
        viewModel { (app: String, org: String) -> AppDashboardViewModel(app, org, get(), get()) }
    }

/**
 * Starts dependency injection with the platform's services in [platform]: a
 * [dev.ggoggam.hangar.app.session.TokenStore] and the [SESSION_NAME] string. Called once by each
 * platform's entry point before the first composition; later calls do nothing.
 */
fun initHangar(platform: Module) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    startKoin { modules(appModule, platform) }
}
