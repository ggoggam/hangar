package dev.ggoggam.hangar.app.storage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.dashboard.Field
import dev.ggoggam.hangar.app.dashboard.Muted
import dev.ggoggam.hangar.app.dashboard.Panel
import dev.ggoggam.hangar.app.dashboard.SectionTitle
import dev.ggoggam.hangar.app.dashboard.UsageBar
import dev.ggoggam.hangar.app.dashboard.section
import dev.ggoggam.hangar.app.dashboard.usageTone
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.app.ui.bytes
import dev.ggoggam.hangar.app.ui.percent
import dev.ggoggam.hangar.core.FlyPostgresBackup
import dev.ggoggam.hangar.core.FlyPostgresClusterDetail
import dev.ggoggam.hangar.core.FlyPostgresEndpoint
import dev.ggoggam.hangar.core.FlyPostgresUser
import org.jetbrains.compose.resources.stringResource

/**
 * One Managed Postgres cluster, as `fly mpg status` and Fly.io's dashboard show it: its size,
 * storage, where to connect, the apps attached to it, which open their dashboards, and its
 * databases, users and backups. Hosts and names only, never a password or connection string.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterScreen(
    vm: ClusterViewModel,
    title: String,
    onBack: () -> Unit,
    onOpenApp: (name: String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(Res.string.back)) } },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                section(state.cluster, "cluster") { cluster ->
                    item(key = "cluster") { AboutCard(cluster) }
                    if (cluster.measured || cluster.diskGb != null) {
                        item(key = "storage:title") { SectionTitle(stringResource(Res.string.mpg_storage)) }
                        item(key = "storage") { StorageCard(cluster) }
                    }
                    if (cluster.direct != null || cluster.pooler != null) {
                        item(key = "connection:title") { SectionTitle(stringResource(Res.string.mpg_connection)) }
                        item(key = "connection") { ConnectionCard(cluster.direct, cluster.pooler) }
                    }
                    item(key = "apps:title") { SectionTitle(stringResource(Res.string.mpg_attached)) }
                    if (cluster.attachedApps.isEmpty()) item(key = "apps:none") { Muted(stringResource(Res.string.data_unattached)) }
                    items(cluster.attachedApps, key = { "app:$it" }) { AppRow(it) { onOpenApp(it) } }
                }

                item(key = "databases:title") { SectionTitle(stringResource(Res.string.mpg_databases)) }
                section(state.databases, "databases") { names ->
                    item(key = "databases") {
                        if (names.isEmpty()) {
                            Muted(stringResource(Res.string.mpg_databases_none))
                        } else {
                            Panel {
                                names.forEach {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "users:title") { SectionTitle(stringResource(Res.string.mpg_users)) }
                section(state.users, "users") { users ->
                    item(key = "users") {
                        if (users.isEmpty()) Muted(stringResource(Res.string.mpg_users_none)) else Panel { users.forEach { UserRow(it) } }
                    }
                }

                item(key = "backups:title") { SectionTitle(stringResource(Res.string.mpg_backups)) }
                section(state.backups, "backups") { backups ->
                    item(key = "backups") {
                        // The ten newest, which covers a day or two of them.
                        if (backups.isEmpty()) {
                            Muted(stringResource(Res.string.mpg_backups_none))
                        } else {
                            Panel { backups.take(10).forEach { BackupRow(it) } }
                        }
                    }
                }
            }
        }
    }
}

/** The cluster's id and status, and what `fly mpg status` lists about it. */
@Composable
private fun AboutCard(cluster: FlyPostgresClusterDetail) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    cluster.id,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusBadge(cluster.status, clusterTone(cluster.status))
        }
        Field(stringResource(Res.string.mpg_region), cluster.region)
        // As a machine's size reads on the Machines tab: `shared-cpu-2x · 1 GB`.
        val size = cluster.cpus?.let { if (cluster.cpuKind == "performance") "performance-${it}x" else "shared-cpu-${it}x" }
        val memory = cluster.memoryMb?.let { bytes(it * 1_048_576.0) }
        Field(stringResource(Res.string.mpg_plan), listOfNotNull(cluster.plan.ifEmpty { null }, size, memory).joinToString(" · "))
        cluster.postgresVersion?.let { Field(stringResource(Res.string.mpg_postgres), if (cluster.postGis) "$it · PostGIS" else it) }
        cluster.replicas?.let {
            Field(
                stringResource(Res.string.mpg_replicas),
                if (it > 0) stringResource(Res.string.mpg_replicas_ha, it) else stringResource(Res.string.mpg_replicas_none),
            )
        }
        Field(stringResource(Res.string.overview_org), cluster.org)
        cluster.createdAt?.let { Field(stringResource(Res.string.mpg_created), ago(it)) }
    }
}

