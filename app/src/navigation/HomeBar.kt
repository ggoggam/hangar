package dev.ggoggam.hangar.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.BarEdge
import dev.ggoggam.hangar.app.ui.barShadow
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

/** The bottom bar under the top-level screens, in the same neutral chrome as the rest of the app. */
@Composable
fun HomeBar(
    current: HomeTab,
    onSelect: (HomeTab) -> Unit,
) {
    Column(Modifier.barShadow(BarEdge.Top)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            HomeTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == current,
                    onClick = { if (tab != current) onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label), maxLines = 1) },
                )
            }
        }
    }
}
