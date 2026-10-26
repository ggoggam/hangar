package dev.ggoggam.hangar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** How good a state is, which picks its badge's color. */
enum class Tone { Ok, Warning, Error, Neutral }

/** A small tinted label for a state such as `started` or `passing`. */
@Composable
fun StatusBadge(
    label: String,
    tone: Tone,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStatusColors.current
    val (fg, bg) =
        when (tone) {
            Tone.Ok -> colors.ok to colors.okTint
            Tone.Warning -> colors.warning to colors.warningTint
            Tone.Error -> colors.error to colors.errorTint
            Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
        }
    Text(
        label,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The tone of a Machines API machine state. */
fun machineTone(state: String): Tone =
    when (state) {
        "started" -> Tone.Ok
        "stopped", "suspended" -> Tone.Neutral
        "failed" -> Tone.Error
        else -> Tone.Warning
    }

/** The tone of a health check's status: `passing`, `warning` or `critical`. */
fun checkTone(status: String): Tone =
    when (status) {
        "passing" -> Tone.Ok
        "warning" -> Tone.Warning
        else -> Tone.Error
    }

/** The tone of a release's status. */
fun releaseTone(status: String): Tone =
    when (status) {
        "complete", "succeeded" -> Tone.Ok
        "failed" -> Tone.Error
        "running", "pending" -> Tone.Warning
        else -> Tone.Neutral
    }

/** The label for an app's status: `deployed`, `pending` or `suspended`, else Fly.io's own word. */
@Composable
fun appStatusLabel(status: String): String =
    when (status) {
        "deployed" -> stringResource(Res.string.status_deployed)
        "pending" -> stringResource(Res.string.status_pending)
        "suspended" -> stringResource(Res.string.status_suspended)
        else -> status
    }

fun appStatusTone(status: String): Tone =
    when (status) {
        "deployed" -> Tone.Ok
        "pending" -> Tone.Warning
        else -> Tone.Neutral
    }
