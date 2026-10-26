package dev.ggoggam.hangar.app.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.User
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.instantOf
import dev.ggoggam.hangar.core.FlyAccessToken
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** One organization as `fly orgs show` shows it, with its tokens as `fly tokens list --scope org` lists them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationScreen(
    vm: OrganizationViewModel,
    title: String,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(Res.string.back)) } },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            rows(state.detail, "org") { org ->
                item(key = "about") {
                    Header(stringResource(Res.string.org_about))
                    SettingRow(stringResource(Res.string.org_slug), trailing = { Text(org.slug) })
                    RowDivider()
                    SettingRow(stringResource(Res.string.org_type), trailing = { Text(orgTypeLabel(org.type)) })
                    RowDivider()
                    SettingRow(stringResource(Res.string.org_role), trailing = { Text(roleLabel(org.viewerRole)) })
                    RowDivider()
                    Header(stringResource(Res.string.org_members, org.members.size))
                }
                items(org.members, key = { "member:${it.email}:${it.name}" }) { member ->
                    SettingRow(
                        member.name.ifEmpty { member.email },
                        icon = Lucide.User,
                        detail = member.email.takeIf { member.name.isNotEmpty() },
                        trailing = { StatusBadge(roleLabel(member.role), Tone.Neutral) },
                    )
                    RowDivider()
                }
            }

            item(key = "tokens") { Header(stringResource(Res.string.org_tokens)) }
            rows(state.tokens, "tokens") { tokens ->
                if (tokens.isEmpty()) item(key = "tokens:none") { RowsMessage(stringResource(Res.string.org_tokens_none)) }
                items(tokens, key = { "token:${it.id}" }) { token ->
                    TokenRow(token)
                    RowDivider()
                }
            }
        }
    }
}

/** A token's name, who made it and when it runs out. Fly.io never shows the token itself again, and nor does Hangar. */
@Composable
private fun TokenRow(token: FlyAccessToken) {
    val expires = instantOf(token.expiresAt)
    val state =
        when {
            token.revokedAt != null -> stringResource(Res.string.token_revoked) to Tone.Error
            expires != null && expires < Clock.System.now() -> stringResource(Res.string.token_expired) to Tone.Neutral
            else -> null
        }
    SettingRow(
        token.name.ifEmpty { token.id },
        icon = Lucide.KeyRound,
        detail =
            listOfNotNull(
                token.createdBy,
                token.expiresAt?.substringBefore('T')?.let { stringResource(Res.string.token_expires, it) },
            ).joinToString(" · "),
        trailing = state?.let { (label, tone) -> { StatusBadge(label, tone) } },
    )
}
