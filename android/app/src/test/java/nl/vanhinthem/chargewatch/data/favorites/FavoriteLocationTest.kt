package nl.vanhinthem.chargewatch.data.favorites

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.vanhinthem.chargewatch.data.ndw.AvailabilitySummary
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteLocationTest {
    @Test
    fun identityAndLocationArePersistableWithoutLiveAvailability() {
        val source = MapLocation(
            "NL-NUO-abc", 5.529763, 51.767547, "Schietspoel 18, Oss",
            "2026-10-25T09:00:00Z", AvailabilitySummary(2, 2, CountReliability.Safe),
            "Schietspoel 18, Oss", "Vattenfall"
        )
        val favorite = FavoriteLocation.from(source).copy(alias = "Mijn laadpaal")
        val restored = Json.decodeFromString<FavoriteLocation>(Json.encodeToString(favorite))
        assertEquals(source.featureId, restored.featureId)
        assertEquals("Mijn laadpaal", restored.displayName)
        assertEquals(source.latitude, restored.latitude, 0.0)
        assertEquals(source.longitude, restored.longitude, 0.0)
    }

    @Test
    fun blankAliasFallsBackToOriginalTitle() {
        val favorite = FavoriteLocation("NL-X-1", "Station", 51.7, 5.5, alias = " ")
        assertEquals("Station", favorite.displayName)
    }
}
