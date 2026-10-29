package nl.vanhinthem.chargewatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import nl.vanhinthem.chargewatch.ui.theme.ChargeWatchTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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
import nl.vanhinthem.chargewatch.data.settings.ChargeWatchDataStatus
import nl.vanhinthem.chargewatch.data.settings.SettingsDataClient
import nl.vanhinthem.chargewatch.data.settings.SettingsDataResult
import nl.vanhinthem.chargewatch.ui.favorites.FavoritesScreen
import nl.vanhinthem.chargewatch.ui.map.MapScreen
import nl.vanhinthem.chargewatch.ui.map.LocationSelectionSheet
import nl.vanhinthem.chargewatch.ui.settings.SettingsScreen

@Composable
fun ChargeWatchApp() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var themeChoice by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    val useDarkTheme = when (themeChoice) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val selectTheme: (String) -> Unit = { selected ->
        themeChoice = selected
        prefs.edit().putString("theme", selected).apply()
    }
    val repository = remember(context) { FavoritesRepository(context) }
    val favorites by repository.favorites.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val mapViewModel: MapViewModel = viewModel()
    val mapState by mapViewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var detailOpen by remember { mutableStateOf(false) }
    var favoriteDetail by remember { mutableStateOf<MapLocation?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val settingsClient = remember { SettingsDataClient() }
    var settingsData by remember { mutableStateOf<ChargeWatchDataStatus?>(null) }
    var settingsLoading by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }

    val availabilityClient = remember { FavoritesAvailabilityClient() }
    var latestStatuses by remember { mutableStateOf<Map<String, MapLocation>>(emptyMap()) }
    var favoriteFetchedAt by remember { mutableStateOf<Instant?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var statusCheckedAt by remember { mutableStateOf(Instant.now()) }
    val mapStatuses = mapState.locations.associateBy { it.featureId }
    val statusView = favorites.associate { favorite ->
        val favoriteObserved = latestStatuses[favorite.featureId]
        val mapObserved = mapStatuses[favorite.featureId]
        fun feedObservedAt(location: MapLocation?): Instant? = try {
            location?.feedLastObservedAt?.let(Instant::parse)
        } catch (_: Exception) {
            null
        }
        val favoriteObservedAt = feedObservedAt(favoriteObserved)
        val mapObservedAt = feedObservedAt(mapObserved)
        val useMap = mapObserved != null &&
            (favoriteObservedAt == null ||
                (mapObservedAt?.isAfter(favoriteObservedAt) == true))
        val observed = if (useMap) mapObserved else favoriteObserved ?: mapObserved
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
                    refreshMessage = result.warning
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

    suspend fun refreshSettings() {
        if (settingsLoading) return
        settingsLoading = true
        settingsError = null
        try {
            when (val result = settingsClient.load()) {
                is SettingsDataResult.Success -> settingsData = result.status
                is SettingsDataResult.Failure -> settingsError = result.message
            }
        } catch (_: Exception) {
            settingsError = "Data-status kon niet worden opgehaald."
        } finally {
            settingsLoading = false
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
    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) refreshSettings()
    }


    ChargeWatchTheme(darkTheme = useDarkTheme) {
    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            if (selectedTab == 0) {
                MapScreen(
                    viewModel = mapViewModel,
                    darkTheme = useDarkTheme,
                    onDetailVisibilityChanged = { detailOpen = it },
                    favorites = favorites,
                    onToggleFavorite = { location -> scope.launch { repository.toggle(location) } }
                )
            } else if (selectedTab == 1) {
                FavoritesScreen(
                    favorites = favorites,
                    availability = statusView,
                    refreshing = refreshing,
                    refreshMessage = refreshMessage,
                    onRefresh = { scope.launch { refresh(force = true) } },
                    onRename = { id, name -> scope.launch { repository.rename(id, name) } },
                    onReorder = { id, targetIndex ->
                        scope.launch { repository.moveTo(id, targetIndex) }
                    },
                    onRemove = { item, index ->
                        scope.launch {
                            repository.remove(item.featureId)
                            val result = snackbarHostState.showSnackbar(
                                message = "Favoriet verwijderd",
                                actionLabel = "Ongedaan maken",
                                withDismissAction = true
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                repository.restore(item, index)
                            }
                        }
                    },
                    onOpen = { favorite ->
                        val cached = latestStatuses[favorite.featureId] ?: mapStatuses[favorite.featureId]
                        if (cached != null) {
                            favoriteDetail = cached
                        } else {
                            scope.launch {
                                when (val result = availabilityClient.refresh(listOf(favorite))) {
                                    is FavoriteRefreshResult.Success -> {
                                        latestStatuses = latestStatuses + result.locations
                                                            result.locations[favorite.featureId]?.let { favoriteDetail = it }
                                    }
                                    is FavoriteRefreshResult.Partial -> {
                                        latestStatuses = latestStatuses + result.locations
                                                            result.locations[favorite.featureId]?.let { favoriteDetail = it }
                                        refreshMessage = result.warning
                                    }
                                }
                            }
                        }
                    }
                )
            } else {
                SettingsScreen(
                    themeChoice = themeChoice,
                    onThemeChoice = selectTheme,
                    dataStatus = settingsData,
                    loading = settingsLoading,
                    error = settingsError,
                    onRefresh = { scope.launch { refreshSettings() } }
                )
            }

            favoriteDetail?.let { location ->
                LocationSelectionSheet(
                    locations = listOf(location),
                    onDismiss = { favoriteDetail = null },
                    onLocationSelected = { },
                    isFavorite = { item -> favorites.any { it.featureId == item.featureId } },
                    onToggleFavorite = { item -> scope.launch { repository.toggle(item) } },
                    expandedTopInset = 16.dp,
                    initiallyExpanded = true,
                    compactAllowed = false
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        if (!detailOpen && favoriteDetail == null) NavigationBar {
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
            NavigationBarItem(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Instellingen") }
            )
        }
    }
    }
}