package nl.vanhinthem.chargewatch.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.NdwViewport
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()

    Box(modifier = Modifier.fillMaxSize()) {
        ChargeMap(
            locations = state.locations,
            darkTheme = darkTheme,
            onViewportChanged = viewModel::onViewportChanged
        )

        SearchHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
                Text(
                    text = state.diagnostic + if (state.locations.isNotEmpty()) " · Eerste punt: ${state.locations.first().latitude}, ${state.locations.first().longitude}" else "",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(onClick = viewModel::testNdwOss) { Text("Test NDW Oss") }
        }

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
    onViewportChanged: (NdwViewport) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val styleUri = if (darkTheme) DARK_STYLE else LIGHT_STYLE
    val currentLocations = rememberUpdatedState(locations)
    val iconCache = remember { mutableMapOf<String, Icon>() }

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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
                    configureStyle(map, styleUri, currentLocations.value, onViewportChanged, context, iconCache)
                    map.getStyle { renderNativeMarkers(map, currentLocations.value, iconCache, context) }
                    map.addOnCameraIdleListener {
                        renderNativeMarkers(map, currentLocations.value, iconCache, context)
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
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                map.getStyle { renderNativeMarkers(map, locations, iconCache, context) }
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
    iconCache: MutableMap<String, Icon>
) {
    map.setStyle(styleUri) { style ->
        renderNativeMarkers(map, locations, iconCache, context)
        // Camera-idle events are not guaranteed to fire on the initial map load.
        // Explicitly request the initial visible viewport once the map is ready.
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
}

// MapLibre native annotations render reliably on the tested device, unlike the
// previous custom GeoJSON source/layers. Clustering is visual only and NEVER
// aggregates connector or EVSE totals.
private fun renderNativeMarkers(
    map: MapLibreMap,
    locations: List<MapLocation>,
    icons: MutableMap<String, Icon>,
    context: Context
) {
    map.clear()
    if (locations.isEmpty()) return

    // Merge locations by actual on-screen distance, not 64-pixel grid cells.
    // A marker bitmap is 112 pixels wide, so keep group centers separated.
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

    // Joining points changes the centroid; merge adjacent final clusters again
    // to avoid the leftover marker-on-marker overlap seen on the device.
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
        val label = if (isCluster) "…" else location.availability.label
        val color = if (isCluster) UNKNOWN_COLOR else when (location.availability.status) {
            AvailabilityStatus.Available -> AVAILABLE_COLOR
            AvailabilityStatus.NoneAvailable -> NONE_AVAILABLE_COLOR
            AvailabilityStatus.Unknown -> UNKNOWN_COLOR
        }
        val key = "$color:$label"
        val icon = icons.getOrPut(key) { iconFactory.fromBitmap(createMarkerBitmap(color, label)) }
        val latitude = group.map { it.latitude }.average()
        val longitude = group.map { it.longitude }.average()
        map.addMarker(
            MarkerOptions()
                .position(LatLng(latitude, longitude))
                .icon(icon)
                .title(if (isCluster) "${group.size} laadlocaties" else location.title)
        )
    }
}

private fun createMarkerBitmap(colorHex: String, label: String): Bitmap {
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
    return bitmap
}

private const val CLUSTER_DISTANCE_PX = 144.0
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val SOURCE_ID = "chargewatch-locations"
private const val BASE_LAYER = "chargewatch-base-diagnostics"
private const val CLUSTER_LAYER = "chargewatch-clusters"
private const val AVAILABLE_LAYER = "chargewatch-available"
private const val NONE_AVAILABLE_LAYER = "chargewatch-none-available"
private const val UNKNOWN_LAYER = "chargewatch-unknown"
private const val CLUSTER_LABEL_LAYER = "chargewatch-cluster-label"
private const val LABEL_LAYER = "chargewatch-labels"
private const val AVAILABLE_COLOR = "#168653"
private const val NONE_AVAILABLE_COLOR = "#D43B3B"
private const val UNKNOWN_COLOR = "#818D96"
