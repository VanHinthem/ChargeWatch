package nl.vanhinthem.chargewatch.data.favorites

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.NdwClient
import nl.vanhinthem.chargewatch.data.ndw.NdwResult
import nl.vanhinthem.chargewatch.data.ndw.NdwViewport

sealed interface FavoriteRefreshResult {
    data class Success(val locations: Map<String, MapLocation>) : FavoriteRefreshResult
    data class Partial(val locations: Map<String, MapLocation>, val warning: String) : FavoriteRefreshResult
}

/**
 * Fetches NDW only when the favorites screen requests an update.
 * Never persists live statuses or dispatches backend requests for individual map markers.
 */
class FavoritesAvailabilityClient(private val ndw: NdwClient = NdwClient()) {
    suspend fun refresh(favorites: List<FavoriteLocation>): FavoriteRefreshResult = withContext(Dispatchers.IO) {
        if (favorites.isEmpty()) return@withContext FavoriteRefreshResult.Success(emptyMap())
        // A group in one viewport saves API calls for nearby favorites; distant favorites
        // are queried by a small local bounding box each, avoiding bounded() truncation.
        val west = favorites.minOf { it.longitude }
        val east = favorites.maxOf { it.longitude }
        val south = favorites.minOf { it.latitude }
        val north = favorites.maxOf { it.latitude }
        val combined = NdwViewport(
            (west - 0.002).coerceAtLeast(-180.0),
            (south - 0.002).coerceAtLeast(-90.0),
            (east + 0.002).coerceAtMost(180.0),
            (north + 0.002).coerceAtMost(90.0)
        )
        val viewports = if (combined.areaSquareDegrees <= 0.05) listOf(combined)
        else favorites.map {
            NdwViewport(it.longitude - 0.002, it.latitude - 0.002, it.longitude + 0.002, it.latitude + 0.002)
        }
        val found = mutableMapOf<String, MapLocation>()
        var failures = 0
        for (viewport in viewports) {
            when (val result = ndw.load(viewport)) {
                is NdwResult.Success -> {
                    val ids = favorites.map { it.featureId }.toSet()
                    result.locations.filter { it.featureId in ids }.forEach { found[it.featureId] = it }
                }
                is NdwResult.RateLimited -> {
                    return@withContext FavoriteRefreshResult.Partial(found, "NDW-verzoeklimiet bereikt; probeer later opnieuw.")
                }
                is NdwResult.Unavailable -> failures++
            }
        }
        if (failures > 0) FavoriteRefreshResult.Partial(found, "Een deel van de NDW-gegevens kon niet worden opgehaald.")
        else FavoriteRefreshResult.Success(found)
    }
}
