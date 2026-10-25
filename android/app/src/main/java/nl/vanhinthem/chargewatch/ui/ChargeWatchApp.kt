package nl.vanhinthem.chargewatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import nl.vanhinthem.chargewatch.data.favorites.FavoritesRepository
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.AvailabilitySummary
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.ui.favorites.FavoritesScreen
import nl.vanhinthem.chargewatch.ui.map.MapScreen

@Composable
fun ChargeWatchApp() {
    val context = LocalContext.current
    val repository = remember(context) { FavoritesRepository(context) }
    val favorites by repository.favorites.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            if (selectedTab == 0) {
                MapScreen(
                    favorites = favorites,
                    onToggleFavorite = { location -> scope.launch { repository.toggle(location) } }
                )
            } else {
                FavoritesScreen(
                    favorites = favorites,
                    onRename = { id, name -> scope.launch { repository.rename(id, name) } },
                    onMove = { id, offset -> scope.launch { repository.move(id, offset) } },
                    onRemove = { item ->
                        scope.launch {
                            repository.toggle(
                                MapLocation(
                                    featureId = item.featureId,
                                    longitude = item.longitude,
                                    latitude = item.latitude,
                                    title = item.title,
                                    lastUpdated = null,
                                    availability = AvailabilitySummary(null, null, CountReliability.Ambiguous)
                                )
                            )
                        }
                    }
                )
            }
        }
        NavigationBar {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                icon = { Icon(Icons.Default.Map, contentDescription = null) },
                label = { Text("Kaart") }
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                icon = { Icon(Icons.Default.Star, contentDescription = null) },
                label = { Text("Favorieten") }
            )
        }
    }
}
