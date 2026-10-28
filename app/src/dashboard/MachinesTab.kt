package dev.ggoggam.hangar.app.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.app.ui.checkTone
import dev.ggoggam.hangar.app.ui.machineTone
import dev.ggoggam.hangar.core.FlyCheck
import dev.ggoggam.hangar.core.FlyMachine
import dev.ggoggam.hangar.core.FlyMachineEvent
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The app's machines, each with its actions; [busy] holds the ones an action is under way on. */
internal fun LazyListScope.machines(
    machines: Load<List<FlyMachine>>,
    busy: Map<String, Action>,
    onAction: (FlyMachine, Action) -> Unit,
) {
    section(machines, "machines", placeholders = 3) { list ->
        if (list.isEmpty()) item(key = "machines:empty") { Muted(stringResource(Res.string.machines_empty)) }
        // Grouped by process group the way `fly status` lists them, then by region.
        items(list.sortedWith(compareBy({ it.processGroup }, { it.region }, { it.id })), key = { "machine:${it.id}" }) {
            MachineCard(it, busy[it.id], onAction)
        }
    }
}

@Composable
private fun MachineCard(
    m: FlyMachine,
    busy: Action?,
    onAction: (FlyMachine, Action) -> Unit,
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name.ifEmpty { m.id }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(m.id, m.processGroup, m.releaseVersion?.let { "v$it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(m.state, machineTone(m.state))
            MachineMenu(m, busy, onAction)
        }
        Text(
            listOfNotNull(
                m.region.uppercase().ifEmpty { null },
                m.config?.guest?.size,
                m.config
                    ?.guest
                    ?.memoryMb
                    ?.takeIf { it > 0 }
                    ?.let { "$it MB" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        m.imageRef?.let {
            Text(
                it.short,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (m.hostStatus != "ok") StatusBadge(stringResource(Res.string.machine_host_unreachable), Tone.Error)

        if (m.checks.isNotEmpty()) {
            Label(stringResource(Res.string.machine_checks))
            m.checks.forEach { CheckRow(it) }
        }
        if (m.events.isNotEmpty()) {
            Label(stringResource(Res.string.machine_events))
            m.events.take(3).forEach { EventRow(it) }
        }
        m.updatedAt?.let {
            Text(
                stringResource(Res.string.machine_updated, ago(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A check and its status; tapping it shows what the check last returned. */
@Composable
private fun CheckRow(check: FlyCheck) {
    var open by rememberSaveable(check.name) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().animateContentSize().clickable(enabled = check.output.isNotBlank()) { open = !open },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                check.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusBadge(check.status, checkTone(check.status))
        }
        if (open) {
            Text(
                check.output,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EventRow(event: FlyMachineEvent) {
    val colors = LocalStatusColors.current
    val exit =
        when {
            event.oomKilled -> stringResource(Res.string.machine_oom)
            event.exitCode != null && event.exitCode != 0 -> stringResource(Res.string.machine_exit, event.exitCode!!)
            else -> null
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOf(event.type, event.status).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        exit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(end = 8.dp)) }
        Text(
            ago(Instant.fromEpochMilliseconds(event.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
