package dev.ggoggam.hangar.app

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.ggoggam.hangar.app.apps.AppsScreen
import dev.ggoggam.hangar.app.dashboard.AppDashboardScreen
import dev.ggoggam.hangar.app.login.LoginScreen
import dev.ggoggam.hangar.app.navigation.Route
import dev.ggoggam.hangar.app.settings.SystemBars
import dev.ggoggam.hangar.app.settings.ThemeSettings
import dev.ggoggam.hangar.core.FlySession
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The app's root. Call [dev.ggoggam.hangar.app.di.initHangar] before the first composition. */
@Composable
fun App() {
    val dark by koinInject<ThemeSettings>().dark.collectAsStateWithLifecycle()
    SystemBars(dark)
    HangarTheme(darkTheme = dark ?: isSystemInDarkTheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val token by koinInject<FlySession>().token.collectAsStateWithLifecycle()
            if (token == null) {
                LoginScreen(koinViewModel())
            } else {
                // Keyed on the token, so signing in again starts from the app list with fresh screens.
                val nav = rememberNavController()
                // Screens slide sideways: forward pushes in from the end, back slides them out again.
                NavHost(
                    nav,
                    startDestination = Route.Apps,
                    enterTransition = { slideIntoContainer(SlideDirection.Start, Slide) },
                    exitTransition = { slideOutOfContainer(SlideDirection.Start, Slide) },
                    popEnterTransition = { slideIntoContainer(SlideDirection.End, Slide) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End, Slide) },
                ) {
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

private val Slide = tween<IntOffset>(300, easing = FastOutSlowInEasing)