/** Whether the Machines API measured how much of the disk is used, which the older list doesn't. */
private val FlyPostgresClusterDetail.measured get() = storageUsedBytes != null && (storageProvisionedBytes ?: 0) > 0

/** How much of the disk is used, or only its size where that wasn't measured. */
@Composable
private fun StorageCard(cluster: FlyPostgresClusterDetail) {
    val used = cluster.storageUsedBytes
    val total = cluster.storageProvisionedBytes
    Panel {
        if (used != null && total != null && total > 0) {
            // As a volume's bar on Overview: amber at 75%, red from where a volume is nearly full.
            val fraction = (used.toDouble() / total).coerceIn(0.0, 1.0)
            val tone = usageTone(fraction)
            UsageBar(fraction, tone)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.mpg_storage_used, bytes(used.toDouble()), bytes(total.toDouble())),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    percent(fraction),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            cluster.diskGb?.let { Text(stringResource(Res.string.mpg_storage_allocated, it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ConnectionCard(
    direct: FlyPostgresEndpoint?,
    pooler: FlyPostgresEndpoint?,
) {
    Panel {
        direct?.let { Field(stringResource(Res.string.mpg_direct), address(it), selectable = true) }
        pooler?.let { Field(stringResource(Res.string.mpg_pooler), address(it), selectable = true) }
        Text(
            stringResource(Res.string.mpg_connection_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `host:port`, with an IPv6 address in brackets, or the host alone without a port. */
private fun address(endpoint: FlyPostgresEndpoint): String {
    val port = endpoint.port ?: return endpoint.host
    return if (':' in endpoint.host) "[${endpoint.host}]:$port" else "${endpoint.host}:$port"
}

/** An attached app, which opens its dashboard. */
@Composable
internal fun AppRow(
    name: String,
    onClick: () -> Unit,
) {
    Panel(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun UserRow(user: FlyPostgresUser) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            user.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        roleLabel(user.role)?.let { StatusBadge(it, Tone.Neutral) }
    }
}

@Composable
private fun roleLabel(role: String): String? =
    when (role) {
        "schema_admin" -> stringResource(Res.string.mpg_role_schema_admin)
        "writer" -> stringResource(Res.string.mpg_role_writer)
        "reader" -> stringResource(Res.string.mpg_role_reader)
        else -> role.ifEmpty { null }
    }

/** A backup's kind, when it started and how big it is, and its status. */
@Composable
private fun BackupRow(backup: FlyPostgresBackup) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when (backup.type) {
                    "full" -> stringResource(Res.string.mpg_backup_full)
                    "incr" -> stringResource(Res.string.mpg_backup_incr)
                    else -> backup.type.ifEmpty { backup.id }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val detail = listOfNotNull(ago(backup.startedAt).ifEmpty { null }, backup.sizeBytes?.let { bytes(it.toDouble()) })
            if (detail.isNotEmpty()) {
                Text(
                    detail.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatusBadge(
            backup.status,
            when (backup.status) {
                "completed" -> Tone.Ok
                "failed", "error" -> Tone.Error
                else -> Tone.Warning
            },
        )
    }
}
