package dev.ggoggam.hangar.app.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.dashboard.Muted
import dev.ggoggam.hangar.app.dashboard.Panel
import dev.ggoggam.hangar.app.dashboard.section
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.core.FlyIncident
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The page `fly platform status` opens in the browser. */
private const val STATUS_PAGE_URL = "https://status.fly.io/"

/** Fly.io's open incidents, as `fly incidents list` lists them, and a way out to the full status page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    vm: StatusViewModel,
    bottomBar: @Composable () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Kept current while it's in front, like the app dashboards.
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
            TopAppBar(
                title = { Text(stringResource(Res.string.status_title)) },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                section(state.incidents, "incidents") { incidents ->
                    if (incidents.isEmpty()) {
                        item(key = "incidents:none") { Panel { Muted(stringResource(Res.string.status_none)) } }
                    }
                    items(incidents, key = { "incident:${it.id}" }) { IncidentCard(it) }
                }
                item(key = "page") { StatusPageLink() }
            }
        }
    }
}

@Composable
private fun IncidentCard(incident: FlyIncident) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(incident.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusBadge(incidentLabel(incident.status), if (incident.status == "resolved") Tone.Ok else Tone.Warning)
        }
        if (incident.components.isNotEmpty()) {
            Text(incident.components.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            listOfNotNull(
                incident.startedAt?.let { stringResource(Res.string.status_started, ago(it)) },
                incident.updatedAt?.let { stringResource(Res.string.status_updated, ago(it)) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** status.fly.io in the browser, as `fly platform status` opens it. */
@Composable
private fun StatusPageLink() {
    val uri = LocalUriHandler.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        TextButton(onClick = { uri.openUri(STATUS_PAGE_URL) }) {
            Text(stringResource(Res.string.status_open_page))
            Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
    }
}

/** The label for a Statuspage incident status, else Fly.io's own word. */
@Composable
private fun incidentLabel(status: String): String =
    when (status) {
        "investigating" -> stringResource(Res.string.incident_investigating)
        "identified" -> stringResource(Res.string.incident_identified)
        "monitoring" -> stringResource(Res.string.incident_monitoring)
        "resolved" -> stringResource(Res.string.incident_resolved)
        else -> status
    }
