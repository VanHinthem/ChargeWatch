package nl.vanhinthem.chargewatch.ui.map

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.time.Instant
import nl.vanhinthem.chargewatch.data.filters.ChargingFilters
import nl.vanhinthem.chargewatch.data.search.Destination
import nl.vanhinthem.chargewatch.data.search.SearchLocation
import nl.vanhinthem.chargewatch.data.location.DevicePosition
import nl.vanhinthem.chargewatch.data.ndw.ChargeWatchMapClient
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.NdwClient
import nl.vanhinthem.chargewatch.data.ndw.NdwResult
import nl.vanhinthem.chargewatch.data.ndw.NdwViewport
import nl.vanhinthem.chargewatch.data.ndw.MapOverviewClient
import nl.vanhinthem.chargewatch.data.ndw.OverviewDataset
import nl.vanhinthem.chargewatch.data.ndw.OverviewResult

class MapViewModel(
    private val client: NdwClient = NdwClient(),
    private val chargeWatchClient: ChargeWatchMapClient = ChargeWatchMapClient(),
    private val overviewClient: MapOverviewClient = MapOverviewClient()
) : ViewModel() {
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    // Selected address and nearby panel survive leaving the map tab and configuration changes.
    // Deliberately in-memory only: ChargeWatch stores no address search history.
    val searchQuery = mutableStateOf("")
    val searchSelected = mutableStateOf(false)
    val destination = mutableStateOf<Destination?>(null)
    val nearLocations = mutableStateOf<List<SearchLocation>>(emptyList())
    val nearMessage = mutableStateOf<String?>(null)
    val nearbyExpanded = mutableStateOf(false)
    internal val overview = mutableStateOf<DestinationOverview?>(null)
    val activeNearby = mutableStateOf<NearbyContext?>(null)
    val previousNearby = mutableStateOf<NearbyContext?>(null)
    val gpsPosition = mutableStateOf<DevicePosition?>(null)
    val nearCache = mutableMapOf<Pair<NearbyContext, ChargingFilters>, NearbyCacheEntry>()
    val nearLoadedFor = mutableStateOf<NearbyContext?>(null)
    val nearLoadedWith = mutableStateOf(ChargingFilters())
    val filters = mutableStateOf(ChargingFilters())
    val operatorChoices = mutableStateOf<List<String>?>(null)

    fun activateNearby(next: NearbyContext) {
        if (activeNearby.value == next) return
        previousNearby.value = activeNearby.value
        activeNearby.value = next
        nearLoadedFor.value = null
        nearbyExpanded.value = false
    }

    fun backToPreviousNearby() {
        val previous = previousNearby.value ?: return
        previousNearby.value = null
        activeNearby.value = previous
        nearLoadedFor.value = null
        nearbyExpanded.value = false
    }

    fun disableGps() {
        gpsPosition.value = null
        if (activeNearby.value is NearbyContext.Gps) {
            activeNearby.value = previousNearby.value?.takeUnless { it is NearbyContext.Gps }
            previousNearby.value = null
            nearLoadedFor.value = null
        } else if (previousNearby.value is NearbyContext.Gps) {
            previousNearby.value = null
        }
        nearCache.keys.removeAll { (origin, _) -> origin is NearbyContext.Gps }
    }

    val mapMode = mutableStateOf(MapMode.Legacy)
    private data class MapCameraRequest(val viewport: NdwViewport, val zoom: Double)
    private var lastRequest: MapCameraRequest? = null
    private var requestSequence = 0L
    private var pending: Job? = null

    fun setMapMode(mode: MapMode) {
        if (mapMode.value == mode) return
        mapMode.value = mode
        invalidate()
        _uiState.value = _uiState.value.copy(
            locations=emptyList(),overview=null,message=null,
            source=if (mode == MapMode.Overview) "ChargeWatch" else _uiState.value.source)
        retry()
    }

    fun onViewportChanged(viewport: NdwViewport, zoom: Double) {
        if (!zoom.isFinite()) return
        val request = MapCameraRequest(viewport,zoom.coerceIn(0.0,22.0))
        if (request == lastRequest) return
        lastRequest = request
        schedule(request)
    }

    private fun invalidate() {
        ++requestSequence
        pending?.cancel()
        pending = null
    }

    private fun schedule(request: MapCameraRequest, debounce: Boolean = true) {
        invalidate() // invalidate a running HTTP request immediately
        val generation = requestSequence
        pending = viewModelScope.launch {
            if (debounce) delay(400)
            load(request,generation)
        }
    }

    fun applyFilters(next: ChargingFilters) {
        if (filters.value == next) return
        filters.value = next
        nearLoadedFor.value = null
        _uiState.value = _uiState.value.copy(
            source=if (next.active) "ChargeWatch" else _uiState.value.source,
            locations=emptyList(),overview=null,message=null)
        retry()
    }

    fun setSource(source: String) {
        if (filters.value.active && source == "NDW") {
            _uiState.value = _uiState.value.copy(message="Schakel filters uit om de directe NDW-bron te gebruiken.")
            return
        }
        if (source !in listOf("NDW","ChargeWatch") || source == _uiState.value.source) return
        _uiState.value = _uiState.value.copy(
            source=source,locations=emptyList(),overview=null,message=null,loadMillis=null)
        retry()
    }

    fun retry() {
        val request = lastRequest ?: return
        schedule(request,debounce=false)
    }

    private suspend fun load(request: MapCameraRequest, generation: Long) {
        val mode = mapMode.value
        val source = _uiState.value.source
        val requestedFilters = filters.value
        val viewport = request.viewport
        if (mode == MapMode.Legacy && viewport.areaSquareDegrees > MAX_VIEWPORT_AREA) {
            if (generation == requestSequence) _uiState.value = _uiState.value.copy(
                locations=emptyList(),overview=null,loading=false,rateLimited=false,
                message="Oude kaartmodus: zoom verder in. Landelijke dekking is hier niet beschikbaar.")
            return
        }
        _uiState.value = _uiState.value.copy(loading=true,message=null,rateLimited=false)
        val started = System.nanoTime()
        if (mode == MapMode.Overview) {
            // At street level, restore the original map's individual locations
            // and pixel-distance clustering instead of drawing server grid cells.
            // The legacy endpoint guarantees completeness or returns HTTP 413.
            if (overviewFetchRoute(viewport) == OverviewFetchRoute.FullLocations) {
                when (val detailed = chargeWatchClient.load(viewport,requestedFilters)) {
                    is NdwResult.Success -> {
                        if (!isCurrent(generation,mode,source,requestedFilters)) return
                        _uiState.value = MapUiState(
                            locations=detailed.locations,overview=null,
                            fetchedAt=Instant.now(),loading=false,source="ChargeWatch",
                            message=if (detailed.locations.isEmpty())
                                "Geen passende laadlocaties in dit kaartgebied." else null,
                            loadMillis=(System.nanoTime()-started)/1_000_000)
                        return
                    }
                    is NdwResult.RateLimited -> {
                        if (!isCurrent(generation,mode,source,requestedFilters)) return
                        _uiState.value = _uiState.value.copy(
                            loading=false,rateLimited=true,
                            message="Laadlocaties ophalen: aanvraaglimiet bereikt.")
                        return
                    }
                    is NdwResult.Unavailable -> {
                        if (!isCurrent(generation,mode,source,requestedFilters)) return
                        if (!isCompleteViewportTooDense(detailed)) {
                            _uiState.value = _uiState.value.copy(
                                loading=false,rateLimited=false,message=detailed.message)
                            return
                        }
                        // HTTP 413: never display an incomplete list. The
                        // complete aggregated overview is the safe fallback.
                    }
                }
            }
            when (val result = overviewClient.load(viewport,request.zoom,requestedFilters)) {
                is OverviewResult.Success -> {
                    if (!isCurrent(generation,mode,source,requestedFilters)) return
                    val dataset = result.data
                    _uiState.value = MapUiState(
                        locations=dataset.individualLocations(),overview=dataset,
                        fetchedAt=Instant.now(),loading=false,source=source,
                        message=if (dataset.markers.isEmpty()) "Geen passende laadlocaties in dit kaartgebied." else null,
                        loadMillis=(System.nanoTime()-started)/1_000_000)
                }
                is OverviewResult.RateLimited -> {
                    if (!isCurrent(generation,mode,source,requestedFilters)) return
                    _uiState.value = _uiState.value.copy(loading=false,rateLimited=true,
                        message="Landelijke kaart: aanvraaglimiet bereikt. Probeer later opnieuw.")
                }
                is OverviewResult.Unavailable -> {
                    if (!isCurrent(generation,mode,source,requestedFilters)) return
                    _uiState.value = _uiState.value.copy(loading=false,rateLimited=false,
                        message=result.message+" Schakel eventueel over op de beperkte oude kaart in Instellingen.")
                }
            }
            return
        }

        val primaryResult = if (source=="ChargeWatch") chargeWatchClient.load(viewport,requestedFilters)
            else client.load(viewport)
        // Legacy fallback is only allowed within its original 1 square degree scope.
        val fallbackEligible = !requestedFilters.active && source=="ChargeWatch" && when (primaryResult) {
            is NdwResult.RateLimited -> true
            is NdwResult.Unavailable -> !isCompleteViewportTooDense(primaryResult)
            is NdwResult.Success -> false
        }
        val result = if (fallbackEligible && isCurrent(generation,mode,source,requestedFilters))
            client.load(viewport) else primaryResult
        if (!isCurrent(generation,mode,source,requestedFilters)) return
        val elapsed = (System.nanoTime()-started)/1_000_000
        when (result) {
            is NdwResult.Success -> _uiState.value = MapUiState(
                locations=result.locations,fetchedAt=Instant.now(),loading=false,
                source=source,loadMillis=elapsed,
                message=if (fallbackEligible && result !== primaryResult)
                    "ChargeWatch API niet beschikbaar; NDW wordt tijdelijk gebruikt."
                else if (result.locations.isEmpty()) "Geen laadlocaties in dit kaartgebied." else null)
            is NdwResult.RateLimited -> _uiState.value = _uiState.value.copy(
                loading=false,rateLimited=true,loadMillis=elapsed,
                message="$source limiet bereikt.")
            is NdwResult.Unavailable -> _uiState.value = _uiState.value.copy(
                loading=false,rateLimited=false,loadMillis=elapsed,message=result.message)
        }
    }

    private fun isCurrent(generation: Long, mode: MapMode, source: String, filtersAtStart: ChargingFilters) =
        generation == requestSequence && mapMode.value == mode &&
        _uiState.value.source == source && filters.value == filtersAtStart

    override fun onCleared() {
        invalidate()
        super.onCleared()
    }

    companion object {
        private const val MAX_VIEWPORT_AREA = 1.0
    }
}

enum class MapMode { Legacy, Overview }

data class MapUiState(
    val overview: OverviewDataset? = null,
    val locations: List<MapLocation> = emptyList(),
    val fetchedAt: Instant? = null,
    val loading: Boolean = false,
    val message: String? = "Zoom verder in om laadlocaties te laden.",
    val rateLimited: Boolean = false,
    val source: String = "ChargeWatch",
    val loadMillis: Long? = null
)
