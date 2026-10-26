package dev.ggoggam.hangar.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.dashboard.Load
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.ShimmerBar
import org.jetbrains.compose.resources.stringResource

// Rows with dividers between them, the way the app list is set, for the settings tab and the
// screens it opens.

/** A group's name, set like the organization names over the app list. */
@Composable
internal fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/**
 * One row: an optional [icon], a [label] with a muted [detail] under it, and [trailing] content.
 * With [onClick] the whole row is tappable, and [chevron] marks one that opens another screen.
 * [destructive] sets the icon and label in the error color, for signing out.
 */
@Composable
internal fun SettingRow(
    label: String,
    icon: ImageVector? = null,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val color = if (destructive) LocalStatusColors.current.error else Color.Unspecified
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = if (destructive) color else LocalContentColor.current,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        if (chevron) {
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
internal fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline)

/** A muted line in place of rows, for an empty or failed list. */
@Composable
internal fun RowsMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/** [load]'s rows: a shimmering row while it loads, a line when it fails, else [content]. */
internal fun <T> LazyListScope.rows(
    load: Load<T>,
    key: String,
    content: LazyListScope.(T) -> Unit,
) {
    when (load) {
        Load.Loading -> {
            item(key = "$key:loading") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShimmerBar(Modifier.fillMaxWidth(0.5f).height(16.dp))
                    ShimmerBar(Modifier.fillMaxWidth(0.7f).height(12.dp))
                }
            }
        }

        Load.Failed -> {
            item(key = "$key:failed") { RowsMessage(stringResource(Res.string.load_failed)) }
        }

        is Load.Ready -> {
            content(load.value)
        }
    }
}
