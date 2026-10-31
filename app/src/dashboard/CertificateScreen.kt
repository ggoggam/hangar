package dev.ggoggam.hangar.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.BarEdge
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.ago
import dev.ggoggam.hangar.app.ui.barShadow
import dev.ggoggam.hangar.app.ui.calendarDate
import dev.ggoggam.hangar.app.ui.clockTime
import dev.ggoggam.hangar.app.ui.instantOf
import dev.ggoggam.hangar.app.ui.plainTextClip
import dev.ggoggam.hangar.app.ui.systemConfirmsCopy
import dev.ggoggam.hangar.core.FlyCertState
import dev.ggoggam.hangar.core.FlyCertValidationError
import dev.ggoggam.hangar.core.FlyCertificateDetail
import dev.ggoggam.hangar.core.FlyIssuedCertificate
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One hostname's certificates as `fly certs show` prints them, the DNS records `fly certs setup`
 * lists, each to copy, and Fly.io's word on what's wrong while it isn't verified. Read-only:
 * adding and removing hostnames stays with flyctl.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertificateScreen(
    vm: CertificateViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(Res.string.cert_copied)
    val copy = { text: String ->
        scope.launch {
            clipboard.setClipEntry(plainTextClip(text))
            if (!systemConfirmsCopy) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(copied)
            }
        }
        Unit
    }
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.barShadow(BarEdge.Bottom),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(Res.string.back)) } },
                title = { Text(vm.hostname, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                section(state.detail, "cert", placeholders = 3) { detail -> certificate(detail, copy) }
            }
        }
    }
}

private fun LazyListScope.certificate(
    cert: FlyCertificateDetail,
    copy: (String) -> Unit,
) {
    // Once issued, each certificate's card says so; before then, this says what's missing.
    val rateLimited = instantOf(cert.rateLimitedUntil)?.takeIf { it > Clock.System.now() }
    if (cert.state == FlyCertState.Issuing || cert.state == FlyCertState.NotVerified || rateLimited != null) {
        item(key = "status") { StatusCard(cert.state, rateLimited) }
    }
    items(cert.validationErrors.withIndex().toList(), key = { "error:${it.index}" }) { ValidationErrorCard(it.value) }

    // The custom certificate first, as flyctl prints them; the Fly-managed one is its fallback once it's verified.
    val custom = cert.custom
    listOfNotNull(custom, cert.flyManaged).forEach { issued ->
        item(key = "cert:${issued.source}") {
            IssuedCard(issued, fallback = !issued.isCustom && custom?.state == FlyCertState.Verified)
        }
    }
    if (cert.state == FlyCertState.Issuing || cert.state == FlyCertState.NotVerified) {
        item(key = "verification") { VerificationCard(cert) }
    }

    item(key = "dns:title") { SectionTitle(stringResource(Res.string.cert_dns_title)) }
    item(key = "dns:route") { RouteRecords(cert, copy) }
    val acme = cert.dnsRequirements.acmeChallenge
    if (cert.usesAcme && acme.name.isNotEmpty() && acme.target.isNotEmpty()) {
        item(key = "dns:acme") {
            Panel {
                Text(stringResource(Res.string.cert_dns_acme), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(if (cert.isWildcard) Res.string.cert_dns_acme_wildcard else Res.string.cert_dns_acme_optional))
                DnsRecord("CNAME", acme.name, acme.target, copy)
            }
        }
    }
    val ownership = cert.dnsRequirements.ownership
    if (cert.needsOwnershipRecord && ownership.appValue.isNotEmpty()) {
        val note = if (custom != null && cert.isWildcard) Res.string.cert_dns_ownership_wildcard else Res.string.cert_dns_ownership_optional
        item(key = "dns:ownership") {
            Panel {
                Text(stringResource(Res.string.cert_check_ownership), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(note))
                DnsRecord("TXT", ownership.name, ownership.appValue, copy)
            }
        }
    }
}

/** Where the hostname stands, and until when the certificate authority won't issue for it, if it's [rateLimited]. */
@Composable
private fun StatusCard(
    state: FlyCertState,
    rateLimited: Instant?,
) {
    Panel {
        CertStateBadge(state)
        when (state) {
            FlyCertState.NotVerified -> Text(stringResource(Res.string.cert_not_verified_note), style = MaterialTheme.typography.bodyMedium)
            FlyCertState.Issuing -> Text(stringResource(Res.string.cert_issuing_note), style = MaterialTheme.typography.bodyMedium)
            else -> Unit
        }
        rateLimited?.let {
            Text(
                stringResource(Res.string.cert_rate_limited, "${calendarDate(it)} ${clockTime(it)}"),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalStatusColors.current.warning,
            )
        }
    }
}

/** A validation error and what to do about it, as `fly certs check` prints them. */
@Composable
private fun ValidationErrorCard(error: FlyCertValidationError) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Lucide.TriangleAlert,
                contentDescription = null,
                tint = LocalStatusColors.current.warning,
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(error.message, style = MaterialTheme.typography.bodyMedium)
                error.remediation?.takeIf { it.isNotBlank() }?.let { Muted(it) }
            }
        }
    }
}

