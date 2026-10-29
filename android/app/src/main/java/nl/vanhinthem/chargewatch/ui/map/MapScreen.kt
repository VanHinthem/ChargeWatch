package nl.vanhinthem.chargewatch.ui.map

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Streetview
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color as ComposeColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import nl.vanhinthem.chargewatch.data.enrichment.EnrichmentResult
import nl.vanhinthem.chargewatch.data.enrichment.LocationEnrichmentClient
import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
import nl.vanhinthem.chargewatch.data.search.LocationSearchClient
import nl.vanhinthem.chargewatch.data.search.SearchLocation
import nl.vanhinthem.chargewatch.data.search.SearchResult
import nl.vanhinthem.chargewatch.data.search.Destination
import nl.vanhinthem.chargewatch.data.search.DestinationSuggestion
import nl.vanhinthem.chargewatch.data.search.DestinationSuggestionsResult
import nl.vanhinthem.chargewatch.data.search.DestinationLookupResult
import nl.vanhinthem.chargewatch.data.search.PdokDestinationClient
import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.NdwAvailability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.formatChargingPower
import nl.vanhinthem.chargewatch.data.ndw.availabilityDescription
import nl.vanhinthem.chargewatch.data.ndw.connectorDisplayName
import nl.vanhinthem.chargewatch.data.ndw.connectorSymbol
import nl.vanhinthem.chargewatch.data.ndw.NdwViewport
import nl.vanhinthem.chargewatch.data.ndw.summarizeCluster
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

@Composable
fun MapScreen(
    favorites: List<FavoriteLocation> = emptyList(),
    onToggleFavorite: (MapLocation) -> Unit = {},
    onDetailVisibilityChanged: (Boolean) -> Unit = {},
    darkTheme: Boolean = false,
    viewModel: MapViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nearestClient = remember { LocationSearchClient() }
    val pdokClient = remember { PdokDestinationClient() }
    var selectedLocations by remember { mutableStateOf<List<MapLocation>>(emptyList()) }
    var searchHeaderBottom by remember { mutableStateOf(0.dp) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<DestinationSuggestion>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var searchSelected by remember { mutableStateOf(false) }
    var selectedSuggestion by remember { mutableStateOf<DestinationSuggestion?>(null) }
    var destination by remember { mutableStateOf<Destination?>(null) }
    var cameraTarget by remember { mutableStateOf<CameraTarget?>(null) }
    var mapCenter by remember { mutableStateOf(51.765 to 5.525) }
    var nearLocations by remember { mutableStateOf<List<SearchLocation>>(emptyList()) }
    var nearLoading by remember { mutableStateOf(false) }
    var nearMessage by remember { mutableStateOf<String?>(null) }
    var pendingSearchFeatureId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(searchQuery, searchSelected) {
        val query = searchQuery.trim()
        searchResults = emptyList()
        if (searchSelected || query.length < 2) {
            searchLoading = false
            searchMessage = null
            return@LaunchedEffect
        }
        searchMessage = null
        delay(250)
        searchLoading = true
        try {
            when (val response = pdokClient.suggest(query, mapCenter.first, mapCenter.second)) {
                is DestinationSuggestionsResult.Success -> {
                    searchResults = response.items
                    searchMessage = if (response.items.isEmpty()) "Geen Nederlandse adressen gevonden." else null
                }
                is DestinationSuggestionsResult.Failure -> searchMessage = response.message
            }
        } finally {
            searchLoading = false
        }
    }

    LaunchedEffect(selectedSuggestion) {
        val suggestion = selectedSuggestion ?: return@LaunchedEffect
        searchLoading = true
        searchMessage = null
        try {
            when (val response = pdokClient.lookup(suggestion)) {
                is DestinationLookupResult.Success -> {
                    destination = response.destination
                    cameraTarget = CameraTarget(response.destination.latitude, response.destination.longitude, 14.0)
                }
                is DestinationLookupResult.Failure -> searchMessage = response.message
            }
        } finally {
            searchLoading = false
        }
    }

    LaunchedEffect(destination) {
        val chosen = destination
        if (chosen == null) {
            nearLocations = emptyList()
            nearMessage = null
            nearLoading = false
            return@LaunchedEffect
        }
        nearLoading = true
        nearLocations = emptyList()
        nearMessage = null
        try {
            when (val response = nearestClient.nearby(chosen.latitude, chosen.longitude)) {
                is SearchResult.Success -> {
                    nearLocations = response.locations
                    nearMessage = if (response.locations.isEmpty()) "Geen laadlocaties binnen 25 km." else null
                }
                is SearchResult.Failure -> nearMessage = response.message
            }
        } finally {
            nearLoading = false
        }
    }

    LaunchedEffect(state.locations, pendingSearchFeatureId) {
        val featureId = pendingSearchFeatureId ?: return@LaunchedEffect
        state.locations.firstOrNull { it.featureId == featureId }?.let {
            selectedLocations = listOf(it)
            pendingSearchFeatureId = null
        }
    }

    fun onQueryChanged(value: String) {
        searchQuery = value
        searchSelected = false
        selectedSuggestion = null
        destination = null
        pendingSearchFeatureId = null
        nearLocations = emptyList()
    }

    fun selectSearchResult(item: DestinationSuggestion) {
        searchQuery = item.label
        searchSelected = true
        searchResults = emptyList()
        searchMessage = null
        selectedLocations = emptyList()
        selectedSuggestion = item
    }

    fun selectNearestCharger(item: SearchLocation) {
        val loaded = state.locations.firstOrNull { it.featureId == item.featureId }
        if (loaded != null) selectedLocations = listOf(loaded)
        else {
            pendingSearchFeatureId = item.featureId
            cameraTarget = CameraTarget(item.latitude, item.longitude, 16.0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ChargeMap(
            locations = state.locations,
            darkTheme = darkTheme,
            onViewportChanged = viewModel::onViewportChanged,
            onLocationsSelected = { selectedLocations = it },
            favoriteIds = favorites.map { it.featureId }.toSet(),
            cameraTarget = cameraTarget,
            destination = destination,
            onMapCenterChanged = { lat, lon ->
                // Do not redraw all markers for insignificant camera movement.
                val region = (lat * 10).roundToInt() / 10.0 to (lon * 10).roundToInt() / 10.0
                if (mapCenter != region) mapCenter = region
            },
            onCameraTargetConsumed = { cameraTarget = null }
        )

        SearchHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            query = searchQuery,
            onQueryChange = ::onQueryChanged,
            results = searchResults,
            loading = searchLoading,
            message = searchMessage,
            searchSelected = searchSelected,
            destination = destination,
            nearLocations = nearLocations,
            nearLoading = nearLoading,
            nearMessage = nearMessage,
            onNearestSelected = ::selectNearestCharger,
            onResultSelected = ::selectSearchResult,
            onBottomChanged = { searchHeaderBottom = it }
        )

        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 76.dp, end = 20.dp)
            )
        }

        state.message?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 4.dp
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    if (selectedLocations.isNotEmpty()) {
        LocationSelectionSheet(
            locations = selectedLocations,
            onDismiss = { selectedLocations = emptyList() },
            onLocationSelected = { selectedLocations = listOf(it) },
            isFavorite = { location -> favorites.any { it.featureId == location.featureId } },
            onToggleFavorite = onToggleFavorite,
            expandedTopInset = searchHeaderBottom + 8.dp
        )
    }


}

