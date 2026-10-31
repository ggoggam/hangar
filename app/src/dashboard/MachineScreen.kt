package dev.ggoggam.hangar.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.BarEdge
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.app.ui.barShadow
import dev.ggoggam.hangar.app.ui.checkTone
import dev.ggoggam.hangar.app.ui.dateTime
import dev.ggoggam.hangar.app.ui.dateTimeAgo
import dev.ggoggam.hangar.app.ui.machineTone
import dev.ggoggam.hangar.core.FlyCheck
import dev.ggoggam.hangar.core.FlyMachine
import dev.ggoggam.hangar.core.FlyMachineEvent
import dev.ggoggam.hangar.core.FlyService
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * One machine, as `fly machine status` shows it: what it runs and where, its services, its checks
 * with their output and its recent events, with the same actions as on the Machines tab. It shows
 * what the dashboard's list has of the machine until its own answer arrives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineScreen(
    vm: AppDashboardViewModel,
    id: String,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detail = state.machineDetails[id]
    val machine = detail?.value ?: state.machines.value?.firstOrNull { it.id == id }

    DisposableEffect(vm, id) {
        vm.openMachine(id)
        onDispose { vm.closeMachine(id) }
    }
    // Kept current while it's in front, as the dashboard is.
    LaunchedEffect(lifecycle, id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(30_000)
                vm.openMachine(id)
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(noticeText(it)) } }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.barShadow(BarEdge.Bottom),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(Res.string.back)) }
                },
                title = { Text(machine?.name?.ifEmpty { null } ?: id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    machine?.let {
                        StatusBadge(it.state, machineTone(it.state))
                        MachineMenu(it, state.busy[id], vm::act)
                    }
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    machine != null -> machineDetail(machine)
                    detail is Load.Failed -> item(key = "failed") { Muted(stringResource(Res.string.load_failed)) }
                    else -> item(key = "loading") { PlaceholderCard() }
                }
            }
        }
    }
}