/** One certificate, as a section of `fly certs show`. [fallback] is the Fly-managed one behind a verified custom one. */
@Composable
private fun IssuedCard(
    cert: FlyIssuedCertificate,
    fallback: Boolean,
) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (cert.isCustom) Res.string.cert_custom else Res.string.cert_fly),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (fallback) StatusBadge(stringResource(Res.string.cert_fallback), Tone.Neutral) else CertStateBadge(cert.state)
        }
        cert.authority?.let { Field(stringResource(Res.string.cert_authority), it) }
        cert.issuer?.takeIf { it.isNotBlank() }?.let { Field(stringResource(Res.string.cert_issuer), it) }
        if (cert.issued.isNotEmpty()) {
            Field(stringResource(Res.string.cert_key_types), cert.issued.joinToString(", ") { it.type.uppercase() })
        }
        ago(cert.createdAt).takeIf { it.isNotEmpty() }?.let { Field(stringResource(Res.string.cert_added), it) }
        instantOf(cert.expires)?.let { expires ->
            val days = (expires - Clock.System.now()).inWholeDays
            Field(
                stringResource(Res.string.cert_expires),
                if (expires > Clock.System.now()) {
                    stringResource(Res.string.cert_days_left, calendarDate(expires), days.toInt())
                } else {
                    stringResource(Res.string.cert_expired, calendarDate(expires))
                },
            )
        }
    }
}

/**
 * The ways Fly.io can prove the hostname, and which it found in place: what's missing while it
 * isn't verified.
 */
@Composable
private fun VerificationCard(cert: FlyCertificateDetail) {
    val v = cert.validation
    Panel {
        Text(stringResource(Res.string.cert_verification), style = MaterialTheme.typography.titleSmall)
        Muted(stringResource(Res.string.cert_verification_note))
        Check(stringResource(Res.string.cert_check_alpn), v.alpnConfigured)
        Check(stringResource(Res.string.cert_check_http), v.httpConfigured)
        Check(stringResource(Res.string.cert_check_dns), v.dnsConfigured)
        if (cert.needsOwnershipRecord) Check(stringResource(Res.string.cert_check_ownership), v.ownershipTxtConfigured)
    }
}

@Composable
private fun Check(
    label: String,
    passed: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (passed) Lucide.CircleCheck else Lucide.CircleX,
            contentDescription = null,
            tint = if (passed) LocalStatusColors.current.ok else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The A and AAAA records that send the hostname's traffic to the app, or a CNAME instead, as `fly certs setup` lists them. */
@Composable
private fun RouteRecords(
    cert: FlyCertificateDetail,
    copy: (String) -> Unit,
) {
    val dns = cert.dnsRequirements
    // flyctl offers the CNAME only below a domain's apex, where DNS allows one. It asks the public
    // suffix list; two labels, as its www tip assumes, covers most domains without shipping the list.
    val cname = dns.cname.takeIf { it.isNotEmpty() && cert.hostname.count { c -> c == '.' } > 1 }
    Panel {
        Text(stringResource(Res.string.cert_dns_route), style = MaterialTheme.typography.titleSmall)
        if (dns.a.isEmpty() && dns.aaaa.isEmpty()) Muted(stringResource(Res.string.cert_dns_no_ips))
        dns.a.forEach { DnsRecord("A", cert.hostname, it, copy) }
        dns.aaaa.forEach { DnsRecord("AAAA", cert.hostname, it, copy) }
        if (cname != null) {
            Muted(stringResource(Res.string.cert_dns_cname_instead), Modifier.padding(top = 4.dp))
            DnsRecord("CNAME", cert.hostname, cname, copy)
        }
    }
}

/** A record to set: its type, then its name and value, each with a button that copies it. */
@Composable
private fun DnsRecord(
    type: String,
    name: String,
    value: String,
    copy: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            type,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp).padding(top = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            CopyLine(name, copy)
            CopyLine(value, copy)
        }
    }
}

@Composable
private fun CopyLine(
    text: String,
    copy: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { copy(text) }, modifier = Modifier.size(36.dp)) {
            Icon(
                Lucide.Copy,
                contentDescription = stringResource(Res.string.cert_copy, text),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Where a hostname's certificate stands, in flyctl's words. */
@Composable
internal fun CertStateBadge(state: FlyCertState) {
    val (label, tone) =
        when (state) {
            FlyCertState.Issued -> Res.string.cert_issued to Tone.Ok
            FlyCertState.Verified -> Res.string.cert_verified to Tone.Ok
            FlyCertState.Issuing -> Res.string.cert_issuing to Tone.Warning
            FlyCertState.NotVerified -> Res.string.cert_not_verified to Tone.Warning
        }
    StatusBadge(stringResource(label), tone)
}
