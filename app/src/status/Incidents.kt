package dev.ggoggam.hangar.app.status

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.dashboard.Panel
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.core.FlyIncident
import org.jetbrains.compose.resources.stringResource

/** The page `fly platform status` opens in the browser. */
private const val STATUS_PAGE_URL = "https://status.fly.io/"

/**
 * Fly.io's open incidents at the top of a list, as `fly incidents list` lists them, with a way out to
 * the full status page. Nothing until they've loaded, and a single quiet line while there are none.
 */
internal fun LazyListScope.incidentList(incidents: List<FlyIncident>?) {
    when {
        incidents == null -> {}

        incidents.isEmpty() -> {
            item(key = "incidents:none") { NoIncidents() }
        }

        else -> {
            items(incidents, key = { "incident:${it.id}" }) { IncidentCard(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
            item(key = "incidents:page") { StatusPageLink() }
        }
    }
}

/**
 * A strip over every screen while Fly.io has an incident open, its names scrolling past, as flyctl
 * warns of them before a command. Tapping it opens the apps, where the incidents are listed.
 */
@Composable
fun IncidentBanner(
    incidents: List<FlyIncident>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val warning = LocalStatusColors.current.warning
    val text = incidents.map { stringResource(Res.string.incident_banner, it.name, incidentLabel(it.status)) }.joinToString("     ·     ")
    Row(
        Modifier
            .fillMaxWidth()
            .background(LocalStatusColors.current.warningTint)
            .windowInsetsPadding(WindowInsets.statusBars)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Lucide.TriangleAlert, contentDescription = null, tint = warning, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = warning,
            maxLines = 1,
            // Scrolls only when it doesn't fit, and keeps going until it's dismissed.
            modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Lucide.X,
                contentDescription = stringResource(Res.string.incident_dismiss),
                tint = warning,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun IncidentCard(
    incident: FlyIncident,
    modifier: Modifier = Modifier,
) {
    Panel(modifier) {
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

/** All clear, in one line that still opens status.fly.io. */
@Composable
private fun NoIncidents() {
    val uri = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { uri.openUri(STATUS_PAGE_URL) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.CircleCheck, contentDescription = null, tint = LocalStatusColors.current.ok, modifier = Modifier.size(16.dp))
        Text(
            stringResource(Res.string.status_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Lucide.ExternalLink,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** status.fly.io in the browser, as `fly platform status` opens it. */
@Composable
private fun StatusPageLink() {
    val uri = LocalUriHandler.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp), contentAlignment = Alignment.CenterEnd) {
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
