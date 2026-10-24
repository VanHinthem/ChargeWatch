package nl.vanhinthem.chargewatch.data.ndw

import kotlinx.serialization.json.Json

class NdwGeoJsonParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }
) {
    fun parse(rawJson: String): List<MapLocation> {
        val collection = json.decodeFromString<NdwFeatureCollection>(rawJson)
        require(collection.type == "FeatureCollection") { "Expected GeoJSON FeatureCollection." }
        require(collection.features.size < MAX_FEATURES) {
            "NDW returned ${collection.features.size} features; result may be truncated at the 1000-feature boundary."
        }

        return collection.features.mapNotNull(::toMapLocation)
    }

    private fun toMapLocation(feature: NdwFeature): MapLocation? {
        val id = feature.id?.takeIf { it.isNotBlank() } ?: return null
        val geometry = feature.geometry ?: return null
        if (geometry.type != "Point" || geometry.coordinates.size < 2) return null

        val longitude = geometry.coordinates[0]
        val latitude = geometry.coordinates[1]
        if (longitude !in -180.0..180.0 || latitude !in -90.0..90.0) return null

        return MapLocation(
            featureId = id,
            longitude = longitude,
            latitude = latitude,
            title = feature.properties.address
                ?.takeIf(String::isNotBlank)
                ?: feature.properties.operatorName
                    ?.takeIf(String::isNotBlank)
                ?: "Laadlocatie",
            lastUpdated = feature.properties.lastUpdated,
            availability = summarize(feature.properties.availabilities)
        )
    }

    internal fun summarize(rows: List<NdwAvailability>): AvailabilitySummary {
        val validPairs = rows.mapNotNull { row ->
            val available = row.available ?: return@mapNotNull null
            val total = row.total ?: return@mapNotNull null
            if (available < 0 || total <= 0 || available > total) return@mapNotNull null
            available to total
        }

        if (validPairs.isEmpty()) {
            return AvailabilitySummary(null, null, CountReliability.Ambiguous)
        }

        return if (rows.size == 1 && validPairs.size == 1) {
            val (available, total) = validPairs.single()
            AvailabilitySummary(available, total, CountReliability.Safe)
        } else {
            // NDW rows describe connector categories, not proven unique EVSEs.
            // Never add category totals: one EVSE can expose multiple connectors.
            AvailabilitySummary(null, null, CountReliability.Ambiguous)
        }
    }

    companion object {
        const val MAX_FEATURES = 1000
    }
}
