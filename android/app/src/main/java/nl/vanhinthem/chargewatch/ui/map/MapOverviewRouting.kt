package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.ndw.ChargeWatchMapClient
import nl.vanhinthem.chargewatch.data.ndw.NdwResult
import nl.vanhinthem.chargewatch.data.ndw.NdwViewport

/**
 * Product behavior: a nationwide view uses the aggregated server overview,
 * but a street-level view shows the same individual chargers and pixel-based
 * grouping as ChargeWatch's original map.
 *
 * The detail route's exact 1 square-degree coverage is never cropped.
 * When the complete detail route hits its 999-location guard, switch back
 * to the complete server grid rather than rendering a partial collection.
 */
internal enum class OverviewFetchRoute { FullLocations, AggregatedGrid }

internal fun overviewFetchRoute(viewport: NdwViewport): OverviewFetchRoute =
    if (viewport.areaSquareDegrees <= 1.0) OverviewFetchRoute.FullLocations
    else OverviewFetchRoute.AggregatedGrid

internal fun isCompleteViewportTooDense(result: NdwResult): Boolean =
    result is NdwResult.Unavailable &&
        result.message == ChargeWatchMapClient.TOO_MANY_LOCATIONS_MESSAGE
