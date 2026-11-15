package dev.ggoggam.hangar.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import dev.ggoggam.hangar.app.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The screens the bottom bar switches between. */
enum class HomeTab(
    val route: Route,
    val label: StringResource,
    val icon: ImageVector,
) {
    Apps(Route.Apps, Res.string.nav_apps, Lucide.LayoutGrid),
    Data(Route.Storage, Res.string.nav_data, Lucide.Database),
    Settings(Route.Settings, Res.string.nav_settings, Lucide.Settings),
}

/**
 * The gap under the bar: the navigation bar, or a margin where that is thinner, so that the bar
 * floats on a phone with no navigation bar to clear as well.
 */
@Composable
fun homeBarBottom(): Dp = max(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), BAR_MARGIN)

/** Room at the end of a tab's list, which runs under the bar, for its last row to scroll clear of it. */
@Composable
fun homeBarClearance(): Dp = homeBarBottom() + BAR_HEIGHT + BAR_CLEARANCE

/**
 * The tabs, floating along the bottom of the app in an island: the same as Sokuri's dock, without
 * its action button.
 *
 * It is drawn over the screens rather than in each one, so its marker slides from tab to tab while the
 * screens fade under it, and sits at the bottom of the space [modifier] gives it. It sinks away under a
 * pushed screen ([current] is null there), which has its own way back. Nothing here takes a touch
 * outside the island, so whatever runs under it can still be touched beside it.
 */
@Composable
fun HomeBar(
    current: HomeTab?,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = current != null,
            enter = slideInVertically(tween(BAR_MOVE_MS)) { it * 2 } + fadeIn(tween(BAR_MOVE_MS)),
            exit = slideOutVertically(tween(BAR_MOVE_MS)) { it * 2 } + fadeOut(tween(BAR_MOVE_MS)),
            modifier =
                Modifier
                    .widthIn(max = BAR_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = BAR_MARGIN, end = BAR_MARGIN, bottom = homeBarBottom()),
        ) {
            // Kept while the island sinks away, so its marker stays on the tab it left from.
            var shown by remember { mutableStateOf(HomeTab.Apps) }
            if (current != null) shown = current
            TabIsland(shown) { if (it != current) onSelect(it) }
        }
    }
}

/**
 * A plain pill of the app's own surface; ink and a grey marker say which tab is open, and the marker
 * travels rather than jumping.
 */
@Composable
private fun TabIsland(
    current: HomeTab,
    onSelect: (HomeTab) -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(ISLAND_PADDING)) {
            val cell = maxWidth / HomeTab.entries.size
            val marker by animateDpAsState(
                cell * current.ordinal,
                spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                label = "island",
            )
            Box(
                modifier =
                    Modifier
                        .offset(x = marker)
                        .width(cell)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            )
            Row(modifier = Modifier.fillMaxSize().selectableGroup()) {
                HomeTab.entries.forEach { tab ->
                    val selected = tab == current
                    val color by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "tab",
                    )
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(tab.label),
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

private val BAR_HEIGHT = 60.dp
private val BAR_MARGIN = 16.dp

/** Wide enough for any phone; on a tablet the island stops growing and sits in the middle. */
private val BAR_MAX_WIDTH = 480.dp

/** Between a tab's last row and the island over it. */
private val BAR_CLEARANCE = 24.dp
private val ISLAND_PADDING = 4.dp

/** As long as a pushed screen takes to slide, so the island moves with it. */
private const val BAR_MOVE_MS = 300
