package dev.ggoggam.hangar.app.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.Sparkline
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.app.ui.bytes
import dev.ggoggam.hangar.app.ui.decimal
import dev.ggoggam.hangar.app.ui.millis
import dev.ggoggam.hangar.core.FlyAppDetail
import dev.ggoggam.hangar.core.FlyAppMetrics
import dev.ggoggam.hangar.core.FlyCertificate
import dev.ggoggam.hangar.core.FlyHostIssue
import dev.ggoggam.hangar.core.FlyIp
import dev.ggoggam.hangar.core.FlyIpType
import dev.ggoggam.hangar.core.FlyMachine
import dev.ggoggam.hangar.core.FlyPoint
import dev.ggoggam.hangar.core.FlyVolume
import dev.ggoggam.hangar.core.grafanaUrl
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.overview(
    state: DashboardState,
    onWindow: (MetricsWindow) -> Unit,
    onRestartApp: (rolling: Boolean) -> Unit,
) {
    // Only when there are some: a quiet app shouldn't carry an empty warning, or a failed one.
    state.hostIssues.value?.let { issues -> items(issues, key = { "host:${it.internalId}" }) { HostIssueCard(it) } }
    section(state.app, "app") { app ->
        item(key = "app") { AppCard(app, state.machines.value) }
        state.machines.value?.let { machines ->
            item(key = "app:restart") {
                AppRestartControl(app.name, machines.count { it.state == "started" }, state.appRestart, onRestartApp)
            }
        }
    }

    item(key = "metrics:title") {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.metrics_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            WindowPicker(state.window, onWindow)
        }
    }
    section(state.metrics, "metrics") { metrics -> item(key = "metrics") { MetricsGrid(metrics) } }
    state.app.value
        ?.let(::grafanaUrl)
        ?.let { url -> item(key = "metrics:grafana") { GrafanaLink(url) } }

    section(state.volumes, "volumes") { volumes ->
        if (volumes.isNotEmpty()) {
            item(key = "volumes:title") { SectionTitle(stringResource(Res.string.volumes_title)) }
            items(volumes, key = { "volume:${it.id}" }) { VolumeRow(it) }
        }
    }
    section(state.ips, "ips") { ips ->
        if (ips.isNotEmpty()) {
            item(key = "ips:title") { SectionTitle(stringResource(Res.string.ips_title)) }
            item(key = "ips") { Panel { ips.forEach { IpRow(it) } } }
        }
    }
    section(state.certificates, "certs") { certs ->
        if (certs.isNotEmpty()) {
            item(key = "certs:title") { SectionTitle(stringResource(Res.string.certs_title)) }
            item(key = "certs") { Panel { certs.forEach { CertRow(it) } } }
        }
    }
}

