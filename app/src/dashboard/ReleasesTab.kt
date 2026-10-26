package dev.ggoggam.hangar.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.app.ui.releaseTone
import dev.ggoggam.hangar.core.FlyRelease
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.releases(releases: Load<List<FlyRelease>>) {
    section(releases, "releases", placeholders = 3) { list ->
        if (list.isEmpty()) item(key = "releases:empty") { Muted(stringResource(Res.string.releases_empty)) }
        items(list, key = { "release:${it.id.ifEmpty { it.version.toString() }}" }) { ReleaseRow(it) }
    }
}

@Composable
private fun ReleaseRow(release: FlyRelease) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("v${release.version}", style = MaterialTheme.typography.titleMedium)
            Text(
                release.description.ifEmpty { release.reason },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (release.stable) StatusBadge(stringResource(Res.string.release_stable), Tone.Neutral)
            StatusBadge(release.status, releaseTone(release.status))
        }
        Text(
            listOfNotNull(release.user?.email?.ifEmpty { null }, ago(release.createdAt)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (release.imageRef.isNotEmpty()) {
            Text(
                release.imageRef.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
