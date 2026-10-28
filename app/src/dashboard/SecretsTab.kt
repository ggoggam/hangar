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
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.core.FlySecret
import dev.ggoggam.hangar.core.FlySecretStatus
import dev.ggoggam.hangar.core.secretStatus
import org.jetbrains.compose.resources.stringResource

/**
 * `fly secrets list`: each secret's name, its value's digest, and whether it's on the machines yet,
 * once the machines and releases that say so have loaded. Values never reach the phone.
 */
internal fun LazyListScope.secrets(state: DashboardState) {
    item(key = "secrets:note") { Muted(stringResource(Res.string.secrets_note)) }
    section(state.secrets, "secrets", placeholders = 3) { list ->
        val machines = state.machines.value
        val releases = state.releases.value
        val statuses =
            if (machines != null && releases != null) list.associate { it.name to secretStatus(it, machines, releases) } else emptyMap()
        if (statuses.values.any { it == FlySecretStatus.Staged || it == FlySecretStatus.Partial }) {
            item(key = "secrets:undeployed") {
                Text(
                    stringResource(Res.string.secrets_undeployed),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalStatusColors.current.warning,
                )
            }
        }
        if (list.isEmpty()) item(key = "secrets:empty") { Muted(stringResource(Res.string.secrets_empty)) }
        items(list, key = { "secret:${it.name}" }) { SecretRow(it, statuses[it.name]) }
    }
}

@Composable
private fun SecretRow(
    secret: FlySecret,
    status: FlySecretStatus?,
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                secret.name,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            status?.let {
                val (label, tone) =
                    when (it) {
                        FlySecretStatus.Deployed -> Res.string.secret_deployed to Tone.Ok
                        FlySecretStatus.Staged -> Res.string.secret_staged to Tone.Warning
                        FlySecretStatus.Partial -> Res.string.secret_partial to Tone.Warning
                        FlySecretStatus.Unknown -> Res.string.secret_unknown to Tone.Neutral
                    }
                StatusBadge(stringResource(label), tone)
            }
        }
        Text(
            listOf(secret.digest, ago(secret.updatedAt ?: secret.createdAt)).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
