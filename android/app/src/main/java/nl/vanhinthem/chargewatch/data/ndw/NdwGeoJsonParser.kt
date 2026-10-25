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
            address = feature.properties.address?.takeIf(String::isNotBlank),
            operatorName = feature.properties.operatorName?.takeIf(String::isNotBlank),
            lastUpdated = feature.properties.lastUpdated,
            availability = summarize(feature.properties.availabilities, feature.properties.cpoId, id)
        )
    }

    internal fun summarize(
        rows: List<NdwAvailability>,
        cpoId: String? = null,
        featureId: String? = null
    ): AvailabilitySummary {
        // Do not treat incomplete or malformed categories as a complete location.
        if (rows.isEmpty()) return AvailabilitySummary(null, null, CountReliability.Ambiguous)
        val pairs = rows.map { row ->
            val available = row.available
            val total = row.total
            if (available == null || total == null ||
                available < 0 || total <= 0 || available > total
            ) return AvailabilitySummary(null, null, CountReliability.Ambiguous)
            available to total
        }
        if (pairs.size == 1) {
            val (available, total) = pairs.single()
            return AvailabilitySummary(available, total, CountReliability.Safe)
        }

        // Validated against an NDW GeoJSON/OCPI snapshot in issue #38.
        // FAS connector categories overlap; other parties usually partition
        // EVSEs. This is an estimate, NOT a unique-EVSE guarantee.
        val partyFromId = featureId?.split("-", limit = 3)?.takeIf { it.size == 3 }?.get(1)
        val party = cpoId?.takeIf { it.isNotBlank() } ?: partyFromId
        // Inconsistent/missing party identity must never select a counting rule.
        if (party == null || (cpoId != null && partyFromId != null &&
                !party.equals(partyFromId, ignoreCase = true))
        ) return AvailabilitySummary(null, null, CountReliability.Ambiguous)

        val selected = if (party.equals("FAS", ignoreCase = true)) {
            // Use one coherent category pair: never independently combine the
            // highest available and highest total from different categories.
            pairs.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })!!
        } else {
            val available = pairs.sumOf { it.first.toLong() }
            val total = pairs.sumOf { it.second.toLong() }
            if (available > Int.MAX_VALUE || total > Int.MAX_VALUE) {
                return AvailabilitySummary(null, null, CountReliability.Ambiguous)
            }
            available.toInt() to total.toInt()
        }
        return AvailabilitySummary(selected.first, selected.second, CountReliability.Estimated)
    }

    companion object {
        const val MAX_FEATURES = 1000
    }
}
