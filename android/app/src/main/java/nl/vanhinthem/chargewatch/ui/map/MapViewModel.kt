package nl.vanhinthem.chargewatch.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    fun testNdwOss() {
        coordinator.cancel()
        val oss = NdwViewport(5.50, 51.75, 5.55, 51.78)
        lastRequestedViewport = oss
        viewModelScope.launch { load(oss) }
    }

    fun retry() {
        val viewport = lastRequestedViewport ?: return
        viewModelScope.launch { load(viewport) }
    }

    private suspend fun load(viewport: NdwViewport) {
        _uiState.value = _uiState.value.copy(loading = true, message = null, rateLimited = false, diagnostic = "NDW-aanvraag gestart (${viewport.asBboxParameter()})")

        when (val result = client.load(viewport)) {
            is NdwResult.Success -> {
                _uiState.value = MapUiState(
                    locations = result.locations,
                    loading = false,
                    diagnostic = "NDW OK: ${result.locations.size} locaties ontvangen",
                    message = if (result.locations.isEmpty()) "Geen laadlocaties in dit kaartgebied." else null
                )
            }
            is NdwResult.RateLimited -> {
                val suffix = result.retryAfterSeconds?.let { " Probeer over $it s opnieuw." }.orEmpty()
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    message = "NDW limiet bereikt.$suffix",
                    diagnostic = "NDW HTTP 429",
                    rateLimited = true
                )
            }
            is NdwResult.Unavailable -> {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    message = "${result.message} ChargeWatch-fallback volgt in een aparte feature.",
                    diagnostic = result.message,
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
    val diagnostic: String = "NDW: nog geen verzoek gestart",
    val loading: Boolean = false,
    val message: String? = "Zoom verder in om laadlocaties te laden.",
    val rateLimited: Boolean = false
)
