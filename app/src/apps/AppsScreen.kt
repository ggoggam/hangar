package dev.ggoggam.hangar.app.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.composables.icons.lucide.TriangleAlert
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.dashboard.Load
import dev.ggoggam.hangar.app.dashboard.Panel
import dev.ggoggam.hangar.app.dashboard.value
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.ShimmerBar
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.appStatusLabel
import dev.ggoggam.hangar.app.ui.appStatusTone
import dev.ggoggam.hangar.app.ui.bytes
import dev.ggoggam.hangar.app.ui.decimal
import dev.ggoggam.hangar.app.ui.usd
import dev.ggoggam.hangar.core.FlyApp
import dev.ggoggam.hangar.core.FlyAppUsage
import dev.ggoggam.hangar.core.FlyIncident
import dev.ggoggam.hangar.core.FlyOrgUsage
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    vm: AppsViewModel,
    onOpen: (FlyApp) -> Unit,
    onStatus: () -> Unit,
    bottomBar: @Composable () -> Unit,
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
            )
        },
        bottomBar = bottomBar,
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
                    AppList(apps, state.incidents.orEmpty(), state.usage, onOpen, onStatus)
                }
            }
        }
    }
}

@Composable
private fun AppList(
    apps: List<FlyApp>,
    incidents: List<FlyIncident>,
    usage: Load<Map<String, FlyOrgUsage>>,
    onOpen: (FlyApp) -> Unit,
    onStatus: () -> Unit,
) {
    val byOrg = apps.groupBy { it.organization.slug }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (incidents.isNotEmpty()) {
            item(key = "incidents") {
                IncidentsBanner(incidents.size, onStatus)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        // Usage on top, one card per organization since each is billed on its own.
        when (usage) {
            Load.Loading -> {
                item(key = "usage:loading") { UsagePlaceholder() }
            }

            Load.Failed -> {
                item(key = "usage:failed") {
                    Text(
                        stringResource(Res.string.usage_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                }
            }

            is Load.Ready -> {
                byOrg.keys.mapNotNull { usage.value[it] }.forEach { org ->
                    item(key = "usage:${org.org}") { UsageCard(org, showOrg = byOrg.size > 1) }
                }
            }
        }
        byOrg.forEach { (org, inOrg) ->
            item(key = "org:$org") {
                Text(
                    org,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                )
            }
            items(inOrg, key = { "app:${it.name}" }) { app ->
                AppRow(app, usage.value?.get(org)?.app(app.name), onClick = { onOpen(app) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Fly.io's open incidents, as flyctl warns of them before a command; tapping opens the status screen. */
@Composable
private fun IncidentsBanner(
    count: Int,
    onClick: () -> Unit,
) {
    val warning = LocalStatusColors.current.warning
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(LocalStatusColors.current.warningTint)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Lucide.TriangleAlert, contentDescription = null, tint = warning, modifier = Modifier.size(18.dp))
        Text(
            stringResource(Res.string.apps_incidents, count),
            style = MaterialTheme.typography.bodyMedium,
            color = warning,
            modifier = Modifier.weight(1f),
        )
        Icon(Lucide.ChevronRight, contentDescription = null, tint = warning, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AppRow(
    app: FlyApp,
    usage: FlyAppUsage?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.name, style = MaterialTheme.typography.bodyLarge)
            usage?.let { AppUsageLine(it) }
        }
        StatusBadge(appStatusLabel(app.status), appStatusTone(app.status))
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(18.dp),
        )
    }
}

/** An organization's estimated cost and usage over the window, from [FlyOrgUsage]. */
@Composable
private fun UsageCard(
    usage: FlyOrgUsage,
    showOrg: Boolean,
) {
    val days = (usage.windowSeconds / 86_400).toInt()
    Panel(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text(
            if (showOrg) stringResource(Res.string.usage_title_org, days, usage.org) else stringResource(Res.string.usage_title, days),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.usage_estimate, usd(usage.estimatedUsd)),
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
        )
        Text(
            listOfNotNull(
                stringResource(Res.string.usage_machine_hours, decimal(usage.machineSeconds / 3_600)),
                stringResource(Res.string.usage_data_out, bytes(usage.dataOutBytes)),
                usage.volumeGb.takeIf { it > 0 }?.let { stringResource(Res.string.usage_volumes, it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(Res.string.usage_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsagePlaceholder() {
    Panel(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        ShimmerBar(Modifier.fillMaxWidth(0.3f).height(12.dp))
        ShimmerBar(Modifier.fillMaxWidth(0.4f).height(24.dp))
        ShimmerBar(Modifier.fillMaxWidth(0.7f).height(12.dp))
    }
}

/** An app's share: its estimated cost, Machine hours and data out, whichever it has. */
@Composable
private fun AppUsageLine(usage: FlyAppUsage) {
    val parts =
        listOfNotNull(
            stringResource(Res.string.usage_estimate, usd(usage.estimatedUsd)),
            usage.machineSeconds.takeIf { it > 0 }?.let { stringResource(Res.string.usage_hours, decimal(it / 3_600)) },
            usage.dataOutBytes.takeIf { it >= 1024 }?.let { bytes(it) },
        )
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
