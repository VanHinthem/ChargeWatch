package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.ndw.NdwResult
import nl.vanhinthem.chargewatch.data.ndw.NdwViewport
import org.junit.Assert.*
import org.junit.Test

class MapOverviewRoutingTest {
    @Test fun neighborhoodUsesOriginalCompleteLocationsWithoutGrid() {
        assertEquals(OverviewFetchRoute.FullLocations,
            overviewFetchRoute(NdwViewport(5.5,51.7,5.6,51.8)))
        assertEquals(OverviewFetchRoute.FullLocations,
            overviewFetchRoute(NdwViewport(5.0,51.0,6.0,52.0)))
    }

    @Test fun countryAndLargeRegionUseAggregatedOverview() {
        assertEquals(OverviewFetchRoute.AggregatedGrid,
            overviewFetchRoute(NdwViewport(3.3,50.7,7.3,53.6)))
        assertEquals(OverviewFetchRoute.AggregatedGrid,
            overviewFetchRoute(NdwViewport(4.0,51.0,6.0,52.0)))
    }

    @Test fun onlyExactOverflowUsesCompleteOverviewFallback() {
        assertTrue(isCompleteViewportTooDense(NdwResult.Unavailable(
            "Meer dan 999 locaties in dit kaartgebied. Zoom verder in.")))
        assertFalse(isCompleteViewportTooDense(NdwResult.Unavailable(
            "ChargeWatch API gaf HTTP 503.")))
        assertFalse(isCompleteViewportTooDense(NdwResult.RateLimited(null)))
        assertFalse(isCompleteViewportTooDense(NdwResult.Success(emptyList())))
    }
}
