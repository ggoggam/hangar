package dev.ggoggam.hangar.app.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Building2
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.User
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.core.FlyOrganization
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The account, organizations and platform as flyctl's `auth`, `orgs` and `platform` commands show
 * them, the theme, and signing out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOrganization: (FlyOrganization) -> Unit,
    onRegions: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val theme = koinInject<ThemeSettings>()
    val dark = theme.dark.collectAsStateWithLifecycle().value ?: isSystemInDarkTheme()
    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) {
        SignOutDialog(onConfirm = vm::signOut, onDismiss = { confirmSignOut = false })
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.settings_title)) }) },
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

/** Asks before signing out, since signing in again means the browser and a code. */
@Composable
private fun SignOutDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sign_out_title)) },
        text = { Text(stringResource(Res.string.sign_out_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.settings_sign_out), color = LocalStatusColors.current.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

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
