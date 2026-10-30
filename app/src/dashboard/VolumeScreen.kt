package dev.ggoggam.hangar.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.app.ui.bytes
import dev.ggoggam.hangar.app.ui.calendarDate
import dev.ggoggam.hangar.app.ui.dateTime
import dev.ggoggam.hangar.app.ui.dateTimeAgo
import dev.ggoggam.hangar.app.ui.percent
import dev.ggoggam.hangar.core.FlyMachine
import dev.ggoggam.hangar.core.FlyVolume
import dev.ggoggam.hangar.core.FlyVolumeSnapshot
import org.jetbrains.compose.resources.stringResource

/**
 * One volume as `fly volumes show` prints it, with how much of it is used, and its snapshots as
 * `fly volumes snapshots list` lists them, newest first. The volume is what the dashboard has
 * loaded; only the snapshots are read here. Read-only: taking, restoring and deleting snapshots
 * stays with flyctl.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeScreen(
    dashboard: AppDashboardViewModel,
    vm: VolumeViewModel,
    onBack: () -> Unit,
    onMachine: (FlyMachine) -> Unit,
) {
    val state by dashboard.state.collectAsStateWithLifecycle()
    val snapshots by vm.state.collectAsStateWithLifecycle()
    val volume = state.volumes.value?.firstOrNull { it.id == vm.volumeId }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(Res.string.back)) } },
                title = { Text(volume?.name?.ifEmpty { null } ?: vm.volumeId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    volume?.let { StatusBadge(it.state, volumeTone(it.state), Modifier.padding(end = 16.dp)) }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing || snapshots.refreshing,
            onRefresh = {
                dashboard.refresh()
                vm.refresh()
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                section(state.volumes, "volume") {
                    if (volume == null) {
                        item(key = "gone") { Muted(stringResource(Res.string.volume_gone)) }
                    } else {
                        item(key = "detail") { VolumeDetail(volume, state.machines.value, onMachine) }
                        item(key = "snapshots:title") { SectionTitle(stringResource(Res.string.volume_snapshots_title)) }
                        section(snapshots.snapshots, "snapshots", placeholders = 2) { list -> snapshotList(list, volume) }
                    }
                }
            }
        }
    }
}

/** What `fly volumes show` prints, with how full it is and the machine it's attached to, which opens. */
@Composable
private fun VolumeDetail(
    volume: FlyVolume,
    machines: List<FlyMachine>?,
    onMachine: (FlyMachine) -> Unit,
) {
    Panel {
        Field(stringResource(Res.string.machine_id), volume.id, selectable = true, monospace = true)
        if (volume.name.isNotEmpty()) Field(stringResource(Res.string.volume_name), volume.name)
        Field(stringResource(Res.string.machine_region), volume.region)
        if (volume.zone.isNotEmpty()) Field(stringResource(Res.string.volume_zone), volume.zone, monospace = true)
        Field(stringResource(Res.string.machine_size), stringResource(Res.string.volume_size_gb, volume.sizeGb))
        val used = volume.bytesUsed
        val total = volume.bytesTotal
        val usedFraction = volume.usedFraction
        if (used != null && total != null && usedFraction != null) {
            val amount = stringResource(Res.string.volume_used_value, bytes(used.toDouble()), bytes(total.toDouble()))
            Field(stringResource(Res.string.volume_used), "$amount · ${percent(usedFraction)}")
            UsageBar(usedFraction, usageTone(usedFraction))
        }
        val encrypted = if (volume.encrypted) Res.string.volume_yes else Res.string.volume_no
        Field(stringResource(Res.string.volume_encrypted), stringResource(encrypted))
        dateTimeAgo(volume.createdAt).takeIf { it.isNotEmpty() }?.let { Field(stringResource(Res.string.machine_created), it) }
        volume.snapshotRetention?.let {
            Field(stringResource(Res.string.volume_snapshot_retention), stringResource(Res.string.volume_days, it))
        }
        volume.autoBackupEnabled?.let { on ->
            val scheduled = if (on) Res.string.volume_on else Res.string.volume_off
            Field(stringResource(Res.string.volume_scheduled_snapshots), stringResource(scheduled))
        }

        // The machine itself when the dashboard has it, to open; its id alone when it doesn't.
        val id = volume.attachedMachineId
        val machine = id?.let { machines?.firstOrNull { m -> m.id == it } }
        if (machine != null) {
            Muted(stringResource(Res.string.volume_attached), Modifier.padding(top = 4.dp))
            MachineLink(machine, onMachine)
        } else {
            Field(
                stringResource(Res.string.volume_attached),
                id ?: stringResource(Res.string.volume_unattached),
                selectable = id != null,
                monospace = id != null,
            )
        }
    }
}

private fun LazyListScope.snapshotList(
    snapshots: List<FlyVolumeSnapshot>,
    volume: FlyVolume,
) {
    if (snapshots.isEmpty()) {
        // With scheduled snapshots off, say so: that's why there are none.
        val empty = if (volume.autoBackupEnabled == false) Res.string.volume_snapshots_off else Res.string.volume_snapshots_empty
        item(key = "snapshots:empty") { Muted(stringResource(empty)) }
        return
    }
    // flyctl's "Total stored size", under its list.
    item(key = "snapshots:total") {
        Muted(stringResource(Res.string.volume_snapshots_total, snapshots.size, bytes(snapshots.sumOf { it.size }.toDouble())))
    }
    items(snapshots.withIndex().toList(), key = { "snapshot:${it.value.id.ifEmpty { "pending:${it.index}" }}" }) { SnapshotCard(it.value) }
}

/** A row of `fly volumes snapshots list`: when it was taken, its status, sizes, how long it's kept and its id. */
@Composable
private fun SnapshotCard(snapshot: FlyVolumeSnapshot) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                snapshot.createdInstant?.let(::dateTime) ?: snapshot.createdAt.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (snapshot.status.isNotEmpty()) StatusBadge(snapshot.status, snapshotTone(snapshot.status))
        }
        ago(snapshot.createdAt).takeIf { it.isNotEmpty() }?.let { Muted(it) }
        Muted(stringResource(Res.string.volume_snapshot_sizes, bytes(snapshot.size.toDouble()), bytes(snapshot.volumeSize.toDouble())))
        val days = snapshot.retentionDays
        val expires = snapshot.expiresAt
        if (days != null && expires != null) Muted(stringResource(Res.string.volume_snapshot_kept, days, calendarDate(expires)))
        // flyctl shows one still being taken as `(pending)`, before it has an id.
        if (snapshot.id.isEmpty()) {
            Muted(stringResource(Res.string.volume_snapshot_pending))
        } else {
            SelectionContainer {
                Text(
                    snapshot.id,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A volume's state as Overview tones it: `created` is the usual one. */
private fun volumeTone(state: String): Tone = if (state == "created") Tone.Ok else Tone.Warning

/** `created` once a snapshot is done, a failure in red, and anything else while it's being taken. */
private fun snapshotTone(status: String): Tone =
    when (status) {
        "created" -> Tone.Ok
        "failed" -> Tone.Error
        else -> Tone.Warning
    }
