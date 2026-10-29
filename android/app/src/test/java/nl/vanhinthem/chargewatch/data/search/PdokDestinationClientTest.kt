package nl.vanhinthem.chargewatch.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdokDestinationClientTest {
    @Test fun suggestParsesAddressesStreetsAndPlacesOnly() {
        val suggestions = parsePdokSuggestions(
            """{"response":{"docs":[
                {"id":"adr-123","weergavenaam":"Schietspoel 18, 5348 Oss","type":"adres"},
                {"id":"weg-1","weergavenaam":"Stationsstraat, Oss","type":"weg"},
                {"id":"woon-1","weergavenaam":"Oss","type":"woonplaats"},
                {"id":"other","weergavenaam":"Perceel","type":"perceel"},
                {"id":"adr-123","weergavenaam":"Duplicaat","type":"adres"}
            ]}}"""
        )
        assertEquals(3, suggestions.size)
        assertEquals("adr-123", suggestions[0].id)
        assertEquals("Stationsstraat, Oss", suggestions[1].label)
        assertEquals("woonplaats", suggestions[2].type)
    }

    @Test fun lookupUsesWgs84PointLongitudeBeforeLatitude() {
        val destination = parsePdokDestination(
            """{"response":{"docs":[{"weergavenaam":"Schietspoel 18, Oss",
               "type":"adres","centroide_ll":"POINT(5.525 51.765)"}]}}"""
        )
        assertEquals("Schietspoel 18, Oss", destination.label)
        assertEquals(51.765, destination.latitude, 0.000001)
        assertEquals(5.525, destination.longitude, 0.000001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun lookupRejectsCoordinatesOutsideTheNetherlands() {
        parsePdokDestination(
            """{"response":{"docs":[{"weergavenaam":"Outside","type":"adres",
               "centroide_ll":"POINT(14.4 52.5)"}]}}"""
        )
    }

    @Test fun emptySuggestionsAreValid() {
        assertTrue(parsePdokSuggestions("""{"response":{"docs":[]}}""").isEmpty())
    }
}
