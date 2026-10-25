package nl.vanhinthem.chargewatch.data.ndw

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NdwFeatureCollection(
    val type: String,
    val features: List<NdwFeature> = emptyList()
)

@Serializable
data class NdwFeature(
    val id: String? = null,
    val type: String,
    val geometry: NdwGeometry? = null,
    val properties: NdwProperties = NdwProperties()
)

@Serializable
data class NdwGeometry(
    val type: String,
    val coordinates: List<Double> = emptyList()
)

@Serializable
data class NdwProperties(
    val address: String? = null,
    @SerialName("operator_name") val operatorName: String? = null,
    @SerialName("cpo_id") val cpoId: String? = null,
    @SerialName("last_updated") val lastUpdated: String? = null,
    val availabilities: List<NdwAvailability> = emptyList()
)

@Serializable
data class NdwAvailability(
    val available: Int? = null,
    val total: Int? = null,
    @SerialName("connector_type") val connectorType: String? = null,
    @SerialName("connector_format") val connectorFormat: String? = null,
    @SerialName("power_type") val powerType: String? = null,
    @SerialName("power_max") val powerMax: Double? = null
)

data class MapLocation(
    val featureId: String,
    val longitude: Double,
    val latitude: Double,
    val title: String,
    val lastUpdated: String?,
    val availability: AvailabilitySummary
)

data class AvailabilitySummary(
    val available: Int?,
    val total: Int?,
    val reliability: CountReliability
) {
    val label: String
        get() = if (available != null && total != null) "$available/$total" else "?"

    val status: AvailabilityStatus
        get() = when {
            reliability == CountReliability.Ambiguous -> AvailabilityStatus.Unknown
            available == null || total == null -> AvailabilityStatus.Unknown
            available > 0 -> AvailabilityStatus.Available
            total > 0 -> AvailabilityStatus.NoneAvailable
            else -> AvailabilityStatus.Unknown
        }
}

enum class CountReliability {
    Safe,
    Estimated,
    Ambiguous
}

enum class AvailabilityStatus {
    Available,
    NoneAvailable,
    Unknown
}