private fun LazyListScope.machineDetail(m: FlyMachine) {
    item(key = "summary") { Summary(m) }
    val mounts = m.config?.mounts.orEmpty()
    if (mounts.isNotEmpty()) {
        item(key = "mounts:title") { SectionTitle(stringResource(Res.string.volumes_title)) }
        item(key = "mounts") {
            Panel {
                mounts.forEach {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it.name.ifEmpty { it.volume }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            listOfNotNull(it.path, it.sizeGb.takeIf { gb -> gb > 0 }?.let { gb -> "$gb GB" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    val services = m.config?.services.orEmpty()
    if (services.isNotEmpty()) {
        item(key = "services:title") { SectionTitle(stringResource(Res.string.machine_services)) }
        services.forEachIndexed { i, service -> item(key = "service:$i") { ServiceCard(service) } }
    }
    if (m.checks.isNotEmpty()) {
        item(key = "checks:title") {
            SectionTitle(stringResource(Res.string.machine_checks_title, m.checks.count { it.status == "passing" }, m.checks.size))
        }
        item(key = "checks") { Panel { m.checks.forEach { CheckDetail(it) } } }
    }
    item(key = "events:title") { SectionTitle(stringResource(Res.string.machine_events)) }
    item(key = "events") {
        if (m.events.isEmpty()) {
            Muted(stringResource(Res.string.machine_no_events))
        } else {
            Panel { m.events.forEach { EventDetail(it) } }
        }
    }
}

/** The table `fly machine status` prints first, without the command and entrypoint, which can hold secrets. */
@Composable
private fun Summary(m: FlyMachine) {
    val guest = m.config?.guest
    Panel {
        if (m.hostStatus != "ok") StatusBadge(stringResource(Res.string.machine_host_unreachable), Tone.Error)
        Field(stringResource(Res.string.machine_id), m.id, selectable = true, monospace = true)
        if (m.region.isNotEmpty()) Field(stringResource(Res.string.machine_region), m.region.uppercase())
        m.processGroup?.let { Field(stringResource(Res.string.machine_process_group), it) }
        m.releaseVersion?.let { Field(stringResource(Res.string.machine_release), "v$it") }
        m.imageRef?.let { image ->
            Field(
                stringResource(Res.string.machine_image),
                image.short + image.version?.let { " ($it)" }.orEmpty(),
                selectable = true,
                monospace = true,
            )
            if (image.digest.isNotEmpty()) {
                Field(
                    stringResource(Res.string.machine_digest),
                    image.digest,
                    selectable = true,
                    monospace = true,
                )
            }
        }
        guest?.let {
            Field(stringResource(Res.string.machine_size), it.size)
            if (it.memoryMb > 0) Field(stringResource(Res.string.machine_memory), "${it.memoryMb} MB")
            if (it.gpus > 0) Field(stringResource(Res.string.machine_gpu), listOfNotNull("${it.gpus}×", it.gpuKind).joinToString(" "))
        }
        if (m.privateIp.isNotEmpty()) Field(stringResource(Res.string.machine_private_ip), m.privateIp, selectable = true, monospace = true)
        m.version.ifEmpty { m.instanceId }.takeIf { it.isNotEmpty() }?.let {
            Field(stringResource(Res.string.machine_version), it, selectable = true, monospace = true)
        }
        m.createdAt?.let { Field(stringResource(Res.string.machine_created), dateTimeAgo(it)) }
        m.updatedAt?.let { Field(stringResource(Res.string.machine_updated_at), dateTimeAgo(it)) }
    }
}

/** A service: its outside ports and their handlers, to its port inside, and when Fly Proxy stops and starts it. */
@Composable
private fun ServiceCard(service: FlyService) {
    Panel {
        Text(
            stringResource(Res.string.machine_service, service.protocol.uppercase(), service.internalPort),
            style = MaterialTheme.typography.bodyLarge,
        )
        service.ports.forEach { port ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(port.label, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                Text(
                    port.handlers.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (port.forceHttps) StatusBadge(stringResource(Res.string.machine_port_https), Tone.Neutral)
            }
        }
        val policy =
            listOfNotNull(
                when (service.autostop) {
                    "off" -> stringResource(Res.string.machine_autostop_off)
                    "stop" -> stringResource(Res.string.machine_autostop_stop)
                    "suspend" -> stringResource(Res.string.machine_autostop_suspend)
                    else -> null
                },
                if (service.autostart == true) stringResource(Res.string.machine_autostart) else null,
                service.minMachinesRunning?.takeIf { it > 0 }?.let { stringResource(Res.string.machine_min_running, it) },
            )
        if (policy.isNotEmpty()) {
            Text(
                policy.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A check with what it last returned, which `fly machine status` prints in full. */
@Composable
private fun CheckDetail(check: FlyCheck) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        check.updatedAt?.let {
            Text(
                stringResource(Res.string.machine_check_updated, ago(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (check.output.isNotBlank()) {
            SelectionContainer {
                Text(
                    check.output.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (check.status == "passing") MaterialTheme.colorScheme.onSurfaceVariant else LocalStatusColors.current.error,
                )
            }
        }
    }
}

/** An event as `fly machine status` lists it, with how a process exited: its code, and whether it ran out of memory. */
@Composable
private fun EventDetail(event: FlyMachineEvent) {
    val at = Instant.fromEpochMilliseconds(event.timestamp)
    val exit =
        listOfNotNull(
            event.exitCode?.let { stringResource(Res.string.machine_exit, it) },
            if (event.oomKilled) stringResource(Res.string.machine_oom) else null,
            if (event.requestedStop) stringResource(Res.string.machine_requested_stop) else null,
        )
    // An exit nobody asked for, or one the kernel forced, is the machine crashing.
    val crashed = event.oomKilled || (event.exitCode.let { it != null && it != 0 } && !event.requestedStop)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(event.type, event.status).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(ago(at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            listOf(dateTime(at), event.source).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (exit.isNotEmpty()) {
            Text(
                exit.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (crashed) LocalStatusColors.current.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
