package dev.ggoggam.hangar.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Square
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.core.FlyMachine
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** What a machine in [state] can have done to it, as the Fly.io dashboard's machine menu offers. */
private fun actionsFor(state: String): List<Action> =
    when (state) {
        "started" -> listOf(Action.Restart, Action.Stop, Action.Suspend)
        "stopped", "suspended" -> listOf(Action.Start)
        else -> emptyList()
    }

private val Action.label: StringResource
    get() =
        when (this) {
            Action.Start -> Res.string.action_start
            Action.Stop -> Res.string.action_stop
            Action.Suspend -> Res.string.action_suspend
            Action.Restart -> Res.string.action_restart
            Action.RestartApp -> Res.string.app_restart
        }

private val Action.icon: ImageVector
    get() =
        when (this) {
            Action.Start -> Lucide.Play
            Action.Stop -> Lucide.Square
            Action.Suspend -> Lucide.Pause
            Action.Restart, Action.RestartApp -> Lucide.RotateCw
        }

/**
 * A machine's actions behind a menu, or a spinner while one is under way. Starting goes straight
 * ahead; stopping, suspending and restarting ask first, since they touch a running app.
 */
@Composable
internal fun MachineMenu(
    machine: FlyMachine,
    busy: Action?,
    onAction: (FlyMachine, Action) -> Unit,
) {
    if (busy != null) {
        CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), strokeWidth = 2.dp)
        return
    }
    val actions = actionsFor(machine.state)
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Action?>(null) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.padding(start = 4.dp).size(36.dp)) {
            Icon(Lucide.EllipsisVertical, stringResource(Res.string.machine_actions), modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surface) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        open = false
                        if (action == Action.Start) onAction(machine, action) else confirming = action
                    },
                )
            }
        }
    }
    confirming?.let { action ->
        val name = machine.name.ifEmpty { machine.id }
        val (title, body) =
            when (action) {
                Action.Stop -> Res.string.confirm_stop_title to Res.string.confirm_stop_body
                Action.Suspend -> Res.string.confirm_suspend_title to Res.string.confirm_suspend_body
                else -> Res.string.confirm_restart_title to Res.string.confirm_restart_body
            }
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(title, name)) },
            text = { Text(stringResource(body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onAction(machine, action)
                }) {
                    Text(
                        stringResource(action.label),
                        color = if (action == Action.Restart) MaterialTheme.colorScheme.primary else LocalStatusColors.current.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.cancel)) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

/**
 * Restarting the whole app, as `fly apps restart` does, or its progress while it's under way.
 * [running] is how many machines it would restart; with none, there's nothing to restart.
 */
@Composable
internal fun AppRestartControl(
    app: String,
    running: Int,
    progress: AppRestart?,
    onRestart: (rolling: Boolean) -> Unit,
) {
    if (progress != null) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (progress.total > 0) {
                LinearProgressIndicator(progress = { progress.done / progress.total.toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Muted(stringResource(Res.string.app_restart_progress, progress.done, progress.total))
        }
        return
    }
    var asking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { asking = true }, enabled = running > 0) {
        Icon(Lucide.RotateCw, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(16.dp))
        Text(stringResource(Res.string.app_restart))
    }
    if (asking) {
        var rolling by rememberSaveable { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(Res.string.app_restart_title, app)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.app_restart_body, running))
                    Column(Modifier.selectableGroup()) {
                        RestartChoice(
                            stringResource(Res.string.app_restart_rolling),
                            stringResource(Res.string.app_restart_rolling_hint),
                            rolling,
                        ) {
                            rolling = true
                        }
                        RestartChoice(
                            stringResource(Res.string.app_restart_now),
                            stringResource(Res.string.app_restart_now_hint),
                            !rolling,
                        ) {
                            rolling = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onRestart(rolling)
                }) { Text(stringResource(Res.string.action_restart)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(Res.string.cancel)) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun RestartChoice(
    title: String,
    hint: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected, onClick = null, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What to say about how an action went. */
internal suspend fun noticeText(notice: ActionNotice): String =
    when (notice) {
        is ActionNotice.Done -> {
            val done =
                when (notice.action) {
                    Action.Start -> Res.string.done_start
                    Action.Stop -> Res.string.done_stop
                    Action.Suspend -> Res.string.done_suspend
                    Action.Restart -> Res.string.done_restart
                    Action.RestartApp -> Res.string.done_app_restart
                }
            getString(done, notice.target)
        }

        is ActionNotice.Failed -> {
            when (notice.reason) {
                ActionFailure.NotAllowed -> {
                    getString(Res.string.action_not_allowed)
                }

                ActionFailure.Busy -> {
                    getString(Res.string.action_busy)
                }

                ActionFailure.PostgresApp -> {
                    getString(Res.string.action_postgres)
                }

                ActionFailure.TimedOut -> {
                    getString(Res.string.action_timed_out, notice.target)
                }

                ActionFailure.Other -> {
                    notice.detail?.let { getString(Res.string.action_failed_detail, it) }
                        ?: getString(Res.string.action_failed)
                }
            }
        }
    }
