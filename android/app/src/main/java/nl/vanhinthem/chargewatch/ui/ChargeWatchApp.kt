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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.vanhinthem.chargewatch.ui.map.MapViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.vanhinthem.chargewatch.data.favorites.FavoritesRepository
import nl.vanhinthem.chargewatch.data.favorites.FavoritesAvailabilityClient
import nl.vanhinthem.chargewatch.data.favorites.FavoriteRefreshResult
import nl.vanhinthem.chargewatch.data.favorites.favoriteAvailability
import androidx.compose.runtime.mutableStateOf
import java.time.Instant
import nl.vanhinthem.chargewatch.data.favorites.favoritesNeedingFetch
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
    val mapViewModel: MapViewModel = viewModel()
    val mapState by mapViewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val availabilityClient = remember { FavoritesAvailabilityClient() }
    var latestStatuses by remember { mutableStateOf<Map<String, MapLocation>>(emptyMap()) }
    var favoriteFetchedAt by remember { mutableStateOf<Instant?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var statusCheckedAt by remember { mutableStateOf(Instant.now()) }
    val mapStatuses = mapState.locations.associateBy { it.featureId }
    val statusView = favorites.associate { favorite ->
        // Reuse current map observations immediately, while NDW fetches a refresh.
        val observed = latestStatuses[favorite.featureId] ?: mapStatuses[favorite.featureId]
        favorite.featureId to favoriteAvailability(observed, statusCheckedAt)
    }
    suspend fun refresh(force: Boolean = false) {
        val currentTime = Instant.now()
        val needed = if (force) favorites else favoritesNeedingFetch(
            favorites = favorites,
            mapLocations = mapStatuses,
            mapFetchedAt = mapState.fetchedAt,
            favoriteLocations = latestStatuses,
            favoriteFetchedAt = favoriteFetchedAt,
            now = currentTime
        )
        if (needed.isEmpty()) {
            refreshMessage = null
            return
        }
        refreshing = true
        refreshMessage = null
        try {
            when (val result = availabilityClient.refresh(needed)) {
                is FavoriteRefreshResult.Success -> {
                    latestStatuses = latestStatuses + result.locations
                    favoriteFetchedAt = Instant.now()
                }
                is FavoriteRefreshResult.Partial -> {
                    latestStatuses = latestStatuses + result.locations
                    refreshMessage = result.warning
                    // Partial responses are not considered fully checked, so retry is possible.
                }
            }
        } catch (_: Exception) {
            refreshMessage = "Beschikbaarheid kon niet worden opgehaald."
        } finally {
            statusCheckedAt = Instant.now()
            refreshing = false
        }
    }
    LaunchedEffect(selectedTab) {
        while (selectedTab == 1) {
            delay(60_000)
            statusCheckedAt = Instant.now()
        }
    }
    LaunchedEffect(selectedTab, favorites.map { it.featureId }) {
        if (selectedTab == 1 && favorites.isNotEmpty()) refresh()
    }


    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            if (selectedTab == 0) {
                MapScreen(
                    viewModel = mapViewModel,
                    favorites = favorites,
                    onToggleFavorite = { location -> scope.launch { repository.toggle(location) } }
                )
            } else {
                FavoritesScreen(
                    favorites = favorites,
                    availability = statusView,
                    refreshing = refreshing,
                    refreshMessage = refreshMessage,
                    onRefresh = { scope.launch { refresh(force = true) } },
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
