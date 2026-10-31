package dev.ggoggam.hangar.app.storage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.dashboard.Load
import dev.ggoggam.hangar.app.dashboard.Muted
import dev.ggoggam.hangar.app.dashboard.Panel
import dev.ggoggam.hangar.app.dashboard.SectionTitle
import dev.ggoggam.hangar.app.dashboard.section
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.appStatusLabel
import dev.ggoggam.hangar.app.ui.appStatusTone
import dev.ggoggam.hangar.core.FlyAddOn
import dev.ggoggam.hangar.core.FlyApp
import dev.ggoggam.hangar.core.FlyPostgresCluster
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Managed Postgres, Postgres apps, Upstash Redis and Tigris buckets across the user's organizations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    vm: StorageViewModel,
    onOpenApp: (FlyApp) -> Unit,
    onOpenCluster: (FlyPostgresCluster) -> Unit,
    onOpenBucket: (FlyAddOn) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.data_title)) },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                titled(Res.string.data_mpg, state.managedPostgres, "mpg", key = { it.id }) { ClusterCard(it) { onOpenCluster(it) } }
                titled(Res.string.data_postgres, state.postgresApps, "pg", key = { it.name }) { PostgresAppRow(it) { onOpenApp(it) } }
                titled(Res.string.data_redis, state.redis, "redis", key = { it.id.ifEmpty { it.name } }) { RedisCard(it) }
                titled(
                    Res.string.data_tigris,
                    state.tigris,
                    "tigris",
                    key = { it.id.ifEmpty { it.name } },
                ) { BucketCard(it) { onOpenBucket(it) } }
            }
        }
    }
}

/**
 * A section with its title always shown, so an empty or failed one still says what it's for,
 * and one card per item.
 */
private fun <T> LazyListScope.titled(
    title: StringResource,
    load: Load<List<T>>,
    name: String,
    key: (T) -> String,
    card: @Composable (T) -> Unit,
) {
    item(key = "$name:title") { SectionTitle(stringResource(title)) }
    section(load, name) { list ->
        if (list.isEmpty()) item(key = "$name:empty") { Muted(stringResource(Res.string.data_none)) }
        items(list, key = { "$name:${key(it)}" }) { card(it) }
    }
}

/** A Managed Postgres cluster, as a row of `fly mpg list`: it opens the cluster's page. */
@Composable
private fun ClusterCard(
    cluster: FlyPostgresCluster,
    onClick: () -> Unit,
) {
    Panel(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cluster.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            StatusBadge(cluster.status, clusterTone(cluster.status))
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Detail(
            listOfNotNull(
                cluster.region,
                cluster.plan,
                cluster.diskGb?.let { stringResource(Res.string.data_disk, it) },
                cluster.org,
            ),
        )
        Detail(
            listOf(
                if (cluster.attachedApps.isEmpty()) {
                    stringResource(Res.string.data_unattached)
                } else {
                    stringResource(Res.string.data_attached, cluster.attachedApps.joinToString(", "))
                },
            ),
        )
    }
}

/** A Managed Postgres cluster's status: `ready`, `failed` or `error`, or on its way to one. */
internal fun clusterTone(status: String) =
    when (status) {
        "ready" -> Tone.Ok
        "failed", "error" -> Tone.Error
        else -> Tone.Warning
    }

/** An unmanaged Postgres cluster, which is an app of its own: it opens that app's dashboard. */
@Composable
private fun PostgresAppRow(
    app: FlyApp,
    onClick: () -> Unit,
) {
    Panel(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(app.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            StatusBadge(appStatusLabel(app.status), appStatusTone(app.status))
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Detail(listOf(app.organization.slug))
    }
}

/** An Upstash Redis database, as a row of `fly redis list`. */
@Composable
private fun RedisCard(db: FlyAddOn) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(db.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (db.eviction) StatusBadge(stringResource(Res.string.data_eviction), Tone.Neutral)
            if (db.prodPack) StatusBadge(stringResource(Res.string.data_prod_pack), Tone.Neutral)
        }
        Detail(listOfNotNull(db.primaryRegion, db.planName, db.organization?.slug))
        if (db.readRegions.isNotEmpty()) Detail(listOf(stringResource(Res.string.data_read_regions, db.readRegions.joinToString(", "))))
    }
}

/** A Tigris bucket, as a row of `fly storage list`: it opens the bucket's page. */
@Composable
private fun BucketCard(
    bucket: FlyAddOn,
    onClick: () -> Unit,
) {
    Panel(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(bucket.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (bucket.public) StatusBadge(stringResource(Res.string.bucket_public), Tone.Neutral)
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Detail(listOfNotNull(bucket.organization?.slug))
    }
}

/** A muted line of [parts] joined with dots, skipping blank ones. */
@Composable
private fun Detail(parts: List<String>) {
    val text = parts.filter { it.isNotBlank() }.joinToString(" · ")
    if (text.isNotEmpty()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
