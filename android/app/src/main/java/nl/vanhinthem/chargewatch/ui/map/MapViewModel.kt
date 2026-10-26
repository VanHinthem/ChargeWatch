package nl.vanhinthem.chargewatch.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import nl.vanhinthem.chargewatch.data.ndw.ChargeWatchMapClient
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.NdwClient
import nl.vanhinthem.chargewatch.data.ndw.NdwResult
import nl.vanhinthem.chargewatch.data.ndw.NdwViewport
import nl.vanhinthem.chargewatch.data.ndw.ViewportRequestCoordinator

class MapViewModel(
    private val client: NdwClient = NdwClient(),
    private val chargeWatchClient: ChargeWatchMapClient = ChargeWatchMapClient()
) : ViewModel() {
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var lastRequestedViewport: NdwViewport? = null
    private var requestSequence: Long = 0
    private val coordinator = ViewportRequestCoordinator(viewModelScope) { viewport ->
        load(viewport)
    }

    fun onViewportChanged(viewport: NdwViewport) {
        if (viewport.areaSquareDegrees > MAX_VIEWPORT_AREA) {
            coordinator.cancel()
            requestSequence++
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

    fun setSource(source: String) {
        if (source !in listOf("NDW", "ChargeWatch") || source == _uiState.value.source) return
        // Invalidate all in-flight loads before changing the source.
        requestSequence++
        coordinator.cancel()
        _uiState.value = _uiState.value.copy(
            source = source, locations = emptyList(), message = null, loadMillis = null
        )
        retry()
    }

    fun retry() {
        val viewport = lastRequestedViewport ?: return
        viewModelScope.launch { load(viewport) }
    }

    private suspend fun load(viewport: NdwViewport) {
        val requestId = ++requestSequence
        val source = _uiState.value.source
        _uiState.value = _uiState.value.copy(loading = true, message = null, rateLimited = false)
        val started = System.nanoTime()
        val primaryResult = if (source == "ChargeWatch") chargeWatchClient.load(viewport) else client.load(viewport)
        // Only fall back for a genuine API failure, not for a capped viewport:
        // NDW may reject large viewports independently, and silently hiding
        // the cap would make parity testing misleading.
        val fallbackEligible = source == "ChargeWatch" && when (primaryResult) {
            is NdwResult.RateLimited -> true
            is NdwResult.Unavailable -> !primaryResult.message.startsWith("Meer dan 999 locaties")
            is NdwResult.Success -> false
        }
        val result = if (fallbackEligible && requestId == requestSequence && _uiState.value.source == source)
            client.load(viewport) else primaryResult
        val usedFallback = fallbackEligible && result !== primaryResult
        val elapsed = (System.nanoTime() - started) / 1_000_000
        // A response from an earlier source selection must never overwrite a newer choice.
        if (requestId != requestSequence || _uiState.value.source != source) return
        when (result) {
            is NdwResult.Success -> {
                _uiState.value = MapUiState(
                    locations = result.locations,
                    fetchedAt = Instant.now(),
                    loading = false,
                    message = if (usedFallback) "ChargeWatch API niet beschikbaar; NDW wordt tijdelijk gebruikt."\n                        else if (result.locations.isEmpty()) "Geen laadlocaties in dit kaartgebied." else null,
                    source = source, loadMillis = elapsed
                )
            }
            is NdwResult.RateLimited -> {
                val suffix = result.retryAfterSeconds?.let { " Probeer over $it s opnieuw." }.orEmpty()
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    message = "$source limiet bereikt.$suffix",
                    rateLimited = true, loadMillis = elapsed
                )
            }
            is NdwResult.Unavailable -> {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    message = result.message, rateLimited = false, loadMillis = elapsed
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
    val rateLimited: Boolean = false,
    val source: String = "ChargeWatch",
    val loadMillis: Long? = null
)
