package dev.ggoggam.hangar.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.BarEdge
import dev.ggoggam.hangar.app.ui.StatusBadge
import dev.ggoggam.hangar.app.ui.Tone
import dev.ggoggam.hangar.app.ui.barShadow
import org.jetbrains.compose.resources.stringResource

/** Fly.io's regions by continent, as `fly platform regions` lists them, with the nearest one marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionsScreen(
    vm: RegionsViewModel,
    onBack: () -> Unit,
) {
    val regions by vm.regions.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.barShadow(BarEdge.Bottom),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(Res.string.back)) } },
                title = { Text(stringResource(Res.string.settings_regions)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            rows(regions, "regions") { data ->
                // flyctl's order: continents alphabetically by their key, regions by name within each.
                data.regions.groupBy { it.geoRegion }.entries.sortedBy { it.key }.forEach { (geo, inGeo) ->
                    item(key = "geo:$geo") { Header(geoLabel(geo)) }
                    items(inGeo, key = { "region:${it.code}" }) { region ->
                        SettingRow(
                            region.name,
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (region.code == data.nearest) StatusBadge(stringResource(Res.string.regions_nearest), Tone.Ok)
                                    if (region.mpgAvailable) StatusBadge("MPG", Tone.Neutral)
                                    Text(
                                        region.code,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                        RowDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun geoLabel(geo: String): String =
    when (geo) {
        "africa" -> stringResource(Res.string.geo_africa)
        "asia_pacific" -> stringResource(Res.string.geo_asia_pacific)
        "europe" -> stringResource(Res.string.geo_europe)
        "north_america" -> stringResource(Res.string.geo_north_america)
        "south_america" -> stringResource(Res.string.geo_south_america)
        else -> geo
    }
