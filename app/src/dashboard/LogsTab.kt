package dev.ggoggam.hangar.app.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import dev.ggoggam.hangar.app.LocalStatusColors
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.clockTime
import dev.ggoggam.hangar.app.ui.instantOf
import dev.ggoggam.hangar.app.ui.plainTextClip
import dev.ggoggam.hangar.app.ui.systemConfirmsCopy
import dev.ggoggam.hangar.core.FlyLogEntry
import dev.ggoggam.hangar.core.FlyMachine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.stringResource

/**
 * `fly logs`, following new lines while the tab is open, optionally narrowed to one machine. The
 * search and the errors filter look through the lines received since the tab opened, as the
 * dashboard's live logs do, since Fly.io's logs API has no search of its own.
 */
@Composable
internal fun LogsTab(
    vm: AppDashboardViewModel,
    machines: List<FlyMachine>,
    modifier: Modifier,
) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Restarted when the machine filter changes, so the new filter's lines come in straight away.
    LaunchedEffect(lifecycle, logs.instance) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.followLogs() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var problemsOnly by rememberSaveable { mutableStateOf(false) }

    // Each line read once, however often the list or the search changes.
    val readings = remember { mutableMapOf<FlyLogEntry, ReadableLine>() }
    val lines =
        remember(logs.entries) {
            readings.keys.retainAll(logs.entries.toSet())
            logs.entries.map { LogItem(it, readings.getOrPut(it) { readable(it) }) }
        }
    val filtering = query.isNotBlank() || problemsOnly
    val shown = remember(lines, query, problemsOnly) { lines.filter { it.matches(query.trim(), problemsOnly) } }

    Column(modifier) {
        SearchField(query, onChange = { query = it })
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(problemsOnly, { problemsOnly = !problemsOnly }, { Text(stringResource(Res.string.logs_problems)) })
            if (machines.size > 1) {
                VerticalDivider(Modifier.height(32.dp).align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.outline)
                FilterChip(logs.instance == null, { vm.setLogInstance(null) }, { Text(stringResource(Res.string.logs_all)) })
                machines.forEach { m ->
                    FilterChip(logs.instance == m.id, { vm.setLogInstance(m.id) }, { Text("${m.region} · ${m.id.take(8)}") })
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (logs.failing) {
            Text(
                stringResource(Res.string.logs_failed),
                style = MaterialTheme.typography.bodySmall,
                color = LocalStatusColors.current.warning,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        when {
            lines.isEmpty() -> {
                Muted(stringResource(Res.string.logs_waiting), Modifier.padding(16.dp))
            }

            filtering && shown.isEmpty() -> {
                Muted(stringResource(Res.string.logs_no_matches, lines.size), Modifier.padding(16.dp))
            }

            else -> {
                if (filtering) {
                    Muted(
                        stringResource(Res.string.logs_matches, shown.size, lines.size),
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
                LogLines(shown, query.trim(), showInstance = logs.instance == null && machines.size != 1)
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        query,
        onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text(stringResource(Res.string.logs_search), style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Lucide.Search, contentDescription = null, Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Lucide.X, stringResource(Res.string.logs_search_clear), Modifier.size(18.dp))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

/** A line as it came and as it reads. */
private class LogItem(
    val entry: FlyLogEntry,
    val line: ReadableLine,
) {
    /**
     * Whether it has [query] in it, ignoring case, in its message as it came or as it reads, or its
     * region or machine; and, when [problemsOnly], is a warning or an error.
     */
    fun matches(
        query: String,
        problemsOnly: Boolean,
    ): Boolean {
        if (problemsOnly && severity(line.level) == null) return false
        if (query.isEmpty()) return true
        return listOf(entry.message, line.message, line.fields, entry.region, entry.instance).any { it.contains(query, ignoreCase = true) }
    }
}

private enum class Severity { Warning, Error }

private fun severity(level: String): Severity? =
    when (level.lowercase()) {
        "error", "fatal", "critical" -> Severity.Error
        "warn", "warning" -> Severity.Warning
        else -> null
    }

@Composable
private fun LogLines(
    items: List<LogItem>,
    query: String,
    showInstance: Boolean,
) {
    val list = rememberLazyListState()
    // Stay pinned to the newest line until the user scrolls up to read, like a terminal.
    var follow by remember { mutableStateOf(true) }
    val atEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to atEnd }.collect { (scrolling, end) -> if (scrolling) follow = end }
    }
    LaunchedEffect(items.size, query) {
        if (follow && items.isNotEmpty()) list.scrollToItem(items.lastIndex)
    }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(Res.string.logs_copied)
    val copy = { entry: FlyLogEntry ->
        scope.launch {
            clipboard.setClipEntry(plainTextClip(asFlyLogs(entry)))
            if (!systemConfirmsCopy) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(copied)
            }
        }
        Unit
    }

    Box(Modifier.fillMaxSize()) {
        // A tap copies the whole line; a long press still selects part of one.
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(items, key = { i, it -> it.entry.id.ifEmpty { "line:$i" } }) { _, it ->
                    LogLine(it, query, showInstance) { copy(it.entry) }
                }
            }
        }
        // Programmatic scrolls count as scrolling, so reaching the end this way follows again.
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)) {
            ScrollButton(list.canScrollBackward, Lucide.ArrowUp, stringResource(Res.string.logs_to_top)) {
                scope.launch { list.animateScrollToItem(0) }
            }
            ScrollButton(list.canScrollForward, Lucide.ArrowDown, stringResource(Res.string.logs_to_bottom)) {
                scope.launch { list.animateScrollToItem(items.lastIndex) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ScrollButton(
    visible: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    // Growing and shrinking the slot, gap included, glides the other button into place rather than
    // leaving it to jump once this one has faded out.
    AnimatedVisibility(
        visible,
        enter = fadeIn() + scaleIn() + expandVertically(clip = false),
        exit = fadeOut() + scaleOut() + shrinkVertically(clip = false),
    ) {
        SmallFloatingActionButton(onClick, Modifier.padding(top = 8.dp)) { Icon(icon, contentDescription = label) }
    }
}

/** [entry] the way `fly logs` prints it, with the message as it came rather than as shown. */
private fun asFlyLogs(entry: FlyLogEntry): String =
    "${entry.timestamp} app[${entry.instance}] ${entry.region} [${entry.level}] ${entry.message}"

/** One line, with each place [query] matches marked. */
@Composable
private fun LogLine(
    item: LogItem,
    query: String,
    showInstance: Boolean,
    onCopy: () -> Unit,
) {
    val colors = LocalStatusColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val entry = item.entry
    val line = item.line
    val messageColor =
        when (severity(line.level)) {
            Severity.Error -> colors.error
            Severity.Warning -> colors.warning
            null -> MaterialTheme.colorScheme.onSurface
        }
    val mark = SpanStyle(background = colors.warning.copy(alpha = 0.3f))
    val text =
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) {
                append(instantOf(entry.timestamp)?.let(::clockTime) ?: entry.timestamp)
                append(' ')
                append(entry.region)
                if (showInstance) append(" ${entry.instance.take(8)}")
                append("  ")
            }
            withStyle(SpanStyle(color = messageColor)) { append(line.message) }
            if (line.fields.isNotEmpty()) withStyle(SpanStyle(color = muted)) { append("  ${line.fields}") }
            if (query.isNotEmpty()) {
                val shown = toAnnotatedString().text
                var at = shown.indexOf(query, ignoreCase = true)
                while (at >= 0) {
                    addStyle(mark, at, at + query.length)
                    at = shown.indexOf(query, at + query.length, ignoreCase = true)
                }
            }
        }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(Res.string.logs_copy), onClick = onCopy),
    )
}

private class ReadableLine(
    val message: String,
    val fields: String,
    val level: String,
)

private val LOG_JSON = Json { isLenient = true }

private val SHOWN_ELSEWHERE = setOf("time", "ts", "timestamp", "level", "lvl", "severity", "msg", "message")

/** A logfmt line's level, such as Go's slog text handler writes: `level=WARN`. */
private val LOGFMT_LEVEL = Regex("""(?:^|\s)(?:level|lvl)="?(\w+)""")

/**
 * A structured (JSON) log line as its message followed by `key=value` pairs, the way logfmt reads;
 * anything else as it came. Its own level, when it has one, JSON or logfmt, wins over the platform's.
 */
private fun readable(entry: FlyLogEntry): ReadableLine {
    val raw = entry.message.trim()
    val json = if (raw.startsWith('{')) runCatching { LOG_JSON.parseToJsonElement(raw).jsonObject }.getOrNull() else null
    if (json == null) {
        val level = LOGFMT_LEVEL.find(raw)?.groupValues?.get(1) ?: entry.level
        return ReadableLine(entry.message, "", level)
    }

    fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
    val message = text("msg") ?: text("message") ?: return ReadableLine(entry.message, "", entry.level)
    val fields =
        json.entries
            .filter { it.key !in SHOWN_ELSEWHERE }
            .joinToString(" ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v.toString()}" }
    return ReadableLine(message, fields, text("level") ?: text("lvl") ?: text("severity") ?: entry.level)
}
