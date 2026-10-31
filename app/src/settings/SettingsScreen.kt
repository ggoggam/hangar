package dev.ggoggam.hangar.app.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Building2
import com.composables.icons.lucide.CircleDollarSign
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Receipt
import com.composables.icons.lucide.Rocket
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Siren
import com.composables.icons.lucide.User
import com.composables.icons.lucide.UserPlus
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.lock.AppLock
import dev.ggoggam.hangar.app.session.rememberDeviceAuth
import dev.ggoggam.hangar.app.ui.BarEdge
import dev.ggoggam.hangar.app.ui.barShadow
import dev.ggoggam.hangar.app.ui.percent
import dev.ggoggam.hangar.app.ui.usd
import dev.ggoggam.hangar.app.watch.BackgroundChecks
import dev.ggoggam.hangar.app.watch.WatchSettings
import dev.ggoggam.hangar.app.watch.checksDetail
import dev.ggoggam.hangar.app.watch.rememberNotificationPermission
import dev.ggoggam.hangar.core.FlyAccount
import dev.ggoggam.hangar.core.FlyOrganization
import dev.ggoggam.hangar.core.FlyVolume
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The account, organizations and platform as flyctl's `auth`, `orgs` and `platform` commands show
 * them, the other accounts on this device, background checks, the app lock, the theme, and signing out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOrganization: (FlyOrganization) -> Unit,
    onRegions: () -> Unit,
    onAddAccount: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val others = accounts.filterNot { it.current }
    val theme = koinInject<ThemeSettings>()
    val dark = theme.dark.collectAsStateWithLifecycle().value ?: isSystemInDarkTheme()
    val checks = koinInject<BackgroundChecks>()
    val watch = koinInject<WatchSettings>()
    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) {
        SignOutDialog(next = others.firstOrNull(), onConfirm = vm::signOut, onDismiss = { confirmSignOut = false })
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.settings_title)) }, modifier = Modifier.barShadow(BarEdge.Bottom)) },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "account") { Header(stringResource(Res.string.settings_account)) }
            rows(state.email, "email") { email ->
                item(key = "email") {
                    SettingRow(stringResource(Res.string.settings_signed_in), icon = Lucide.User, detail = email)
                    RowDivider()
                }
            }
            items(others, key = { "account:${it.id}" }) { account ->
                SettingRow(
                    accountLabel(account),
                    icon = Lucide.ArrowLeftRight,
                    detail = stringResource(Res.string.settings_switch_account),
                    onClick = { vm.switchTo(account) },
                )
                RowDivider()
            }
            item(key = "add-account") {
                SettingRow(
                    stringResource(Res.string.settings_add_account),
                    icon = Lucide.UserPlus,
                    detail = stringResource(Res.string.settings_add_account_detail),
                    onClick = onAddAccount,
                    chevron = true,
                )
                RowDivider()
            }
            item(key = "sign-out") {
                SettingRow(
                    stringResource(Res.string.settings_sign_out),
                    icon = Lucide.LogOut,
                    onClick = { confirmSignOut = true },
                    destructive = true,
                )
                RowDivider()
            }

            item(key = "orgs") { Header(stringResource(Res.string.settings_organizations)) }
            rows(state.organizations, "orgs") { orgs ->
                items(orgs, key = { "org:${it.slug}" }) { org ->
                    SettingRow(
                        org.name ?: org.slug,
                        icon = Lucide.Building2,
                        detail =
                            listOfNotNull(
                                org.slug,
                                org.type?.let { orgTypeLabel(it) },
                                org.viewerRole?.let { roleLabel(it) },
                            ).joinToString(" · "),
                        onClick = { onOrganization(org) },
                        chevron = true,
                    )
                    RowDivider()
                }
            }

            item(key = "platform") {
                Header(stringResource(Res.string.settings_platform))
                SettingRow(stringResource(Res.string.settings_regions), icon = Lucide.Globe, onClick = onRegions, chevron = true)
                RowDivider()
            }

            if (checks.supported) {
                item(key = "notifications") { Notifications(watch) }
            }

            item(key = "security") {
                Header(stringResource(Res.string.settings_security))
                LockSwitch(koinInject())
                RowDivider()
            }

            item(key = "appearance") {
                Header(stringResource(Res.string.settings_appearance))
                // The whole row toggles; the switch only shows the state.
                SettingRow(stringResource(Res.string.settings_dark_mode), icon = Lucide.Moon, onClick = { theme.setDark(!dark) }) {
                    Switch(checked = dark, onCheckedChange = null)
                }
                RowDivider()
            }
        }
    }
}

