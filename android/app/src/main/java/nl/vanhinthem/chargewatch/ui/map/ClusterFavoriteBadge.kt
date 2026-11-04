package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
import nl.vanhinthem.chargewatch.data.ndw.OverviewMarker
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Locate saved favorites in the same canonical Web Mercator cells as MapGrid.CellAt
 * in the read-only API. Only coordinates/IDs already stored on this device are used.
 * This does not send favorites or aliases to the backend.
 */
internal fun favoriteGridCell(longitude: Double, latitude: Double, level: Int): Pair<Long, Long>? {
    if (!longitude.isFinite() || !latitude.isFinite() ||
        longitude !in -180.0..180.0 || latitude !in -85.0511287798066..85.0511287798066 ||
        level !in 0..22
    ) return null

    val radius = 6378137.0
    val halfWorld = 20037508.342789244
    val world = 40075016.68557849
    val cellCount = 1L shl level
    val metersPerCell = world / cellCount
    val xMeters = radius * longitude * PI / 180.0
    val radians = latitude * PI / 180.0
    val yMeters = radius * ln(tan(PI / 4.0 + radians / 2.0))
    return floor((xMeters + halfWorld) / metersPerCell).toLong().coerceIn(0, cellCount - 1) to
        floor((halfWorld - yMeters) / metersPerCell).toLong().coerceIn(0, cellCount - 1)
}

/** The complete local viewport contains exact identities, not grid approximations. */
internal fun localGroupHasFavorite(
    group: LocationVisualGroup, favoriteIds: Set<String>
): Boolean = group.members.any { it.featureId in favoriteIds }

/**
 * An overview singleton carries the exact feature ID. A server cluster only
 * carries a complete cell membership/count, not the IDs of its constituents.
 *
 * Without filters, a saved coordinate within that occupied cell is enough to
 * display the cluster badge. With filters active, that is not proof of filter
 * membership, so only positively identified singleton locations are badged.
 * This avoids a false-positive badge when a favorite has been filtered out.
 */
internal fun overviewGroupHasFavorite(
    group: OverviewVisualGroup,
    favorites: List<FavoriteLocation>,
    filtersActive: Boolean
): Boolean {
    if (favorites.isEmpty()) return false
    val ids = favorites.map { it.featureId }.toSet()
    if (group.members.any {
            it is OverviewMarker.Location && it.featureId in ids
        }) return true

    if (filtersActive) return false

    val clusterCells = group.members.filterIsInstance<OverviewMarker.Cluster>()
        .map { Triple(it.gridLevel,it.gridX,it.gridY) }.toSet()
    if (clusterCells.isEmpty()) return false

    // Usually all members of a response have the same grid level. Keep this
    // robust for later protocol changes rather than assuming that invariant.
    return favorites.any { favorite ->
        clusterCells.any { (level,x,y) ->
            favoriteGridCell(favorite.longitude,favorite.latitude,level) == (x to y)
        }
    }
}
