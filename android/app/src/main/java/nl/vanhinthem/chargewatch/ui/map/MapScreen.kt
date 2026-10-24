package nl.vanhinthem.chargewatch.ui.map

import android.graphics.Color
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
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
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
                    text = state.diagnostic,
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
                    configureStyle(map, styleUri, locations, onViewportChanged)
                    map.addOnCameraIdleListener {
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
                map.getStyle { style -> updateLocations(style, locations) }
            }
        }
    )
}

private fun configureStyle(
    map: MapLibreMap,
    styleUri: String,
    locations: List<MapLocation>,
    onViewportChanged: (NdwViewport) -> Unit
) {
    map.setStyle(styleUri) { style ->
        ensureLayers(style)
        updateLocations(style, locations)
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

private fun ensureLayers(style: Style) {
    if (style.getSource(SOURCE_ID) != null) return

    style.addSource(
        GeoJsonSource(
            SOURCE_ID,
            FeatureCollection.fromFeatures(arrayOf<Feature>()),
            GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(52)
                .withClusterMaxZoom(14)
        )
    )

    val cluster = CircleLayer(CLUSTER_LAYER, SOURCE_ID).withProperties(
        circleColor(UNKNOWN_COLOR),
        circleRadius(24f),
        circleStrokeColor(UNKNOWN_COLOR),
        circleStrokeWidth(4f)
    ).withFilter(has("point_count"))
    style.addLayer(cluster)

    style.addLayer(
        CircleLayer(AVAILABLE_LAYER, SOURCE_ID)
            .withProperties(circleColor(AVAILABLE_COLOR), circleRadius(22f))
            .withFilter(
                all(
                    not(has("point_count")),
                    eq(get("status"), literal("available"))
                )
            )
    )
    style.addLayer(
        CircleLayer(NONE_AVAILABLE_LAYER, SOURCE_ID)
            .withProperties(circleColor(NONE_AVAILABLE_COLOR), circleRadius(22f))
            .withFilter(
                all(
                    not(has("point_count")),
                    eq(get("status"), literal("none"))
                )
            )
    )
    style.addLayer(
        CircleLayer(UNKNOWN_LAYER, SOURCE_ID)
            .withProperties(circleColor(UNKNOWN_COLOR), circleRadius(22f))
            .withFilter(
                all(
                    not(has("point_count")),
                    eq(get("status"), literal("unknown"))
                )
            )
    )

    style.addLayer(
        SymbolLayer(CLUSTER_LABEL_LAYER, SOURCE_ID)
            .withProperties(
                textField("…"),
                textColor(Color.WHITE),
                textSize(17f),
                textAllowOverlap(true),
                textIgnorePlacement(true)
            )
            .withFilter(has("point_count"))
    )

    style.addLayer(
        SymbolLayer(LABEL_LAYER, SOURCE_ID)
            .withProperties(
                textField(get("label")),
                textColor(Color.WHITE),
                textSize(13f),
                textAllowOverlap(true),
                textIgnorePlacement(true)
            )
            .withFilter(not(has("point_count")))
    )
}

private fun updateLocations(style: Style, locations: List<MapLocation>) {
    val features = locations.map { location ->
        Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)).apply {
            addStringProperty("feature_id", location.featureId)
            addStringProperty("label", location.availability.label)
            addStringProperty(
                "status",
                when (location.availability.status) {
                    AvailabilityStatus.Available -> "available"
                    AvailabilityStatus.NoneAvailable -> "none"
                    AvailabilityStatus.Unknown -> "unknown"
                }
            )
        }
    }
    style.getSourceAs<GeoJsonSource>(SOURCE_ID)
        ?.setGeoJson(FeatureCollection.fromFeatures(features))
}

private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val SOURCE_ID = "chargewatch-locations"
private const val CLUSTER_LAYER = "chargewatch-clusters"
private const val AVAILABLE_LAYER = "chargewatch-available"
private const val NONE_AVAILABLE_LAYER = "chargewatch-none-available"
private const val UNKNOWN_LAYER = "chargewatch-unknown"
private const val CLUSTER_LABEL_LAYER = "chargewatch-cluster-label"
private const val LABEL_LAYER = "chargewatch-labels"
private const val AVAILABLE_COLOR = "#168653"
private const val NONE_AVAILABLE_COLOR = "#D43B3B"
private const val UNKNOWN_COLOR = "#818D96"
