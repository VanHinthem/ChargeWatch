package nl.vanhinthem.chargewatch.data.favorites

import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import java.time.Duration
import java.time.Instant

/** Age of *our successful availability fetch*, distinct from NDW's source observation timestamp. */
private val MAX_FETCH_AGE = Duration.ofMinutes(2)

fun favoritesNeedingFetch(
    favorites: List<FavoriteLocation>,
    mapLocations: Map<String, MapLocation>,
    mapFetchedAt: Instant?,
    favoriteLocations: Map<String, MapLocation>,
    favoriteFetchedAt: Instant?,
    now: Instant
): List<FavoriteLocation> = favorites.filter { favorite ->
    fun recentlyFetched(timestamp: Instant?): Boolean =
        timestamp != null && !timestamp.isAfter(now) &&
            Duration.between(timestamp, now) <= MAX_FETCH_AGE

    val mapIsRecent = mapLocations.containsKey(favorite.featureId) && recentlyFetched(mapFetchedAt)
    val favoriteIsRecent = favoriteLocations.containsKey(favorite.featureId) && recentlyFetched(favoriteFetchedAt)
    !mapIsRecent && !favoriteIsRecent
}
