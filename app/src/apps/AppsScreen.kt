package dev.ggoggam.hangar.app.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.appStatusLabel
import dev.ggoggam.hangar.app.ui.appStatusTone
import dev.ggoggam.hangar.core.FlyApp
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    vm: AppsViewModel,
    onOpen: (FlyApp) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.apps_title))
                        state.email?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = { TextButton(onClick = vm::signOut) { Text(stringResource(Res.string.apps_sign_out)) } },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val apps = state.apps
            when {
                state.failed && apps == null -> {
                    Message(stringResource(Res.string.apps_failed)) {
                        Button(onClick = vm::refresh) { Text(stringResource(Res.string.apps_retry)) }
                    }
                }

                apps == null -> {}

                apps.isEmpty() -> {
                    Message(stringResource(Res.string.apps_empty))
                }

                else -> {
                    AppList(apps, onOpen)
                }
            }
        }
    }
}

@Composable
private fun AppList(
    apps: List<FlyApp>,
    onOpen: (FlyApp) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        apps.groupBy { it.organization.slug }.forEach { (org, inOrg) ->
            item(key = "org:$org") {
                Text(
                    org,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                )
            }
            items(inOrg, key = { "app:${it.name}" }) { app ->
                AppRow(app, onClick = { onOpen(app) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun AppRow(
    app: FlyApp,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(app.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        StatusBadge(appStatusLabel(app.status), appStatusTone(app.status))
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(18.dp),
        )
    }
}

@Composable
private fun Message(
    text: String,
    action: (@Composable () -> Unit)? = null,
) {
    // In a scrollable box so pull-to-refresh still works on an empty or failed list.
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    action?.invoke()
                }
            }
        }
    }
}
