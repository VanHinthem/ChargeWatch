package nl.vanhinthem.chargewatch.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.NdwClient
import nl.vanhinthem.chargewatch.data.ndw.NdwResult
import nl.vanhinthem.chargewatch.data.ndw.NdwViewport
import nl.vanhinthem.chargewatch.data.ndw.ViewportRequestCoordinator

class MapViewModel(
    private val client: NdwClient = NdwClient()
) : ViewModel() {
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var lastRequestedViewport: NdwViewport? = null
    private val coordinator = ViewportRequestCoordinator(viewModelScope) { viewport ->
        load(viewport)
    }

    fun onViewportChanged(viewport: NdwViewport) {
        if (viewport.areaSquareDegrees > MAX_VIEWPORT_AREA) {
            coordinator.cancel()
            _uiState.value = _uiState.value.copy(
                locations = emptyList(),
                loading = false,
                message = "Zoom verder in om laadlocaties te laden.",
                rateLimited = false
            )
            return
        }

        lastRequestedViewport = viewport
        coordinator.submit(viewport)
    }

    fun retry() {
        val viewport = lastRequestedViewport ?: return
        viewModelScope.launch { load(viewport) }
    }

    private suspend fun load(viewport: NdwViewport) {
        _uiState.value = _uiState.value.copy(loading = true, message = null, rateLimited = false)

        when (val result = client.load(viewport)) {
            is NdwResult.Success -> {
                _uiState.value = MapUiState(
                    locations = result.locations,
                    fetchedAt = Instant.now(),
                    loading = false,
                    message = if (result.locations.isEmpty()) "Geen laadlocaties in dit kaartgebied." else null
                )
            }
            is NdwResult.RateLimited -> {
                val suffix = result.retryAfterSeconds?.let { " Probeer over $it s opnieuw." }.orEmpty()
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    message = "NDW limiet bereikt.$suffix",
                    rateLimited = true
                )
            }
            is NdwResult.Unavailable -> {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    message = "${result.message} ChargeWatch-fallback volgt in een aparte feature.",
                    rateLimited = false
                )
            }
        }
    }

    override fun onCleared() {
        coordinator.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_VIEWPORT_AREA = 1.0
    }
}

data class MapUiState(
    val locations: List<MapLocation> = emptyList(),
    val fetchedAt: Instant? = null,
    val loading: Boolean = false,
    val message: String? = "Zoom verder in om laadlocaties te laden.",
    val rateLimited: Boolean = false
)
