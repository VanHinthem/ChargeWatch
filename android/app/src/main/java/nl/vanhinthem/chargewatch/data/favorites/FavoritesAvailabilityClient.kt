package nl.vanhinthem.chargewatch.data.favorites

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import nl.vanhinthem.chargewatch.data.ndw.ChargeWatchMapClient
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.NdwClient
import nl.vanhinthem.chargewatch.data.ndw.NdwResult
import nl.vanhinthem.chargewatch.data.ndw.NdwViewport

sealed interface FavoriteRefreshResult {
    data class Success(
        val locations: Map<String, MapLocation>,
        val warning: String? = null
    ) : FavoriteRefreshResult

    data class Partial(
        val locations: Map<String, MapLocation>,
        val warning: String
    ) : FavoriteRefreshResult
}

/**
 * Favorites follow the same source policy as the map:
 * ChargeWatch API first, direct NDW only as a fallback.
 *
 * Nearby favorites share one viewport request. Distant favorites use small
 * per-favorite viewports and are fetched concurrently so initial screen load
 * does not pay the network latency serially.
 */
class FavoritesAvailabilityClient(
    private val chargeWatch: ChargeWatchMapClient = ChargeWatchMapClient(),
    private val ndw: NdwClient = NdwClient()
) {
    suspend fun refresh(favorites: List<FavoriteLocation>): FavoriteRefreshResult = withContext(Dispatchers.IO) {
        if (favorites.isEmpty()) return@withContext FavoriteRefreshResult.Success(emptyMap())

        val ids = favorites.map { it.featureId }.toSet()
        val viewports = favoriteViewports(favorites)

        val results = coroutineScope {
            viewports.map { viewport ->
                async { loadViewport(viewport) }
            }.awaitAll()
        }

        val found = mutableMapOf<String, MapLocation>()
        var fallbackUsed = false
        var failures = 0
        var rateLimited = false

        results.forEach { result ->
            fallbackUsed = fallbackUsed || result.fallbackUsed
            rateLimited = rateLimited || result.rateLimited
            if (result.failed) failures++
            result.locations
                .filter { it.featureId in ids }
                .forEach { found[it.featureId] = it }
        }

        val warning = when {
            failures > 0 && rateLimited ->
                "Een deel van de favorieten kon niet worden bijgewerkt; een bronlimiet werd bereikt."
            failures > 0 ->
                "Een deel van de favorieten kon niet worden bijgewerkt."
            fallbackUsed ->
                "ChargeWatch API tijdelijk niet beschikbaar; NDW is als fallback gebruikt."
            else -> null
        }

        if (failures > 0) {
            FavoriteRefreshResult.Partial(
                locations = found,
                warning = warning ?: "Een deel van de favorieten kon niet worden bijgewerkt."
            )
        } else {
            FavoriteRefreshResult.Success(found, warning)
        }
    }

    private suspend fun loadViewport(viewport: NdwViewport): ViewportRefresh {
        return when (val primary = chargeWatch.load(viewport)) {
            is NdwResult.Success -> ViewportRefresh(primary.locations)
            is NdwResult.RateLimited -> when (val fallback = ndw.load(viewport)) {
                is NdwResult.Success -> ViewportRefresh(
                    locations = fallback.locations,
                    fallbackUsed = true
                )
                is NdwResult.RateLimited -> ViewportRefresh(
                    fallbackUsed = true,
                    failed = true,
                    rateLimited = true
                )
                is NdwResult.Unavailable -> ViewportRefresh(
                    fallbackUsed = true,
                    failed = true
                )
            }
            is NdwResult.Unavailable -> when (val fallback = ndw.load(viewport)) {
                is NdwResult.Success -> ViewportRefresh(
                    locations = fallback.locations,
                    fallbackUsed = true
                )
                is NdwResult.RateLimited -> ViewportRefresh(
                    fallbackUsed = true,
                    failed = true,
                    rateLimited = true
                )
                is NdwResult.Unavailable -> ViewportRefresh(
                    fallbackUsed = true,
                    failed = true
                )
            }
        }
    }
}

internal fun favoriteViewports(favorites: List<FavoriteLocation>): List<NdwViewport> {
    if (favorites.isEmpty()) return emptyList()

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

    return if (combined.areaSquareDegrees <= 0.05) {
        listOf(combined)
    } else {
        favorites.map {
            NdwViewport(
                (it.longitude - 0.002).coerceAtLeast(-180.0),
                (it.latitude - 0.002).coerceAtLeast(-90.0),
                (it.longitude + 0.002).coerceAtMost(180.0),
                (it.latitude + 0.002).coerceAtMost(90.0)
            )
        }
    }
}

private data class ViewportRefresh(
    val locations: List<MapLocation> = emptyList(),
    val fallbackUsed: Boolean = false,
    val failed: Boolean = false,
    val rateLimited: Boolean = false
)