/**
 * The background checks' switch, which asks to post notifications as it turns them on, and what
 * they notify about while they're on.
 */
@Composable
private fun Notifications(watch: WatchSettings) {
    val on by watch.enabled.collectAsStateWithLifecycle()
    val incidents by watch.incidents.collectAsStateWithLifecycle()
    val apps by watch.apps.collectAsStateWithLifecycle()
    val deploys by watch.deploys.collectAsStateWithLifecycle()
    val volumes by watch.volumes.collectAsStateWithLifecycle()
    val billing by watch.billing.collectAsStateWithLifecycle()
    val budget by watch.budget.collectAsStateWithLifecycle()
    var editBudget by remember { mutableStateOf(false) }
    if (editBudget) {
        BudgetDialog(budget, onSave = { watch.setBudget(it) }, onDismiss = { editBudget = false })
    }
    val ask = rememberNotificationPermission()
    var denied by remember { mutableStateOf(false) }
    Header(stringResource(Res.string.settings_notifications))
    SettingRow(
        stringResource(Res.string.settings_checks),
        icon = Lucide.Bell,
        detail = stringResource(if (denied) Res.string.settings_checks_denied else checksDetail),
        onClick = {
            if (on) {
                watch.setEnabled(false)
            } else {
                ask { granted ->
                    denied = !granted
                    if (granted) watch.setEnabled(true)
                }
            }
        },
    ) {
        Switch(checked = on, onCheckedChange = null)
    }
    RowDivider()
    if (on) {
        SettingRow(
            stringResource(Res.string.settings_notify_incidents),
            icon = Lucide.Siren,
            detail = stringResource(Res.string.settings_notify_incidents_detail),
            onClick = { watch.setIncidents(!incidents) },
        ) {
            Switch(checked = incidents, onCheckedChange = null)
        }
        RowDivider()
        SettingRow(
            stringResource(Res.string.settings_notify_apps),
            icon = Lucide.Server,
            detail = stringResource(Res.string.settings_notify_apps_detail),
            onClick = { watch.setApps(!apps) },
        ) {
            Switch(checked = apps, onCheckedChange = null)
        }
        RowDivider()
        SettingRow(
            stringResource(Res.string.settings_notify_deploys),
            icon = Lucide.Rocket,
            detail = stringResource(Res.string.settings_notify_deploys_detail),
            onClick = { watch.setDeploys(!deploys) },
        ) {
            Switch(checked = deploys, onCheckedChange = null)
        }
        RowDivider()
        SettingRow(
            stringResource(Res.string.settings_notify_volumes),
            icon = Lucide.HardDrive,
            detail = stringResource(Res.string.settings_notify_volumes_detail, percent(FlyVolume.NEARLY_FULL)),
            onClick = { watch.setVolumes(!volumes) },
        ) {
            Switch(checked = volumes, onCheckedChange = null)
        }
        RowDivider()
        SettingRow(
            stringResource(Res.string.settings_notify_billing),
            icon = Lucide.Receipt,
            detail = stringResource(Res.string.settings_notify_billing_detail),
            onClick = { watch.setBilling(!billing) },
        ) {
            Switch(checked = billing, onCheckedChange = null)
        }
        RowDivider()
        if (billing) {
            SettingRow(
                stringResource(Res.string.settings_spend_alert),
                icon = Lucide.CircleDollarSign,
                detail =
                    budget?.let { stringResource(Res.string.settings_spend_alert_on, usd(it)) }
                        ?: stringResource(Res.string.settings_spend_alert_off),
                onClick = { editBudget = true },
                chevron = true,
            )
            RowDivider()
        }
    }
}