@Composable
private fun SearchHeader(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<DestinationSuggestion>,
    loading: Boolean,
    message: String?,
    searchSelected: Boolean,
    destination: Destination?,
    nearLocations: List<SearchLocation>,
    nearLoading: Boolean,
    nearMessage: String?,
    onNearestSelected: (SearchLocation) -> Unit,
    onResultSelected: (DestinationSuggestion) -> Unit,
    onBottomChanged: (Dp) -> Unit = {}
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val suggestVisible = focused && !searchSelected && query.trim().length >= 2

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                onBottomChanged(with(density) { coordinates.boundsInRoot().bottom.toDp() })
            },
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 5.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (suggestVisible) {
                            results.firstOrNull()?.let {
                                onResultSelected(it)
                                focused = false
                                focusManager.clearFocus()
                            }
                        } else focusManager.clearFocus()
                    }),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                Text("Zoek adres of plaats", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                            innerTextField()
                        }
                    }
                )
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        onQueryChange("")
                        focusManager.clearFocus()
                        focused = false
                    }) { Icon(Icons.Default.Close, contentDescription = "Zoekopdracht wissen") }
                }
                // Filter implementation is phase B of issue #95.
                Icon(Icons.Default.FilterList, contentDescription = "Filters (volgt later)")
            }
        }

        if (suggestVisible && (results.isNotEmpty() || message != null || loading)) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), tonalElevation = 6.dp) {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)) {
                    results.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            onResultSelected(item)
                            focused = false
                            focusManager.clearFocus()
                        }.padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Navigation, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.label, style = MaterialTheme.typography.titleSmall)
                                Text(when (item.type) {
                                    "adres" -> "Adres"
                                    "postcode" -> "Postcode"
                                    "weg" -> "Straat"
                                    "woonplaats" -> "Plaats"
                                    "gemeente" -> "Gemeente"
                                    else -> "Bestemming"
                                }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (results.isEmpty() && !loading && message != null) {
                        Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (searchSelected && destination == null && !loading && message != null) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Text(message, modifier = Modifier.padding(16.dp))
            }
        }
        if (destination != null) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), tonalElevation = 6.dp) {
                Column(modifier = Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)) {
                    Text("Dichtstbijzijnde laadlocaties",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall)
                    if (nearLoading) {
                        Text("Laadlocaties zoeken…",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall)
                    } else if (nearMessage != null) {
                        Text(nearMessage,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    nearLocations.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth().clickable { onNearestSelected(item) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.EvStation, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.operatorName?.takeIf { it.isNotBlank() }
                                    ?: item.address.ifBlank { "Laadlocatie" },
                                    style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(item.address.takeIf { it.isNotBlank() },
                                    item.city.takeIf { it.isNotBlank() }).joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                item.distanceMeters?.let { meters ->
                                    Text(if (meters < 1000) "${meters.toInt()} m hemelsbreed"
                                        else String.format(Locale.forLanguageTag("nl-NL"), "%.1f km hemelsbreed", meters / 1000.0),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(if (item.unknownCount > 0 || item.totalCount <= 0 || item.feedIsStale != false) "?"
                                else "${item.availableCount}/${item.totalCount}",
                                style = MaterialTheme.typography.titleSmall,
                                color = when {
                                    item.unknownCount > 0 || item.totalCount <= 0 || item.feedIsStale != false -> ComposeColor(0xFF818D96)
                                    item.availableCount > 0 -> ComposeColor(0xFF168653)
                                    else -> ComposeColor(0xFFD43B3B)
                                })
                        }
                    }
                }
            }
        }
    }
}

private data class CameraTarget(val latitude: Double, val longitude: Double, val zoom: Double)

/** Not Compose state: avoid marker re-clustering for each autocomplete keystroke. */
private class MapRenderSnapshot {
    var locations: List<MapLocation>? = null
    var favoriteIds: Set<String>? = null
    var destination: Destination? = null
}

