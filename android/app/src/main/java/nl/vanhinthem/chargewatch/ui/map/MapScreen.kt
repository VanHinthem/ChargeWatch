package nl.vanhinthem.chargewatch.ui.map

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Streetview
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
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
    viewModel: MapViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()
    var selectedLocations by remember { mutableStateOf<List<MapLocation>>(emptyList()) }
    var detailLocation by remember { mutableStateOf<MapLocation?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        ChargeMap(
            locations = state.locations,
            darkTheme = darkTheme,
            onViewportChanged = viewModel::onViewportChanged,
            onLocationsSelected = { selectedLocations = it },
            favoriteIds = favorites.map { it.featureId }.toSet()
        )

        SearchHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 14.dp)
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
            onDetails = {
                detailLocation = it
                selectedLocations = emptyList()
            },
            isFavorite = { location -> favorites.any { it.featureId == location.featureId } },
            onToggleFavorite = onToggleFavorite
        )
    }

    detailLocation?.let { location ->
        LocationDetailScreen(
            location = location,
            onBack = { detailLocation = null }
        )
    }
}

@Composable
private fun SearchHeader(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Text(
                text = "Zoek locatie of plaats",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(Icons.Default.FilterList, contentDescription = "Filters")
        }
    }
}

@Composable
private fun ChargeMap(
    locations: List<MapLocation>,
    darkTheme: Boolean,
    onViewportChanged: (NdwViewport) -> Unit,
    onLocationsSelected: (List<MapLocation>) -> Unit,
    favoriteIds: Set<String>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val styleUri = if (darkTheme) DARK_STYLE else LIGHT_STYLE
    val currentLocations = rememberUpdatedState(locations)
    val currentFavoriteIds = rememberUpdatedState(favoriteIds)
    val currentSelectionHandler = rememberUpdatedState(onLocationsSelected)
    val iconCache = remember { mutableMapOf<String, Icon>() }
    val markerGroups = remember { mutableMapOf<Long, List<MapLocation>>() }

    val mapView = remember(context, darkTheme) {
        MapView(context).apply { onCreate(Bundle()) }
    }

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

                    configureStyle(
                        map = map,
                        styleUri = styleUri,
                        locations = currentLocations.value,
                        onViewportChanged = onViewportChanged,
                        context = context,
                        iconCache = iconCache,
                        markerGroups = markerGroups,
                        favoriteIds = currentFavoriteIds.value
                    )
                    map.getStyle {
                        renderNativeMarkers(
                            map,
                            currentLocations.value,
                            iconCache,
                            context,
                            markerGroups,
                            currentFavoriteIds.value
                        )
                    }
                    map.addOnCameraIdleListener {
                        renderNativeMarkers(
                            map,
                            currentLocations.value,
                            iconCache,
                            context,
                            markerGroups,
                            currentFavoriteIds.value
                        )
                        requestViewport(map, onViewportChanged)
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                map.getStyle {
                    renderNativeMarkers(map, locations, iconCache, context, markerGroups, favoriteIds)
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
    favoriteIds: Set<String>
) {
    map.setStyle(styleUri) {
        renderNativeMarkers(map, locations, iconCache, context, markerGroups, favoriteIds)
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
    favoriteIds: Set<String>
) {
    map.clear()
    markerGroups.clear()
    if (locations.isEmpty()) return

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSelectionSheet(
    locations: List<MapLocation>,
    onDismiss: () -> Unit,
    onLocationSelected: (MapLocation) -> Unit,
    onDetails: (MapLocation) -> Unit,
    isFavorite: (MapLocation) -> Boolean,
    onToggleFavorite: (MapLocation) -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(location.title, style = MaterialTheme.typography.headlineSmall)
                location.operatorName
                    ?.takeIf { it != location.title }
                    ?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                location.address
                    ?.takeIf { it != location.title }
                    ?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                AvailabilityText(location)
                OutlinedButton(onClick = { onToggleFavorite(location) }) {
                    Icon(if (isFavorite(location)) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null)
                    Text(if (isFavorite(location)) " Verwijder favoriet" else " Voeg toe aan favorieten")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { openNavigation(context, location) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Text(" Navigeren")
                    }
                    OutlinedButton(
                        onClick = { openStreetView(context, location) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Streetview, contentDescription = null)
                        Text(" Street View")
                    }
                }
                OutlinedButton(
                    onClick = { onDetails(location) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Text(" Details")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
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

@Composable
private fun LocationDetailScreen(location: MapLocation, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Terug")
                }
                Text("Locatiedetails", style = MaterialTheme.typography.titleLarge)
            }
            Text(location.title, style = MaterialTheme.typography.headlineMedium)
            location.operatorName?.takeIf { it != location.title }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            location.address?.takeIf { it != location.title }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }

            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Beschikbaarheid volgens NDW", style = MaterialTheme.typography.titleMedium)
                    AvailabilityText(location)
                    Text(
                        "Laatst bekende status, geen garantie op actuele beschikbaarheid.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    location.lastUpdated?.let {
                        Text("Bronupdate: $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text("Aansluitingen", style = MaterialTheme.typography.titleLarge)
            if (location.connectorCategories.isEmpty()) {
                Text("Geen aansluitingsgegevens beschikbaar.")
            } else {
                Text(
                    "NDW-categorieën; categorieën kunnen dezelfde laadpunten omvatten en zijn niet afzonderlijk optelbaar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                location.connectorCategories.forEach { category ->
                    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            val name = listOfNotNull(
                                category.connectorType?.takeIf { it.isNotBlank() },
                                category.powerType?.takeIf { it.isNotBlank() }
                            ).joinToString(" · ").ifEmpty { "Type onbekend" }
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            category.powerMax?.takeIf { it > 0 }?.let {
                                Text("Maximaal ${it} kW", style = MaterialTheme.typography.bodyMedium)
                            }
                            val count = if (category.available != null && category.total != null &&
                                category.available >= 0 && category.total > 0 &&
                                category.available <= category.total) "${category.available}/${category.total}"
                                else "?"
                            Text("$count volgens NDW-categorie", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("Tarieven", style = MaterialTheme.typography.titleLarge)
            Text("Nog geen gevalideerde actuele tariefinformatie gekoppeld.")
            HorizontalDivider()
            Text("Populaire laadtijden", style = MaterialTheme.typography.titleLarge)
            Text("Nog geen gevalideerde historische gegevens voor deze locatie gekoppeld.")
            Text(
                "NDW-locatie: " + location.featureId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { openNavigation(context, location) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Navigation, contentDescription = null)
                    Text(" Navigeren")
                }
                OutlinedButton(onClick = { openStreetView(context, location) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Streetview, contentDescription = null)
                    Text(" Street View")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
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

private fun createMarkerBitmap(colorHex: String, label: String, isFavorite: Boolean): Bitmap {
    val bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.WHITE
    canvas.drawCircle(56f, 56f, 49f, paint)
    paint.color = Color.parseColor(colorHex)
    canvas.drawCircle(56f, 56f, 45f, paint)
    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    paint.textSize = if (label.length > 4) 29f else 34f
    canvas.drawText(label, 56f, 68f, paint)
    if (isFavorite) drawFavoriteStar(canvas, 91f, 26f)
    return bitmap
}

private fun createClusterBitmap(colorHex: String, label: String, isFavorite: Boolean): Bitmap {
    val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.WHITE
    canvas.drawCircle(70f, 76f, 63f, paint)
    paint.color = Color.parseColor(colorHex)
    canvas.drawCircle(70f, 76f, 57f, paint)
    paint.color = Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawCircle(70f, 76f, 49f, paint)
    paint.style = Paint.Style.FILL
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    paint.textSize = if (label.length > 4) 30f else 36f
    canvas.drawText(label, 70f, 88f, paint)
    if (isFavorite) drawFavoriteStar(canvas, 120f, 35f)
    return bitmap
}


// Large, outlined star stays legible on both light/dark tiles without covering the EVSE ratio.
private fun drawFavoriteStar(canvas: Canvas, centerX: Float, centerY: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        textSize = 49f
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#26313A")
    }
    canvas.drawText("★", centerX, centerY + 17f, paint)
    paint.style = Paint.Style.FILL
    paint.color = Color.parseColor("#F3AF25")
    canvas.drawText("★", centerX, centerY + 17f, paint)
}

private const val CLUSTER_DISTANCE_PX = 144.0
private const val CLUSTER_ZOOM_STEP = 2.0
private const val MAX_CLUSTER_ZOOM = 18.0
private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val AVAILABLE_COLOR = "#168653"
private const val NONE_AVAILABLE_COLOR = "#D43B3B"
private const val UNKNOWN_COLOR = "#818D96"
