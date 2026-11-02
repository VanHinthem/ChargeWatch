package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.location.DevicePosition
import nl.vanhinthem.chargewatch.data.search.SearchLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NearbyContextTest {
    private fun loc(id: String) = SearchLocation(id,"", "",null,51.7,5.5,1,2,0)
    @Test fun excludesTheOriginWithoutAssumingConnectorCompatibility() {
        val origin = NearbyOrigin("NL-A-1","",51.7,5.5)
        val context = NearbyContext.Alternatives(origin)
        val result = visibleNearbyCandidates(context, listOf(loc("NL-A-1"),loc("NL-A-2"),loc("NL-A-3")))
        assertEquals(listOf("NL-A-2","NL-A-3"), result.map { it.featureId })
        assertFalse(result.any { it.featureId == origin.featureId })
    }
    @Test fun retainsNearestFiveAndDistinctIdentities() {
        val context = NearbyContext.Gps(DevicePosition(51.7,5.5,20f,false))
        val result = visibleNearbyCandidates(context, (1..8).map { loc("$it") } + loc("2"))
        assertEquals(listOf("1","2","3","4","5"),result.map { it.featureId })
        assertEquals("5 locaties bij mijn positie",nearbyLabel(context,5))
    }
    @Test fun handlesSingularAlternatives() {
        assertEquals("1 nabije alternatief",nearbyLabel(NearbyContext.Alternatives(NearbyOrigin("1","",51.7,5.5)),1))
    }
}