@Composable
private fun ChargeMap(
    locations: List<MapLocation>,
    darkTheme: Boolean,
    onViewportChanged: (NdwViewport) -> Unit,
    onLocationsSelected: (List<MapLocation>) -> Unit,
    favoriteIds: Set<String>,
    cameraTarget: CameraTarget?,
    destination: Destination?,
    onMapCenterChanged: (Double, Double) -> Unit,
    onCameraTargetConsumed: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val styleUri = if (darkTheme) DARK_STYLE else LIGHT_STYLE
    val currentLocations = rememberUpdatedState(locations)
    val currentFavoriteIds = rememberUpdatedState(favoriteIds)
    val currentSelectionHandler = rememberUpdatedState(onLocationsSelected)
    val currentDestination = rememberUpdatedState(destination)
    val currentMapCenterHandler = rememberUpdatedState(onMapCenterChanged)
    val iconCache = remember { mutableMapOf<String, Icon>() }
    val markerGroups = remember { mutableMapOf<Long, List<MapLocation>>() }

    val mapView = remember(context) {
        MapView(context).apply { onCreate(Bundle()) }
    }

    var currentStyleUri by remember(mapView) { mutableStateOf<String?>(null) }
    val renderSnapshot = remember(mapView) { MapRenderSnapshot() }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mapView.onStart()
            override fun onResume(owner: LifecycleOwner) = mapView.onResume()
            override fun onPause(owner: LifecycleOwner) = mapView.onPause()
            override fun onStop(owner: LifecycleOwner) = mapView.onStop()
            override fun onDestroy(owner: LifecycleOwner) = mapView.onDestroy()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(51.765, 5.525))
                        .zoom(12.0)
                        .build()

                    map.setOnMarkerClickListener { marker ->
                        val group = markerGroups[marker.id] ?: return@setOnMarkerClickListener false
                        if (group.isEmpty()) return@setOnMarkerClickListener true
                        when (markerTapAction(group.size, map.cameraPosition.zoom, MAX_CLUSTER_ZOOM)) {
                            MarkerTapAction.SelectLocation,
                            MarkerTapAction.SelectClusterLocations -> currentSelectionHandler.value(group)
                            MarkerTapAction.ZoomCluster -> {
                                val latitude = group.map { it.latitude }.average()
                                val longitude = group.map { it.longitude }.average()
                                val targetZoom = (map.cameraPosition.zoom + CLUSTER_ZOOM_STEP)
                                    .coerceAtMost(MAX_CLUSTER_ZOOM)
                                map.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(latitude, longitude),
                                        targetZoom
                                    )
                                )
                            }
                        }
                        true
                    }
                    map.addOnMapClickListener {
                        currentSelectionHandler.value(emptyList())
                        false
                    }

                    currentStyleUri = styleUri
                    configureStyle(
                        map = map,
                        styleUri = styleUri,
                        locations = currentLocations.value,
                        onViewportChanged = onViewportChanged,
                        context = context,
                        iconCache = iconCache,
                        markerGroups = markerGroups,
                        favoriteIds = currentFavoriteIds.value,
                        destination = currentDestination.value
                    )
                    map.getStyle {
                        renderNativeMarkers(
                            map,
                            currentLocations.value,
                            iconCache,
                            context,
                            markerGroups,
                            currentFavoriteIds.value,
                            currentDestination.value
                        )
                    }
                    map.addOnCameraIdleListener {
                        renderNativeMarkers(
                            map,
                            currentLocations.value,
                            iconCache,
                            context,
                            markerGroups,
                            currentFavoriteIds.value,
                            currentDestination.value
                        )
                        map.cameraPosition.target?.let { center ->
                            currentMapCenterHandler.value(center.latitude, center.longitude)
                        }
                        requestViewport(map, onViewportChanged)
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                cameraTarget?.let { target ->
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(target.latitude, target.longitude),
                            target.zoom
                        )
                    )
                    onCameraTargetConsumed()
                }
                val styleChanged = currentStyleUri != styleUri
                val markersChanged = renderSnapshot.locations !== locations ||
                    renderSnapshot.favoriteIds != favoriteIds ||
                    renderSnapshot.destination != destination
                if (styleChanged) {
                    currentStyleUri = styleUri
                    map.setStyle(styleUri) {
                        renderNativeMarkers(map, locations, iconCache, context, markerGroups, favoriteIds, destination)
                    }
                } else if (markersChanged) {
                    map.getStyle {
                        renderNativeMarkers(map, locations, iconCache, context, markerGroups, favoriteIds, destination)
                    }
                }
                if (styleChanged || markersChanged) {
                    renderSnapshot.locations = locations
                    renderSnapshot.favoriteIds = favoriteIds
                    renderSnapshot.destination = destination
                }
            }
        }
    )
}

private fun configureStyle(
    map: MapLibreMap,
    styleUri: String,
    locations: List<MapLocation>,
    onViewportChanged: (NdwViewport) -> Unit,
    context: Context,
    iconCache: MutableMap<String, Icon>,
    markerGroups: MutableMap<Long, List<MapLocation>>,
    favoriteIds: Set<String>,
    destination: Destination?
) {
    map.setStyle(styleUri) {
        renderNativeMarkers(map, locations, iconCache, context, markerGroups, favoriteIds, destination)
        requestViewport(map, onViewportChanged)
    }
}

private fun requestViewport(map: MapLibreMap, onViewportChanged: (NdwViewport) -> Unit) {
    val bounds = map.projection.visibleRegion.latLngBounds
    if (bounds.longitudeWest < bounds.longitudeEast &&
        bounds.latitudeSouth < bounds.latitudeNorth
    ) {
        onViewportChanged(
            NdwViewport(
                minLongitude = bounds.longitudeWest,
                minLatitude = bounds.latitudeSouth,
                maxLongitude = bounds.longitudeEast,
                maxLatitude = bounds.latitudeNorth
            )
        )
    }
}

