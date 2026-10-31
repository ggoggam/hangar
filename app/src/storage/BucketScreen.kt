package dev.ggoggam.hangar.app.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.dashboard.Field
import dev.ggoggam.hangar.app.dashboard.Muted
import dev.ggoggam.hangar.app.dashboard.Panel
import dev.ggoggam.hangar.app.dashboard.SectionTitle
import dev.ggoggam.hangar.app.dashboard.section
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.core.FlyBucket
import dev.ggoggam.hangar.core.FlyShadowBucket
import org.jetbrains.compose.resources.stringResource

/**
 * One Tigris bucket, as `fly storage status` shows it: whether it's public, where to reach it, its
 * custom domain and shadow bucket, and the app it was made for, which opens its dashboard. Never
 * its access keys, nor what's in it, which only those keys can list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BucketScreen(
    vm: BucketViewModel,
    title: String,
    onBack: () -> Unit,
    onOpenApp: (name: String) -> Unit,
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
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                section(state.bucket, "bucket") { bucket ->
                    item(key = "bucket") { AboutCard(bucket) }
                    item(key = "endpoint:title") { SectionTitle(stringResource(Res.string.bucket_endpoint)) }
                    item(key = "endpoint") { EndpointCard(bucket) }
                    bucket.customDomain?.let { domain ->
                        item(key = "domain:title") { SectionTitle(stringResource(Res.string.bucket_domain)) }
                        item(key = "domain") { DomainCard(domain, bucket.cnameTarget) }
                    }
                    bucket.shadow?.let { shadow ->
                        item(key = "shadow:title") { SectionTitle(stringResource(Res.string.bucket_shadow)) }
                        item(key = "shadow") { ShadowCard(shadow) }
                    }
                    item(key = "app:title") { SectionTitle(stringResource(Res.string.bucket_app)) }
                    val app = bucket.app
                    if (app == null) {
                        item(key = "app:none") { Muted(stringResource(Res.string.data_unattached)) }
                    } else {
                        item(key = "app:$app") { AppRow(app) { onOpenApp(app) } }
                    }
                }
            }
        }
    }
}

/** The bucket's id and status, and what `fly storage status` lists about it. */
@Composable
private fun AboutCard(bucket: FlyBucket) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    bucket.id,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            bucket.status?.let { StatusBadge(it, bucketTone(it)) }
        }
        bucket.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Field(
            stringResource(Res.string.bucket_access),
            stringResource(if (bucket.public) Res.string.bucket_public else Res.string.bucket_private),
        )
        if (bucket.accelerate) Field(stringResource(Res.string.bucket_caching), stringResource(Res.string.bucket_accelerate))
        bucket.org?.let { Field(stringResource(Res.string.overview_org), it) }
        bucket.createdAt?.let { Field(stringResource(Res.string.mpg_created), ago(it)) }
    }
}

/** An extension's status, `ready` once provisioned or `error`, as `fly storage create` waits for it. */
private fun bucketTone(status: String) =
    when (status) {
        "ready" -> Tone.Ok
        "error" -> Tone.Error
        else -> Tone.Warning
    }

/** What an S3 client needs besides the keys, as `fly storage create` sets them on the app. */
@Composable
private fun EndpointCard(bucket: FlyBucket) {
    Panel {
        Field(stringResource(Res.string.bucket_s3), FlyBucket.ENDPOINT, selectable = true, monospace = true)
        Field(stringResource(Res.string.mpg_region), "auto", monospace = true)
        Field(stringResource(Res.string.bucket_name), bucket.name, selectable = true, monospace = true)
        if (bucket.public) Field(stringResource(Res.string.bucket_public_url), bucket.publicUrl, selectable = true, monospace = true)
        Text(
            stringResource(if (bucket.app == null) Res.string.bucket_keys_note else Res.string.bucket_keys_note_app),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The domain the bucket serves its objects on, and the CNAME record it needs. */
@Composable
private fun DomainCard(
    domain: String,
    target: String,
) {
    Panel {
        Field(stringResource(Res.string.bucket_domain_name), domain, selectable = true, monospace = true)
        Field(stringResource(Res.string.bucket_cname), target, selectable = true, monospace = true)
        Text(
            stringResource(Res.string.bucket_domain_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The bucket on another service the bucket fills itself from, without its keys. */
@Composable
private fun ShadowCard(shadow: FlyShadowBucket) {
    Panel {
        Field(stringResource(Res.string.bucket_name), shadow.name, selectable = true, monospace = true)
        shadow.endpoint?.let { Field(stringResource(Res.string.bucket_s3), it, selectable = true, monospace = true) }
        shadow.region?.let { Field(stringResource(Res.string.mpg_region), it, monospace = true) }
        Text(
            stringResource(if (shadow.writeThrough) Res.string.bucket_shadow_write_through else Res.string.bucket_shadow_read),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