/**
 * The app lock's switch, which asks the user to authenticate as it turns the lock on, so it's never
 * on for someone who can't get past it. Off and greyed out while the device has no screen lock.
 */
@Composable
private fun LockSwitch(lock: AppLock) {
    val on by lock.on.collectAsStateWithLifecycle()
    val auth = rememberDeviceAuth()
    // Asked again each time the app comes back, so a screen lock set meanwhile in system settings counts.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val available = remember(auth, state) { auth.available }
    val scope = rememberCoroutineScope()
    val reason = stringResource(Res.string.lock_reason_turn_on)
    var asking by remember { mutableStateOf(false) }
    val toggle: () -> Unit = {
        if (on) {
            lock.turnOff()
        } else if (!asking) {
            asking = true
            scope.launch {
                try {
                    lock.turnOn(auth, reason)
                } finally {
                    asking = false
                }
            }
        }
    }
    // Once on, it can always be turned off, even with the screen lock since removed.
    val usable = on || available
    SettingRow(
        stringResource(Res.string.settings_lock),
        icon = Lucide.Lock,
        detail = stringResource(if (usable) Res.string.settings_lock_detail else Res.string.settings_lock_unavailable),
        onClick = toggle.takeIf { usable },
    ) {
        Switch(checked = on, onCheckedChange = null, enabled = usable)
    }
}

/** Sets the monthly spend to be notified past, in whole or part dollars, or turns it off. */
@Composable
private fun BudgetDialog(
    budget: Double?,
    onSave: (Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(budget?.let { decimalText(it) }.orEmpty()) }
    // Some keyboards type a decimal comma.
    val amount =
        text
            .trim()
            .removePrefix("$")
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.spend_alert_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.spend_alert_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(Res.string.spend_alert_label)) },
                    prefix = { Text("$") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(amount)
                    onDismiss()
                },
                enabled = amount != null,
            ) { Text(stringResource(Res.string.spend_alert_save)) }
        },
        dismissButton = {
            if (budget != null) {
                TextButton(onClick = {
                    onSave(null)
                    onDismiss()
                }) { Text(stringResource(Res.string.spend_alert_turn_off)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** [value] as typed back into the field: `50`, not `50.0`. */
private fun decimalText(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * Asks before signing out, since signing in again means the browser and a code, and says which
 * account comes [next], if there's another.
 */
@Composable
private fun SignOutDialog(
    next: FlyAccount?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (next == null) Res.string.sign_out_title else Res.string.sign_out_account_title)) },
        text = {
            Text(
                if (next == null) {
                    stringResource(Res.string.sign_out_body)
                } else {
                    stringResource(Res.string.sign_out_account_body, accountLabel(next))
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.settings_sign_out), color = LocalStatusColors.current.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** What the account switcher calls [account]: its email or organization, or what it is. */
@Composable
private fun accountLabel(account: FlyAccount): String =
    account.label ?: stringResource(if (account.demo) Res.string.account_demo else Res.string.account_unnamed)

/** The label for an organization type, `PERSONAL` or `SHARED`, else Fly.io's own word. */
@Composable
internal fun orgTypeLabel(type: String): String =
    when (type.uppercase()) {
        "PERSONAL" -> stringResource(Res.string.org_type_personal)
        "SHARED" -> stringResource(Res.string.org_type_shared)
        else -> type.lowercase()
    }

/** The label for a role in an organization, `admin` or `member`, else Fly.io's own word. */
@Composable
internal fun roleLabel(role: String): String =
    when (role.lowercase()) {
        "admin" -> stringResource(Res.string.role_admin)
        "member" -> stringResource(Res.string.role_member)
        else -> role.lowercase()
    }
