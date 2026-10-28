package dev.ggoggam.hangar.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.ggoggam.hangar.app.apps.AppsScreen
import dev.ggoggam.hangar.app.dashboard.AppDashboardScreen
import dev.ggoggam.hangar.app.login.LoginScreen
import dev.ggoggam.hangar.app.navigation.HomeBar
import dev.ggoggam.hangar.app.navigation.HomeTab
import dev.ggoggam.hangar.app.navigation.Route
import dev.ggoggam.hangar.app.settings.OrganizationScreen
import dev.ggoggam.hangar.app.settings.RegionsScreen
import dev.ggoggam.hangar.app.settings.SettingsScreen
import dev.ggoggam.hangar.app.settings.SystemBars
import dev.ggoggam.hangar.app.settings.ThemeSettings
import dev.ggoggam.hangar.app.status.IncidentBanner
import dev.ggoggam.hangar.app.status.IncidentFeed
import dev.ggoggam.hangar.app.storage.StorageScreen
import dev.ggoggam.hangar.core.FlyApp
import dev.ggoggam.hangar.core.FlySession
import kotlinx.coroutines.delay
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
                val feed = koinInject<IncidentFeed>()
                val incidents by feed.incidents.collectAsStateWithLifecycle()
                val dismissed by feed.dismissed.collectAsStateWithLifecycle()
                val lifecycle = LocalLifecycleOwner.current.lifecycle

                // Kept current while the app is in front, whichever screen is open.
                LaunchedEffect(feed, lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            feed.refresh()
                            delay(60_000)
                        }
                    }
                }

                // Every open incident, until they're dismissed and none has opened since.
                val banner = incidents.orEmpty().takeIf { open -> open.any { it.id !in dismissed } }
                Column(Modifier.fillMaxSize()) {
                    banner?.let { IncidentBanner(it, onOpen = { nav.openTab(HomeTab.Apps) }, onDismiss = feed::dismiss) }
                    // Screens slide sideways: forward pushes in from the end, back slides them out again.
                    // Switching between the bottom bar's screens fades instead, since neither is on top.
                    // Under the banner, which takes the status bar's space, so the screens' top bars don't.
                    NavHost(
                        nav,
                        startDestination = Route.Apps,
                        modifier =
                            Modifier.weight(1f).consumeWindowInsets(
                                if (banner !=
                                    null
                                ) {
                                    WindowInsets.statusBars
                                } else {
                                    WindowInsets(0.dp)
                                },
                            ),
                        enterTransition = { if (isTabSwitch()) fadeIn(Fade) else slideIntoContainer(SlideDirection.Start, Slide) },
                        exitTransition = { if (isTabSwitch()) fadeOut(Fade) else slideOutOfContainer(SlideDirection.Start, Slide) },
                        popEnterTransition = { if (isTabSwitch()) fadeIn(Fade) else slideIntoContainer(SlideDirection.End, Slide) },
                        popExitTransition = { if (isTabSwitch()) fadeOut(Fade) else slideOutOfContainer(SlideDirection.End, Slide) },
                    ) {
                        val openApp = { app: FlyApp -> nav.navigate(Route.App(app.name, app.organization.slug)) }
                        val bar = @Composable { current: HomeTab -> HomeBar(current) { nav.openTab(it) } }
                        composable<Route.Apps> {
                            AppsScreen(koinViewModel(), onOpen = openApp, bottomBar = { bar(HomeTab.Apps) })
                        }
                        composable<Route.Storage> { StorageScreen(koinViewModel(), onOpenApp = openApp, bottomBar = { bar(HomeTab.Data) }) }
                        composable<Route.Settings> {
                            SettingsScreen(
                                koinViewModel(),
                                onOrganization = { org -> nav.navigate(Route.Organization(org.slug, org.name ?: org.slug)) },
                                onRegions = { nav.navigate(Route.Regions) },
                                bottomBar = { bar(HomeTab.Settings) },
                            )
                        }
                        composable<Route.Organization> { entry ->
                            val route = entry.toRoute<Route.Organization>()
                            OrganizationScreen(
                                koinViewModel { parametersOf(route.slug) },
                                title = route.name,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable<Route.Regions> { RegionsScreen(koinViewModel(), onBack = { nav.popBackStack() }) }
                        composable<Route.App> { entry ->
                            val route = entry.toRoute<Route.App>()
                            AppDashboardScreen(koinViewModel { parametersOf(route.name, route.org) }, onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

private val Slide = tween<IntOffset>(300, easing = FastOutSlowInEasing)

private val Fade = tween<Float>(200)

/**
 * Switches to [tab] the way bottom bars do: the app list stays at the bottom of the stack, so back
 * from another tab returns to it, and each tab comes back as it was left rather than loading again.
 */
private fun NavController.openTab(tab: HomeTab) =
    navigate(tab.route) {
        popUpTo<Route.Apps> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() = initialState.isTab() && targetState.isTab()

private fun NavBackStackEntry.isTab() = HomeTab.entries.any { destination.hasRoute(it.route::class) }