/** A bordered box, the dashboard's card. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A host problem under some of the app's machines, which `fly incidents hosts list` shows. */
@Composable
private fun HostIssueCard(issue: FlyHostIssue) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.TriangleAlert, contentDescription = null, tint = LocalStatusColors.current.warning, modifier = Modifier.size(18.dp))
            StatusBadge(stringResource(Res.string.host_issue), Tone.Warning)
        }
        Text(issue.message, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(Res.string.host_issue_updated, ago(issue.updatedAt ?: issue.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The app's board on fly-metrics.net, opened in the browser as `fly dashboard metrics --grafana` does. */
@Composable
private fun GrafanaLink(url: String) {
    val uri = LocalUriHandler.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        TextButton(onClick = { uri.openUri(url) }) {
            Text(stringResource(Res.string.metrics_grafana))
            Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
    }
}

@Composable
private fun AppCard(
    app: FlyAppDetail,
    machines: List<FlyMachine>?,
) {
    Panel {
        app.hostname?.let { Field(stringResource(Res.string.overview_hostname), it, selectable = true) }
        Field(stringResource(Res.string.overview_org), app.organization.slug)
        app.platformVersion?.let { Field(stringResource(Res.string.overview_platform), it) }
        app.currentRelease?.let { release ->
            Field(
                stringResource(Res.string.overview_release),
                stringResource(Res.string.overview_release_value, release.version, ago(release.createdAt)),
            )
        }
        if (machines != null) {
            val running = machines.count { it.state == "started" }
            val stopped = machines.count { it.state == "stopped" || it.state == "suspended" }
            val checks = machines.flatMap { it.checks }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    stringResource(Res.string.overview_machines_summary, running, stopped),
                    if (running >
                        0
                    ) {
                        Tone.Ok
                    } else {
                        Tone.Neutral
                    },
                )
                if (checks.isEmpty()) {
                    StatusBadge(stringResource(Res.string.overview_no_checks), Tone.Neutral)
                } else {
                    val passing = checks.count { it.status == "passing" }
                    StatusBadge(
                        stringResource(Res.string.overview_checks_summary, passing, checks.size),
                        when {
                            passing == checks.size -> Tone.Ok
                            checks.any { it.status == "critical" } -> Tone.Error
                            else -> Tone.Warning
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    selectable: Boolean = false,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        val text = @Composable { Text(value, style = MaterialTheme.typography.bodyMedium) }
        Box(Modifier.weight(0.62f)) { if (selectable) SelectionContainer { text() } else text() }
    }
}

@Composable
private fun WindowPicker(
    window: MetricsWindow,
    onWindow: (MetricsWindow) -> Unit,
) {
    val labels = listOf(Res.string.metrics_window_hour, Res.string.metrics_window_six_hours, Res.string.metrics_window_day)
    SingleChoiceSegmentedButtonRow {
        MetricsWindow.entries.forEachIndexed { i, w ->
            SegmentedButton(
                selected = w == window,
                onClick = { onWindow(w) },
                shape = SegmentedButtonDefaults.itemShape(i, MetricsWindow.entries.size),
                icon = {},
                label = { Text(stringResource(labels[i]), style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun MetricsGrid(m: FlyAppMetrics) {
    val noData = stringResource(Res.string.metrics_no_data)
    val noTraffic = stringResource(Res.string.metrics_no_traffic)
    val total = m.memoryTotalBytes.lastOrNull()?.value
    val errors = m.errorsPerMinute.lastOrNull()?.value ?: 0.0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(
                stringResource(Res.string.metrics_cpu),
                m.cpuPercent.lastOrNull()?.let { "${decimal(it.value)}%" },
                noData,
                null,
                m.cpuPercent,
                Modifier.weight(1f),
            )
            Metric(
                stringResource(Res.string.metrics_memory),
                m.memoryBytes.lastOrNull()?.let { bytes(it.value) },
                noData,
                total?.let { stringResource(Res.string.metrics_memory_of, bytes(it)) },
                m.memoryBytes,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(
                stringResource(Res.string.metrics_requests),
                m.requestsPerMinute.lastOrNull()?.let { "${decimal(it.value)}/min" },
                noTraffic,
                if (errors > 0) stringResource(Res.string.metrics_errors, decimal(errors)) else null,
                m.requestsPerMinute,
                Modifier.weight(1f),
                alarm = errors > 0,
            )
            Metric(
                stringResource(Res.string.metrics_latency),
                m.p95Millis.lastOrNull()?.let { millis(it.value) },
                noTraffic,
                null,
                m.p95Millis,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String?,
    empty: String,
    detail: String?,
    points: List<FlyPoint>,
    modifier: Modifier,
    alarm: Boolean = false,
) {
    Panel(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Muted when there's no figure, so "No traffic" doesn't look like one. Tabular figures keep
        // the digits from shifting as the numbers refresh.
        Text(
            value ?: empty,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            maxLines = 1,
        )
        Text(
            detail.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (alarm) LocalStatusColors.current.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Sparkline(points, Modifier.fillMaxWidth().height(36.dp))
    }
}

@Composable
private fun VolumeRow(volume: FlyVolume) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(volume.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            StatusBadge(volume.state, if (volume.state == "created") Tone.Ok else Tone.Warning)
        }
        Text(
            stringResource(
                Res.string.volume_detail,
                volume.region,
                volume.sizeGb,
                volume.attachedMachineId ?: stringResource(Res.string.volume_unattached),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val used = volume.bytesUsed
        val total = volume.bytesTotal
        if (used != null && total != null && total > 0) {
            val fraction = (used.toFloat() / total).coerceIn(0f, 1f)
            val colors = LocalStatusColors.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(
                            if (fraction >
                                0.9f
                            ) {
                                colors.error
                            } else if (fraction > 0.75f) {
                                colors.warning
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ),
                )
            }
            Text(
                "${bytes(used.toDouble())} / ${bytes(total.toDouble())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IpRow(ip: FlyIp) {
    val type =
        when (ip.type) {
            FlyIpType.V4 -> Res.string.ip_v4
            FlyIpType.V6 -> Res.string.ip_v6
            FlyIpType.SharedV4 -> Res.string.ip_shared_v4
            FlyIpType.PrivateV6 -> Res.string.ip_private_v6
            FlyIpType.EgressV4, FlyIpType.EgressV6 -> Res.string.ip_egress
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(type),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        SelectionContainer(Modifier.weight(0.62f)) {
            Text(
                ip.ip,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CertRow(cert: FlyCertificate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            cert.hostname,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.padding(4.dp))
        StatusBadge(cert.status, if (cert.status == "Ready") Tone.Ok else Tone.Warning)
    }
}