private fun renderNativeMarkers(
    map: MapLibreMap,
    locations: List<MapLocation>,
    icons: MutableMap<String, Icon>,
    context: Context,
    markerGroups: MutableMap<Long, List<MapLocation>>,
    favoriteIds: Set<String>,
    destination: Destination?
) {
    map.clear()
    markerGroups.clear()
    val clusters = mutableListOf<MutableList<MapLocation>>()
    val positions = locations.associateWith { location ->
        map.projection.toScreenLocation(LatLng(location.latitude, location.longitude))
    }
    locations.forEach { location ->
        val point = positions.getValue(location)
        val nearest = clusters.minByOrNull { group ->
            val x = group.map { positions.getValue(it).x.toDouble() }.average()
            val y = group.map { positions.getValue(it).y.toDouble() }.average()
            val dx = x - point.x.toDouble()
            val dy = y - point.y.toDouble()
            dx * dx + dy * dy
        }
        val distanceSquared = nearest?.let { group ->
            val x = group.map { positions.getValue(it).x.toDouble() }.average()
            val y = group.map { positions.getValue(it).y.toDouble() }.average()
            val dx = x - point.x.toDouble()
            val dy = y - point.y.toDouble()
            dx * dx + dy * dy
        } ?: Double.POSITIVE_INFINITY
        if (nearest != null && distanceSquared < CLUSTER_DISTANCE_PX * CLUSTER_DISTANCE_PX) {
            nearest.add(location)
        } else {
            clusters.add(mutableListOf(location))
        }
    }

    var merged: Boolean
    do {
        merged = false
        outer@ for (i in clusters.indices) {
            for (j in i + 1 until clusters.size) {
                val a = clusters[i]
                val b = clusters[j]
                val dx = a.map { positions.getValue(it).x.toDouble() }.average() -
                    b.map { positions.getValue(it).x.toDouble() }.average()
                val dy = a.map { positions.getValue(it).y.toDouble() }.average() -
                    b.map { positions.getValue(it).y.toDouble() }.average()
                if (dx * dx + dy * dy < CLUSTER_DISTANCE_PX * CLUSTER_DISTANCE_PX) {
                    a.addAll(b)
                    clusters.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
    } while (merged)

    val iconFactory = IconFactory.getInstance(context)
    clusters.forEach { group ->
        val isCluster = group.size > 1
        val location = group.first()
        val aggregate = if (isCluster) summarizeCluster(group) else null
        val label = aggregate?.label ?: location.availability.label
        val displayStatus = aggregate?.status ?: location.availability.status
        val color = when (displayStatus) {
            AvailabilityStatus.Available -> AVAILABLE_COLOR
            AvailabilityStatus.NoneAvailable -> NONE_AVAILABLE_COLOR
            AvailabilityStatus.Unknown -> UNKNOWN_COLOR
        }
        val isFavorite = group.any { it.featureId in favoriteIds }
        val key = isCluster.toString() + ":" + color + ":" + label + ":" + isFavorite
        val icon = icons.getOrPut(key) {
            iconFactory.fromBitmap(
                if (isCluster) createClusterBitmap(color, label, isFavorite)
                else createMarkerBitmap(color, label, isFavorite)
            )
        }
        val latitude = group.map { it.latitude }.average()
        val longitude = group.map { it.longitude }.average()
        val marker = map.addMarker(
            MarkerOptions()
                .position(LatLng(latitude, longitude))
                .icon(icon)
                .title(if (isCluster) group.size.toString() + " laadlocaties" else location.title)
        )
        markerGroups[marker.id] = group.toList()
    }
    destination?.let { chosen ->
        val icon = icons.getOrPut("destination-pin") { iconFactory.fromBitmap(createDestinationMarkerBitmap()) }
        val marker = map.addMarker(MarkerOptions().position(LatLng(chosen.latitude, chosen.longitude))
            .icon(icon).title(chosen.label))
        // Empty group marks this as a destination, not a charging site.
        markerGroups[marker.id] = emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSelectionSheet(
    locations: List<MapLocation>,
    onDismiss: () -> Unit,
    onLocationSelected: (MapLocation) -> Unit,
    isFavorite: (MapLocation) -> Boolean,
    onToggleFavorite: (MapLocation) -> Unit,
    expandedTopInset: Dp,
    initiallyExpanded: Boolean = false,
    compactAllowed: Boolean = true
) {
    val context = LocalContext.current
    val enrichmentClient = remember { LocationEnrichmentClient() }
    val selectedId = locations.singleOrNull()?.featureId
    var enrichment by remember(selectedId) { mutableStateOf<EnrichmentResult?>(null) }
    LaunchedEffect(selectedId) {
        enrichment = null
        if (selectedId != null) enrichment = enrichmentClient.load(selectedId)
    }
    // A standard bottom sheet supports an explicit peek height. That is what the
    // map UX needs: compact = exactly the summary content, expanded = all detail.
    val sheetState = rememberStandardBottomSheetState(
        initialValue = if (initiallyExpanded) SheetValue.Expanded else SheetValue.PartiallyExpanded,
        confirmValueChange = { next ->
            compactAllowed || next != SheetValue.PartiallyExpanded
        },
        skipHiddenState = false
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var compactContentHeight by remember(selectedId) { mutableStateOf(320.dp) }
    val dragHandleHeight = 32.dp
    val compactPeekHeight = compactContentHeight + dragHandleHeight
    val expanded = sheetState.currentValue == SheetValue.Expanded

    BackHandler {
        if (!compactAllowed) {
            onDismiss()
        } else if (expanded) {
            scope.launch { sheetState.partialExpand() }
        } else {
            onDismiss()
        }
    }
    LaunchedEffect(sheetState.currentValue) {
        if (sheetState.currentValue == SheetValue.Hidden) onDismiss()
    }

    BottomSheetScaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = expandedTopInset.coerceAtLeast(0.dp)),
        scaffoldState = scaffoldState,
        sheetPeekHeight = if (locations.size > 1) 300.dp else compactPeekHeight,
        sheetMaxWidth = Dp.Unspecified,
        containerColor = ComposeColor.Transparent,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetDragHandle = {
            Box(
                Modifier.fillMaxWidth().height(dragHandleHeight)
                    .clickable {
                        scope.launch {
                            if (expanded) {
                                if (compactAllowed) sheetState.partialExpand() else sheetState.hide()
                            } else {
                                sheetState.expand()
                            }
                        }
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    Modifier.padding(top = 10.dp).width(36.dp).height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50))
                )
            }
        },
        sheetContent = {
        if (locations.size > 1) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = locations.size.toString() + " laadlocaties",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Kies een locatie",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                locations.forEachIndexed { index, location ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLocationSelected(location) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(location.title, style = MaterialTheme.typography.titleMedium)
                            location.address
                                ?.takeIf { it != location.title }
                                ?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                        }
                        Text(
                            location.availability.label,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        } else {
            val location = locations.single()
            val statusColor = when (location.availability.status) {
                AvailabilityStatus.Available -> ComposeColor(0xFF2FA76B)
                AvailabilityStatus.NoneAvailable -> ComposeColor(0xFFE35D5B)
                AvailabilityStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                Modifier.fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            if (!expanded) {
                                compactContentHeight = with(density) {
                                    coordinates.size.height.toDp()
                                }
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(location.title, style = MaterialTheme.typography.titleLarge)
                        location.address?.takeIf { it != location.title }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        location.operatorName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { onToggleFavorite(location) }) {
                        val favorite = isFavorite(location)
                        Icon(
                            imageVector = if (favorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (favorite) "Verwijder favoriet" else "Voeg favoriet toe",
                            tint = if (favorite) ComposeColor(0xFFF3AF25)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.EvStation, contentDescription = null, tint = statusColor)
                    when (location.availability.status) {
                        AvailabilityStatus.Available -> {
                            Text(location.availability.label,
                                style = MaterialTheme.typography.titleMedium, color = statusColor)
                            Text("laadpunten vrij", style = MaterialTheme.typography.bodyMedium)
                        }
                        AvailabilityStatus.NoneAvailable -> {
                            Text(
                                location.availability.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = statusColor
                            )
                            Text(
                                "Geen laadpunten vrij",
                                style = MaterialTheme.typography.titleMedium,
                                color = statusColor
                            )
                        }
                        AvailabilityStatus.Unknown -> Text(
                            "Beschikbaarheid onbekend",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // The summary is always visible, not only when the sheet is expanded.
                // Never imply a single price when tariff components, VAT or conditions differ.
                val connectors = location.connectorCategories
                val detailTariffs = (enrichment as? EnrichmentResult.Found)?.value
                val energyPrices = detailTariffs?.tariffs.orEmpty()
                    .filter { it.type == "ENERGY" }
                    .distinctBy {
                        listOf(
                            it.inclusiveAmount, it.currency, it.vat.orEmpty(),
                            it.hasRestrictions.toString(), it.optionStatus
                        )
                    }
                val hasExtraComponents = detailTariffs?.tariffs.orEmpty().any { it.type != "ENERGY" }
                val compactProfileSelectionOnly = detailTariffs?.let { detail ->
                    "TARIFF_TYPE_SELECTION_REQUIRED" in detail.tariffLimitations &&
                        detail.sourceTariffTypes == setOf("REGULAR") &&
                        detail.tariffLimitations.all {
                            it == "TARIFF_TYPE_SELECTION_REQUIRED" || it == "CONDITIONAL"
                        }
                } == true
                val hasExtraConditions = hasExtraComponents ||
                    detailTariffs?.tariffs.orEmpty().any {
                        it.hasRestrictions && !compactProfileSelectionOnly
                    } ||
                    (detailTariffs?.hasComplexTariffs == true && !compactProfileSelectionOnly)
                if (!expanded) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = if (maxWidth >= 330.dp) 3 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            connectors.chunked(columns).forEach { connectorRow ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    connectorRow.forEach { connector ->
                                        Surface(
                                            modifier = if (connectors.size == 1)
                                                Modifier.widthIn(min = 160.dp, max = 190.dp)
                                            else Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            tonalElevation = 2.dp
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                ConnectorSocketIcon(connector.connectorType, sizeDp = 20)
                                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                    Text(
                                                        connectorPowerLabel(connector) ?: "— kW",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        connectorDisplayName(connector.connectorType),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (connectors.size > 1) {
                                        repeat(columns - connectorRow.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }

                            energyPrices.take(2).forEach { price ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.LocalOffer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Column {
                                        Text(
                                            (if (price.currency == "EUR") "€ " else "") +
                                                (if (price.vatKnown) price.inclusiveAmount else price.amount)
                                                    .replace('.', ',') +
                                                "/kWh" +
                                                (if (price.currency == "EUR") "" else " ${price.currency}"),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            if (price.vatKnown) "incl. btw" else "btw onbekend",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (energyPrices.size > 2) {
                                Text(
                                    "+${energyPrices.size - 2} tarieven bekijken",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (energyPrices.isEmpty() && detailTariffs != null) {
                                val message = when {
                                    "SOURCE_MISSING" in detailTariffs.tariffStatuses ->
                                        "Tariefreferentie ontbreekt in actuele bron"
                                    "UNAVAILABLE" in detailTariffs.tariffStatuses ->
                                        "Tarief momenteel niet toepasbaar"
                                    "MALFORMED" in detailTariffs.tariffStatuses ->
                                        "Tariefgegevens zijn onvolledig"
                                    else -> null
                                }
                                if (message != null) Text(
                                    message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (hasExtraConditions) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "Extra kosten of voorwaarden",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { openNavigation(context, location) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Navigeren")
                    }
                    OutlinedButton(onClick = { openStreetView(context, location) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Streetview, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Street View")
                    }
                }
                if (expanded) {
                    HorizontalDivider()
                    Text("Aansluitingen", style = MaterialTheme.typography.titleMedium)
                    if (location.connectorCategories.isEmpty()) {
                        Text("Geen aansluitgegevens beschikbaar")
                    } else {
                        location.connectorCategories.forEach { category ->
                            Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ConnectorSocketIcon(category.connectorType)
                                    Column(Modifier.weight(1f)) {
                                        Text(connectorDisplayName(category.connectorType), style = MaterialTheme.typography.titleMedium)
                                        val attributes = listOfNotNull(
                                            category.powerType?.uppercase()?.takeIf { it == "AC" || it == "DC" },
                                            connectorPowerLabel(category)
                                        ).joinToString(" · ")
                                        if (attributes.isNotEmpty()) Text(attributes,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    val count = if (category.available != null && category.total != null &&
                                        category.available >= 0 && category.total > 0 && category.available <= category.total)
                                        "${category.available}/${category.total}" else "?"
                                    Text(count, style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Tarieven", style = MaterialTheme.typography.titleMedium)
                    when (val result = enrichment) {
                        null -> Text("Tariefgegevens ophalen…")
                        EnrichmentResult.Unconfigured -> Text("Tarieven nog niet beschikbaar in deze testversie.")
                        EnrichmentResult.NotFound -> Text("Geen aanvullende tarieven voor deze locatie.")
                        EnrichmentResult.Unavailable -> Text("Tariefgegevens konden niet worden opgehaald.")
                        is EnrichmentResult.Found -> {
                            if (result.value.tariffs.isEmpty()) {
                                val message = when {
                                    "SOURCE_MISSING" in result.value.tariffStatuses ->
                                        "Tariefreferentie ontbreekt in de actuele NDW-bron."
                                    "UNAVAILABLE" in result.value.tariffStatuses ->
                                        "Het gekoppelde tarief is momenteel niet toepasbaar."
                                    "MALFORMED" in result.value.tariffStatuses ->
                                        "Tariefgegevens zijn onvolledig of ongeldig."
                                    else -> "Geen betrouwbaar tarief beschikbaar."
                                }
                                Text(message)
                            }
                            result.value.tariffs
                                .distinctBy { part ->
                                    listOf(
                                        part.type,
                                        part.inclusiveAmount,
                                        part.currency,
                                        part.vat.orEmpty(),
                                        part.hasRestrictions.toString(),
                                        part.optionStatus,
                                        part.sourceTariffType.orEmpty()
                                    )
                                }
                                .forEach { part ->
                                    val unit = when (part.type) {
                                        "ENERGY" -> "kWh"
                                        "TIME", "PARKING_TIME" -> "uur"
                                        else -> "keer"
                                    }
                                    val label = when (part.type) {
                                        "ENERGY" -> "Energie"
                                        "TIME" -> "Laadtijd"
                                        "PARKING_TIME" -> "Parkeertijd"
                                        else -> "Starttarief"
                                    }
                                    val taxNote = if (part.vatKnown) "incl. btw" else "btw niet opgegeven"
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top) {
                                        Column(Modifier.weight(1f)) {
                                            Text(label, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                taxNote,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(if (part.currency == "EUR") "€ ${part.inclusiveAmount.replace('.', ',')}/$unit"
                                             else "${part.inclusiveAmount} ${part.currency}/$unit",
                                            style = MaterialTheme.typography.titleSmall)
                                    }
                                }
                            if (result.value.tariffs.isNotEmpty())
                                Text("Prijscomponenten; je laadpasprovider kan een andere eindprijs berekenen.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val sourceTypes = result.value.sourceTariffTypes.sorted()
                            val profileSelectionOnly =
                                "TARIFF_TYPE_SELECTION_REQUIRED" in result.value.tariffLimitations &&
                                    result.value.tariffLimitations.all {
                                        it == "TARIFF_TYPE_SELECTION_REQUIRED" || it == "CONDITIONAL"
                                    }
                            if (profileSelectionOnly && sourceTypes.size == 1) {
                                Text(
                                    "Tariefprofiel: ${tariffTypeLabel(sourceTypes.single())}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                val visibleLimitations = result.value.tariffLimitations
                                    .let { limitations ->
                                        if ("TARIFF_TYPE_SELECTION_REQUIRED" in limitations)
                                            limitations - "CONDITIONAL"
                                        else limitations
                                    }
                                if (visibleLimitations.isNotEmpty()) {
                                    Text(
                                        "Tariefvoorwaarden",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    visibleLimitations.sorted().forEach { limitation ->
                                        val suffix = if (
                                            limitation == "TARIFF_TYPE_SELECTION_REQUIRED" &&
                                            sourceTypes.isNotEmpty()
                                        ) " (bronprofiel: ${sourceTypes.joinToString()})" else ""
                                        Text(
                                            "• ${tariffLimitationLabel(limitation)}$suffix",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else if (result.value.hasComplexTariffs) {
                                    Text(
                                        "Er zijn meerdere of niet volledig zekere tariefopties.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Historisch minstens één laadpunt vrij",
                        style = MaterialTheme.typography.titleMedium)
                    val weekdays = listOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo")
                    var chosenWeekday by remember(selectedId) {
                        mutableStateOf(LocalDate.now(ZoneId.of("Europe/Amsterdam")).dayOfWeek.value)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        weekdays.forEachIndexed { index, label ->
                            val weekday = index + 1
                            val selected = chosenWeekday == weekday
                            Surface(
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .clickable { chosenWeekday = weekday },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(label, style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold
                                            else androidx.compose.ui.text.font.FontWeight.Normal)
                                }
                            }
                        }
                    }
                    val historical = (enrichment as? EnrichmentResult.Found)?.value?.hourlyAvailability.orEmpty()
                        .filter { it.isoWeekday == chosenWeekday }
                    var selectedHour by remember(selectedId, chosenWeekday) { mutableStateOf<Int?>(null) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.height(88.dp), verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.End) {
                            Text("100%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("50%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("0%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth().height(88.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.Bottom) {
                                (0..23).forEach { hour ->
                                    val slot = historical.firstOrNull { it.hour == hour }
                                    Box(Modifier.weight(1f).fillMaxSize()
                                        .clickable { selectedHour = hour },
                                        contentAlignment = Alignment.BottomCenter) {
                                        if (slot == null) {
                                            Box(Modifier.fillMaxWidth().height(88.dp)
                                                .background(MaterialTheme.colorScheme.surfaceVariant,
                                                    RoundedCornerShape(2.dp)))
                                        } else {
                                            Box(Modifier.fillMaxWidth()
                                                .height((slot.share * 88).coerceAtLeast(2.0).dp)
                                                .background(MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(2.dp)))
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                listOf("00", "06", "12", "18", "24").forEach { label ->
                                    Text(label, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    selectedHour?.let { hour ->
                        val slot = historical.firstOrNull { it.hour == hour }
                        val hourLabel = "%02d:00–%02d:00".format(Locale.forLanguageTag("nl-NL"), hour, hour + 1)
                        Text(if (slot == null) "$hourLabel · Geen gegevens"
                             else "$hourLabel · ${(slot.share * 100).toInt()}% historische beschikbaarheidsindicatie",
                             style = MaterialTheme.typography.bodySmall)
                    }
                    val updated = remember(location.lastUpdated) {
                        location.lastUpdated?.let { raw ->
                            runCatching { DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("nl-NL"))
                                .withZone(ZoneId.systemDefault()).format(Instant.parse(raw)) }.getOrNull()
                        }
                    }
                    updated?.let { Text("NDW · bijgewerkt $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    Text(
                        "Veeg omhoog voor meer details",
                        modifier = Modifier.clickable { scope.launch { sheetState.expand() } },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        }
    ) {
        // Transparent scaffold body: the map and search header remain the underlying UI.
    }
}

@Composable
private fun AvailabilityText(location: MapLocation) {
    val statusText = when (location.availability.status) {
        AvailabilityStatus.Available -> "Beschikbaar"
        AvailabilityStatus.NoneAvailable -> "Geen vrije aansluiting"
        AvailabilityStatus.Unknown -> "Beschikbaarheid onbekend"
    }
    Column {
        Text(
            text = location.availability.label,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


private fun openNavigation(context: Context, location: MapLocation) {
    val label = Uri.encode(location.address ?: location.title)
    val uri = Uri.parse(
        "geo:0,0?q=" + location.latitude + "," + location.longitude + "(" + label + ")"
    )
    val intent = Intent(Intent.ACTION_VIEW, uri)
    context.startActivity(Intent.createChooser(intent, "Navigeren met"))
}

private fun openStreetView(context: Context, location: MapLocation) {
    val streetView = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("google.streetview:cbll=" + location.latitude + "," + location.longitude)
    ).setPackage(GOOGLE_MAPS_PACKAGE)

    if (streetView.resolveActivity(context.packageManager) != null) {
        context.startActivity(streetView)
        return
    }

    val browserFallback = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(
            "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=" +
                location.latitude + "," + location.longitude
        )
    )
    context.startActivity(browserFallback)
}

private fun createDestinationMarkerBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(96, 120, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2563EB") }
    val tip = Path().apply {
        moveTo(20f, 59f)
        lineTo(48f, 114f)
        lineTo(76f, 59f)
        close()
    }
    canvas.drawPath(tip, paint)
    canvas.drawCircle(48f, 43f, 36f, paint)
    paint.color = Color.WHITE
    canvas.drawCircle(48f, 43f, 15f, paint)
    return bitmap
}

private fun createMarkerBitmap(colorHex: String, label: String, isFavorite: Boolean): Bitmap {
    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val statusColor = Color.parseColor(colorHex)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = Color.argb(48, 0, 0, 0)
    canvas.drawCircle(64f, 69f, 49f, paint)

    paint.color = Color.parseColor("#F5F7F8")
    canvas.drawCircle(64f, 64f, 48f, paint)

    paint.color = statusColor
    canvas.drawCircle(64f, 64f, 44f, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.5f
    paint.color = Color.argb(84, 255, 255, 255)
    canvas.drawCircle(64f, 64f, 42.5f, paint)

    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = android.graphics.Typeface.create(
        "sans-serif-medium",
        android.graphics.Typeface.BOLD
    )
    paint.textSize = if (label.length > 4) 30f else 35f
    canvas.drawText(label, 64f, 76f, paint)

    if (isFavorite) drawFavoriteBadgeOnMarker(canvas, 64f, 64f, 44f)
    return bitmap
}

private fun createClusterBitmap(colorHex: String, label: String, isFavorite: Boolean): Bitmap {
    val bitmap = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val statusColor = Color.parseColor(colorHex)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = Color.argb(48, 0, 0, 0)
    canvas.drawCircle(75f, 82f, 61f, paint)

    // Cluster halo stays subtle even though the marker itself is slightly larger.
    paint.color = Color.argb(
        70,
        Color.red(statusColor),
        Color.green(statusColor),
        Color.blue(statusColor)
    )
    canvas.drawCircle(75f, 76f, 60f, paint)

    paint.color = Color.parseColor("#F5F7F8")
    canvas.drawCircle(75f, 76f, 53f, paint)

    paint.color = statusColor
    canvas.drawCircle(75f, 76f, 48f, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.5f
    paint.color = Color.argb(84, 255, 255, 255)
    canvas.drawCircle(75f, 76f, 46.5f, paint)

    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = android.graphics.Typeface.create(
        "sans-serif-medium",
        android.graphics.Typeface.BOLD
    )
    paint.textSize = if (label.length > 4) 31f else 37f
    canvas.drawText(label, 75f, 89f, paint)

    if (isFavorite) drawFavoriteBadgeOnMarker(canvas, 75f, 76f, 48f)
    return bitmap
}

private fun drawFavoriteBadgeOnMarker(
    canvas: Canvas,
    markerCenterX: Float,
    markerCenterY: Float,
    markerRadius: Float
) {
    // Keep the badge at the same normalized top-right position for both
    // individual markers and clusters, regardless of marker size.
    val badgeX = markerCenterX + markerRadius * 0.91f
    val badgeY = markerCenterY - markerRadius * 0.86f
    drawFavoriteBadge(canvas, badgeX, badgeY)
}

private fun drawFavoriteBadge(canvas: Canvas, centerX: Float, centerY: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Slight shadow so the yellow badge stays readable over both light and dark map tiles.
    paint.color = Color.argb(50, 0, 0, 0)
    canvas.drawCircle(centerX + 1.5f, centerY + 2f, 19f, paint)

    paint.color = Color.parseColor("#F3AF25")
    canvas.drawCircle(centerX, centerY, 18f, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.5f
    paint.color = Color.argb(150, 38, 49, 58)
    canvas.drawCircle(centerX, centerY, 17.5f, paint)

    val star = Path()
    val outerRadius = 10.8
    val innerRadius = 5.0
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val angle = -PI / 2 + i * PI / 5
        val x = centerX + (cos(angle) * radius).toFloat()
        val y = centerY + (sin(angle) * radius).toFloat()
        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()

    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    canvas.drawPath(star, paint)
}

private const val CLUSTER_DISTANCE_PX = 144.0
private const val CLUSTER_ZOOM_STEP = 2.0
private const val MAX_CLUSTER_ZOOM = 18.0
private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/fiord"
private const val AVAILABLE_COLOR = "#168653"
private const val NONE_AVAILABLE_COLOR = "#D43B3B"
private const val UNKNOWN_COLOR = "#818D96"

private fun tariffTypeLabel(type: String): String = when (type) {
    "REGULAR" -> "Regulier"
    "AD_HOC_PAYMENT" -> "Ad-hoc betalen"
    "PROFILE_CHEAP" -> "Goedkoop"
    "PROFILE_FAST" -> "Snel"
    "PROFILE_GREEN" -> "Groen"
    else -> type
}

private fun tariffLimitationLabel(code: String): String = when (code) {
    "CONDITIONAL" -> "Prijs hangt af van een sessievoorwaarde"
    "TARIFF_TYPE_SELECTION_REQUIRED" -> "NDW levert een specifiek tariefprofiel; ChargeWatch kiest dit niet automatisch"
    "UNSUPPORTED_TARIFF_TYPE" -> "Het brontarief gebruikt een onbekend tariefprofiel"
    "UNSUPPORTED_REQUESTED_TARIFF_TYPE" -> "Het gevraagde tariefprofiel wordt niet ondersteund"
    "UNKNOWN_RESTRICTION" -> "Het tarief bevat een voorwaarde die ChargeWatch nog niet interpreteert"
    "TIME_ZONE_REQUIRED" -> "Tijdzone is nodig om de tijdsvoorwaarde te beoordelen"
    "INVALID_RESTRICTION_VALUE" -> "Een tariefvoorwaarde heeft een ongeldige waarde"
    "INVALID_RESTRICTIONS" -> "De tariefvoorwaarden hebben een ongeldig formaat"
    "INVALID_ELEMENTS" -> "De tariefelementen hebben een ongeldig formaat"
    "INVALID_ELEMENT" -> "Een tariefelement is ongeldig"
    "INVALID_PRICE_COMPONENT" -> "Een prijscomponent is ongeldig"
    "INVALID_VAT" -> "Het btw-percentage is ongeldig"
    "INVALID_CURRENCY" -> "De valuta is ongeldig"
    "INVALID_VALIDITY" -> "De geldigheidsperiode is ongeldig"
    "OUTSIDE_VALIDITY" -> "Het tarief valt buiten zijn geldigheidsperiode"
    "SOURCE_TARIFF_NOT_FOUND" -> "De tariefreferentie ontbreekt in de actuele bron"
    "TARIFF_TYPE_MISMATCH" -> "Dit tariefprofiel past niet bij de gekozen optie"
    else -> "Niet volledig geïnterpreteerde tariefvoorwaarde"
}

/** Preserve NDW fallback behavior; mark API-derived values as estimates/bounds. */
private fun connectorPowerLabel(connector: NdwAvailability): String? {
    val effective = connector.effectivePowerWatts
    val text = formatChargingPower(effective?.toDouble() ?: connector.powerMax) ?: return null
    return when (connector.powerSource) {
        "derived_ac" -> "≈ $text"
        "dc_upper_bound" -> "≤ $text"
        else -> text
    }
}
