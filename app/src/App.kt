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
import androidx.compose.runtime.remember
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
import dev.ggoggam.hangar.app.dashboard.AppDashboardViewModel
import dev.ggoggam.hangar.app.dashboard.CertificateScreen
import dev.ggoggam.hangar.app.dashboard.DashboardTab
import dev.ggoggam.hangar.app.dashboard.MachineScreen
import dev.ggoggam.hangar.app.dashboard.ReleaseScreen
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
import dev.ggoggam.hangar.app.storage.ClusterScreen
import dev.ggoggam.hangar.app.storage.StorageScreen
import dev.ggoggam.hangar.app.watch.BackgroundChecks
import dev.ggoggam.hangar.app.watch.NotificationLink
import dev.ggoggam.hangar.app.watch.NotificationLinks
import dev.ggoggam.hangar.app.watch.WatchSettings
import dev.ggoggam.hangar.app.watch.keepScheduled
import dev.ggoggam.hangar.core.FlyApp
import dev.ggoggam.hangar.core.FlySession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
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
            val session = koinInject<FlySession>()
            val token by session.token.collectAsStateWithLifecycle()
            val watch = koinInject<WatchSettings>()
            val checks = koinInject<BackgroundChecks>()
            // Background checks follow the switch in Settings and the session.
            LaunchedEffect(session, watch, checks) { keepScheduled(session, watch, checks) }
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
                        composable<Route.Storage> {
                            StorageScreen(
                                koinViewModel(),
                                onOpenApp = openApp,
                                onOpenCluster = { nav.navigate(Route.PostgresCluster(it.id, it.name, it.org, it.rawOrg)) },
                                bottomBar = { bar(HomeTab.Data) },
                            )
                        }
                        composable<Route.PostgresCluster> { entry ->
                            val route = entry.toRoute<Route.PostgresCluster>()
                            ClusterScreen(
                                koinViewModel { parametersOf(route.id, route.org, route.rawOrg) },
                                title = route.name,
                                onBack = { nav.popBackStack() },
                                onOpenApp = { name -> nav.navigate(Route.App(name, route.org)) },
                            )
                        }
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
                            AppDashboardScreen(
                                koinViewModel { parametersOf(route.name, route.org) },
                                onBack = { nav.popBackStack() },
                                initialTab = DashboardTab.entries.firstOrNull { it.name == route.tab } ?: DashboardTab.Overview,
                                onMachine = { nav.navigate(Route.Machine(route.name, route.org, it.id)) },
                                onRelease = { nav.navigate(Route.Release(route.name, route.org, it.version)) },
                                onCertificate = { nav.navigate(Route.Certificate(route.name, it)) },
                            )
                        }
                        composable<Route.Machine> { entry ->
                            val route = entry.toRoute<Route.Machine>()
                            MachineScreen(dashboardUnder(nav, entry, route.app, route.org), route.id, onBack = { nav.popBackStack() })
                        }
                        composable<Route.Release> { entry ->
                            val route = entry.toRoute<Route.Release>()
                            ReleaseScreen(
                                dashboardUnder(nav, entry, route.app, route.org),
                                route.version,
                                onBack = { nav.popBackStack() },
                                onMachine = { nav.navigate(Route.Machine(route.app, route.org, it.id)) },
                            )
                        }
                        composable<Route.Certificate> { entry ->
                            val route = entry.toRoute<Route.Certificate>()
                            CertificateScreen(koinViewModel { parametersOf(route.app, route.hostname) }, onBack = { nav.popBackStack() })
                        }
                    }
                }

                // A tapped notification's screen, once the graph above is set up: now, or when the app starts.
                val links = koinInject<NotificationLinks>()
                LaunchedEffect(links, nav) {
                    links.pending.filterNotNull().collect {
                        when (val link = links.take()) {
                            NotificationLink.Apps -> nav.openTab(HomeTab.Apps)
                            is NotificationLink.App -> nav.navigate(Route.App(link.name, link.org, link.tab)) { popUpTo<Route.Apps>() }
                            null -> Unit
                        }
                    }
                }
            }
        }
    }
}

/**
 * The view model of the dashboard under [entry], the one it was opened from, so a machine or
 * release screen starts from what the dashboard loaded and shares its actions. Nothing opened
 * from a dashboard opens another, so the topmost dashboard is that one.
 */
@Composable
private fun dashboardUnder(
    nav: NavController,
    entry: NavBackStackEntry,
    app: String,
    org: String,
): AppDashboardViewModel {
    val owner = remember(entry) { runCatching { nav.getBackStackEntry<Route.App>() }.getOrDefault(entry) }
    return koinViewModel(viewModelStoreOwner = owner) { parametersOf(app, org) }
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
