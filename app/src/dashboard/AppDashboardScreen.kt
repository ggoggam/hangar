package dev.ggoggam.hangar.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.ShimmerBar
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.appStatusLabel
import dev.ggoggam.hangar.app.ui.appStatusTone
import dev.ggoggam.hangar.core.FlyMachine
import dev.ggoggam.hangar.core.FlyRelease
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class DashboardTab(
    val label: StringResource,
) {
    Overview(Res.string.tab_overview),
    Machines(Res.string.tab_machines),
    Releases(Res.string.tab_releases),
    Secrets(Res.string.tab_secrets),
    Logs(Res.string.tab_logs),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDashboardScreen(
    vm: AppDashboardViewModel,
    onBack: () -> Unit,
    initialTab: DashboardTab = DashboardTab.Overview,
    onMachine: (FlyMachine) -> Unit = {},
    onRelease: (FlyRelease) -> Unit = {},
    onCertificate: (hostname: String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Each action's outcome, one after another.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(noticeText(it)) } }

    // Keep the numbers current while the screen is in front, like a dashboard left open.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(30_000)
                vm.refresh(quiet = true)
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(Res.string.back)) }
                    },
                    title = {
                        Text(vm.appName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        state.app.value?.let {
                            StatusBadge(appStatusLabel(it.status), appStatusTone(it.status), Modifier.padding(end = 16.dp))
                        }
                    },
                )
                // Scrollable so labels keep their width on a narrow phone, and in Korean.
                PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface, edgePadding = 4.dp) {
                    DashboardTab.entries.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t.label), maxLines = 1) })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (DashboardTab.entries[tab]) {
            DashboardTab.Logs -> {
                LogsTab(vm, state.machines.value.orEmpty(), modifier)
            }

            else -> {
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = modifier) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (DashboardTab.entries[tab]) {
                            DashboardTab.Overview -> overview(state, vm::setWindow, vm::restartApp, onCertificate)
                            DashboardTab.Machines -> machines(state.machines, state.busy, vm::act, onMachine)
                            DashboardTab.Releases -> releases(state.releases, onRelease)
                            DashboardTab.Secrets -> secrets(state)
                            DashboardTab.Logs -> Unit
                        }
                    }
                }
            }
        }
    }
}

/**
 * A section's placeholder cards, failure or [content], so every section loads and fails the same
 * way. [placeholders] is how many cards stand in for it while it loads.
 */
internal fun <T> LazyListScope.section(
    load: Load<T>,
    key: String,
    placeholders: Int = 1,
    content: LazyListScope.(T) -> Unit,
) {
    when (load) {
        Load.Loading -> {
            items(placeholders, key = { "$key:loading:$it" }) { PlaceholderCard() }
        }

        Load.Failed -> {
            item(key = "$key:failed") { Muted(stringResource(Res.string.load_failed), Modifier.padding(vertical = 8.dp)) }
        }

        is Load.Ready -> {
            content(load.value)
        }
    }
}

/** A card of shimmering bars, roughly the shape of a title and two lines under it. */
@Composable
internal fun PlaceholderCard() {
    Panel {
        ShimmerBar(Modifier.fillMaxWidth(0.4f).height(18.dp))
        ShimmerBar(Modifier.fillMaxWidth(0.7f).height(12.dp))
        ShimmerBar(Modifier.fillMaxWidth(0.55f).height(12.dp))
    }
}

@Composable
internal fun Muted(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}
