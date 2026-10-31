package dev.ggoggam.hangar.app.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import dev.ggoggam.hangar.app.ui.BarEdge
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.barShadow
import dev.ggoggam.hangar.app.ui.dateTimeAgo
import dev.ggoggam.hangar.app.ui.machineTone
import dev.ggoggam.hangar.app.ui.releaseTone
import dev.ggoggam.hangar.core.FlyMachine
import dev.ggoggam.hangar.core.FlyRelease
import dev.ggoggam.hangar.core.FlyReleaseChange
import dev.ggoggam.hangar.core.releaseChange
import org.jetbrains.compose.resources.stringResource

/**
 * One release, as a row of `fly releases --image` has it, with what changed since the release
 * before it that went out and the machines running it. All of it is what the dashboard has loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseScreen(
    vm: AppDashboardViewModel,
    version: Int,
    onBack: () -> Unit,
    onMachine: (FlyMachine) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val release = state.releases.value?.firstOrNull { it.version == version }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.barShadow(BarEdge.Bottom),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(Res.string.back)) }
                },
                title = { Text(stringResource(Res.string.release_title, version), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    release?.let { StatusBadge(it.status, releaseTone(it.status), Modifier.padding(end = 16.dp)) }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                section(state.releases, "release") { releases ->
                    if (release == null) {
                        item(key = "gone") { Muted(stringResource(Res.string.release_gone)) }
                    } else {
                        releaseDetail(release, releases, state.machines, onMachine)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.releaseDetail(
    release: FlyRelease,
    releases: List<FlyRelease>,
    machines: Load<List<FlyMachine>>,
    onMachine: (FlyMachine) -> Unit,
) {
    item(key = "summary") {
        Panel {
            if (release.description.isNotEmpty()) Text(release.description, style = MaterialTheme.typography.bodyLarge)
            if (release.stable) StatusBadge(stringResource(Res.string.release_stable), Tone.Neutral)
            if (release.reason.isNotEmpty()) Field(stringResource(Res.string.release_reason), release.reason)
            release.user?.let { user ->
                val name = user.name.orEmpty()
                val by =
                    when {
                        name.isEmpty() -> user.email
                        user.email.isEmpty() -> name
                        else -> "$name (${user.email})"
                    }
                if (by.isNotEmpty()) Field(stringResource(Res.string.release_by), by, selectable = true)
            }
            Field(stringResource(Res.string.release_created), dateTimeAgo(release.createdAt))
            if (release.imageRef.isNotEmpty()) {
                Field(stringResource(Res.string.machine_image), release.imageRef, selectable = true, monospace = true)
            }
        }
    }

    item(key = "changes:title") { SectionTitle(stringResource(Res.string.release_changes)) }
    item(key = "changes") {
        Panel {
            when (val change = releaseChange(release, releases)) {
                is FlyReleaseChange.NewImage -> {
                    Text(stringResource(Res.string.release_new_image, change.previous.version), style = MaterialTheme.typography.bodyMedium)
                    Field("v${change.previous.version}", change.previous.imageRef, selectable = true, monospace = true)
                    Field("v${release.version}", release.imageRef, selectable = true, monospace = true)
                }

                is FlyReleaseChange.SameImage -> {
                    Text(
                        stringResource(Res.string.release_same_image, change.previous.version),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                null -> {
                    Muted(stringResource(Res.string.release_no_previous))
                }
            }
        }
    }

    item(key = "machines:title") { SectionTitle(stringResource(Res.string.release_machines)) }
    section(machines, "machines") { list ->
        val on = list.filter { it.releaseVersion == release.version }
        item(key = "machines") {
            if (on.isEmpty()) {
                Muted(stringResource(Res.string.release_machines_none))
            } else {
                Panel { on.forEach { MachineLink(it, onMachine) } }
            }
        }
    }
}

/** A machine by name, id and region, with its state; it opens the machine's screen. */
@Composable
internal fun MachineLink(
    m: FlyMachine,
    onOpen: (FlyMachine) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name.ifEmpty { m.id }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(m.id, m.region.uppercase().ifEmpty { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusBadge(m.state, machineTone(m.state))
    }
}
