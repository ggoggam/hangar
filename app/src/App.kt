package dev.ggoggam.hangar.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.ggoggam.hangar.app.apps.AppsScreen
import dev.ggoggam.hangar.app.dashboard.AppDashboardScreen
import dev.ggoggam.hangar.app.login.LoginScreen
import dev.ggoggam.hangar.app.navigation.Route
import dev.ggoggam.hangar.app.session.Session
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The app's root. Call [dev.ggoggam.hangar.app.di.initHangar] before the first composition. */
@Composable
fun App() {
    HangarTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val token by koinInject<Session>().token.collectAsStateWithLifecycle()
            if (token == null) {
                LoginScreen(koinViewModel())
            } else {
                // Keyed on the token, so signing in again starts from the app list with fresh screens.
                val nav = rememberNavController()
                NavHost(nav, startDestination = Route.Apps) {
                    composable<Route.Apps> {
                        AppsScreen(koinViewModel(), onOpen = { app -> nav.navigate(Route.App(app.name, app.organization.slug)) })
                    }
                    composable<Route.App> { entry ->
                        val route = entry.toRoute<Route.App>()
                        AppDashboardScreen(koinViewModel { parametersOf(route.name, route.org) }, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
