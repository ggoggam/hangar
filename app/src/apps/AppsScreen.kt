package dev.ggoggam.hangar.app.apps

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import dev.ggoggam.hangar.app.dashboard.Load
import dev.ggoggam.hangar.app.dashboard.Panel
import dev.ggoggam.hangar.app.dashboard.value
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.status.incidentList
import dev.ggoggam.hangar.app.ui.BarEdge
import dev.ggoggam.hangar.app.ui.ShimmerBar
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.appStatusLabel
import dev.ggoggam.hangar.app.ui.appStatusTone
import dev.ggoggam.hangar.app.ui.barShadow
import dev.ggoggam.hangar.app.ui.bytes
import dev.ggoggam.hangar.app.ui.decimal
import dev.ggoggam.hangar.app.ui.usd
import dev.ggoggam.hangar.core.FlyApp
import dev.ggoggam.hangar.core.FlyAppUsage
import dev.ggoggam.hangar.core.FlyOrgUsage
import dev.ggoggam.hangar.core.FlyPins
import dev.ggoggam.hangar.core.searchApps
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    vm: AppsViewModel,
    onOpen: (FlyApp) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.barShadow(BarEdge.Bottom),
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
            // One list whatever the apps' state, so the incidents stay on top and pull-to-refresh
            // still works on an empty or failed list.
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                incidentList(incidents)
                when {
                    state.failed && apps == null -> {
                        item(key = "message") {
                            Message(stringResource(Res.string.apps_failed)) {
                                Button(onClick = vm::refresh) { Text(stringResource(Res.string.apps_retry)) }
                            }
                        }
                    }

                    apps == null -> {}

                    apps.isEmpty() -> {
                        item(key = "message") { Message(stringResource(Res.string.apps_empty)) }
                    }

                    else -> {
                        appList(apps, state.usage, pins, query, { query = it }, onOpen, vm::togglePin)
                    }
                }
            }
        }
    }
}

/** Below this many apps there's no search field: the whole list fits on a screen or two. */
private const val SEARCH_FROM = 8

private fun LazyListScope.appList(
    apps: List<FlyApp>,
    usage: Load<Map<String, FlyOrgUsage>>,
    pins: FlyPins,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (FlyApp) -> Unit,
    onPin: (FlyApp) -> Unit,
) {
    val byOrg = apps.groupBy { it.organization.slug }
    val searching = apps.size >= SEARCH_FROM && query.isNotBlank()
    if (apps.size >= SEARCH_FROM) {
        item(key = "search") { SearchField(query, onQuery) }
        if (pins.isEmpty && !searching) {
            item(key = "pin-hint") {
                Text(
                    stringResource(Res.string.apps_pin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
    // Usage on top, one card per organization since each is billed on its own. A search leaves it
    // out, so the matches are right under the field.
    if (!searching) usageCards(usage, byOrg.keys, showOrg = byOrg.size > 1)
    val shown = if (searching) searchApps(apps, query) else apps
    if (shown.isEmpty()) {
        item(key = "message") { Message(stringResource(Res.string.apps_no_matches, query.trim())) }
        return
    }
    // The pinned apps first, whatever their organization, then the rest under theirs.
    val (pinned, rest) = pins.split(shown)
    if (pinned.isNotEmpty()) {
        item(key = "pinned") { GroupHeader(stringResource(Res.string.apps_pinned)) }
        items(pinned, key = { "app:${it.name}" }) { app ->
            val org = app.organization.slug
            AppRow(
                app,
                usage.value?.get(org)?.app(app.name),
                org = org.takeIf { byOrg.size > 1 },
                pinned = true,
                onClick = { onOpen(app) },
                onPin = { onPin(app) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
    rest.groupBy { it.organization.slug }.forEach { (org, inOrg) ->
        item(key = "org:$org") { GroupHeader(org) }
        items(inOrg, key = { "app:${it.name}" }) { app ->
            AppRow(
                app,
                usage.value?.get(org)?.app(app.name),
                org = null,
                pinned = false,
                onClick = { onOpen(app) },
                onPin = { onPin(app) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun LazyListScope.usageCards(
    usage: Load<Map<String, FlyOrgUsage>>,
    orgs: Set<String>,
    showOrg: Boolean,
) {
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
            orgs.mapNotNull { usage.value[it] }.forEach { org ->
                item(key = "usage:${org.org}") { UsageCard(org, showOrg) }
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        query,
        onChange,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text(stringResource(Res.string.apps_search), style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Lucide.Search, contentDescription = null, Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Lucide.X, stringResource(Res.string.apps_search_clear), Modifier.size(18.dp))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

/** One app. Touching and holding it offers to pin it to the top of the list, or to unpin it. */
@Composable
private fun AppRow(
    app: FlyApp,
    usage: FlyAppUsage?,
    /** Its organization, for a pinned app among several organizations'; null to leave it out. */
    org: String?,
    pinned: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val pinLabel = stringResource(if (pinned) Res.string.apps_unpin else Res.string.apps_pin)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = pinLabel,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                ).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.name, style = MaterialTheme.typography.bodyLarge)
                if (org != null || usage != null) AppUsageLine(org, usage)
            }
            StatusBadge(appStatusLabel(app.status), appStatusTone(app.status))
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
            )
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.surface) {
            DropdownMenuItem(
                text = { Text(pinLabel) },
                leadingIcon = {
                    Icon(
                        if (pinned) Lucide.PinOff else Lucide.Pin,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = {
                    menu = false
                    onPin()
                },
            )
        }
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

/** An app's [org], when given, and its share: its estimated cost, Machine hours and data out, whichever it has. */
@Composable
private fun AppUsageLine(
    org: String?,
    usage: FlyAppUsage?,
) {
    val parts =
        listOfNotNull(
            org,
            usage?.let { stringResource(Res.string.usage_estimate, usd(it.estimatedUsd)) },
            usage?.machineSeconds?.takeIf { it > 0 }?.let { stringResource(Res.string.usage_hours, decimal(it / 3_600)) },
            usage?.dataOutBytes?.takeIf { it >= 1024 }?.let { bytes(it) },
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
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}
