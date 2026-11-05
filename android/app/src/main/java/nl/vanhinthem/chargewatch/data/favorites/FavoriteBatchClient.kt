package nl.vanhinthem.chargewatch.data.favorites

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import nl.vanhinthem.chargewatch.BuildConfig
import nl.vanhinthem.chargewatch.data.enrichment.HourlyAvailability
import nl.vanhinthem.chargewatch.data.enrichment.LocationEnrichment
import nl.vanhinthem.chargewatch.data.enrichment.parseEnrichment
import nl.vanhinthem.chargewatch.data.enrichment.parseHourlyAvailability
import nl.vanhinthem.chargewatch.data.enrichment.parseMapLocationDetail
import nl.vanhinthem.chargewatch.data.ndw.AvailabilitySummary
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Complete location detail and history can be shared by Favorites and the widget. */
data class FavoriteBatchLocation(
    val mapLocation: MapLocation,
    val enrichment: LocationEnrichment,
    val statistics: List<HourlyAvailability>
)

sealed interface FavoriteBatchResult {
    data class Success(
        val locations: Map<String, FavoriteBatchLocation>,
        val missingIds: Set<String>
    ) : FavoriteBatchResult
    data class Unavailable(val message: String) : FavoriteBatchResult
}

/**
 * Single read-only batch request. No viewport approximation and no favorite
 * aliases or local storage metadata leave the device.
 */
class FavoriteBatchClient(
    private val baseUrl: String = BuildConfig.CHARGEWATCH_API_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8,TimeUnit.SECONDS)
        .callTimeout(20,TimeUnit.SECONDS).build()
) {
    suspend fun load(ids: List<String>): FavoriteBatchResult = withContext(Dispatchers.IO) {
        require(ids.isNotEmpty() && ids.size <= MAX_BATCH && ids.distinct().size == ids.size)
        if (!baseUrl.startsWith("https://"))
            return@withContext FavoriteBatchResult.Unavailable("ChargeWatch API is niet geconfigureerd.")
        val json = buildJsonObject {
            put("featureIds",JsonArray(ids.map(::JsonPrimitive)))
            put("includeStatistics",true)
        }.toString()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/locations/by-ndw-id/batch")
            .header("Accept","application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 405)
                    return@use FavoriteBatchResult.Unavailable(
                        "De ChargeWatch API moet eerst worden bijgewerkt.")
                if (!response.isSuccessful)
                    return@use FavoriteBatchResult.Unavailable(
                        "Favorieten ophalen mislukte (HTTP ${response.code}).")
                val body=response.body?.string() ?: return@use FavoriteBatchResult.Unavailable(
                    "Lege ChargeWatch API-response.")
                runCatching { parseFavoriteBatchResponse(body,ids) }
                    .getOrElse { FavoriteBatchResult.Unavailable(
                        "Onvolledige of ongeldige favorietgegevens ontvangen.") }
            }
        } catch (_: IOException) {
            FavoriteBatchResult.Unavailable("ChargeWatch API is tijdelijk niet bereikbaar.")
        } catch (_: IllegalArgumentException) {
            FavoriteBatchResult.Unavailable("Ongeldige ChargeWatch API-URL.")
        }
    }

    companion object { const val MAX_BATCH = 20 }
}

/** Validate membership, completeness and requested order before publishing cache. */
internal fun parseFavoriteBatchResponse(raw: String, ids: List<String>): FavoriteBatchResult.Success {
    require(ids.size in 1..FavoriteBatchClient.MAX_BATCH && ids.size==ids.distinct().size)
    val root=Json.parseToJsonElement(raw).jsonObject
    val entries=root["locations"]?.jsonArray ?: error("Missing locations")
    require(entries.size == ids.size) { "Missing response entries" }
    val found=linkedMapOf<String,FavoriteBatchLocation>()
    val missing=linkedSetOf<String>()
    entries.forEachIndexed { index, entry ->
        val obj=entry.jsonObject
        val id=obj["featureId"]?.jsonPrimitive?.contentOrNull ?: error("Missing featureId")
        require(id==ids[index]) { "Out-of-order response" }
        when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "NOT_FOUND" -> {
                require(obj["detail"] == null || obj["detail"] is JsonNull)
                missing+=id
            }
            "FOUND" -> {
                val detail=obj["detail"]?.jsonObject ?: error("Missing detail")
                val stats=obj["statistics"]?.jsonObject ?: error("Missing full statistics")
                val longitude=detail["longitude"]?.jsonPrimitive?.doubleOrNull ?: error("Missing longitude")
                val latitude=detail["latitude"]?.jsonPrimitive?.doubleOrNull ?: error("Missing latitude")
                require(longitude in -180.0..180.0 && latitude in -90.0..90.0)
                val address=detail["address"]?.jsonPrimitive?.contentOrNull
                val seed=MapLocation(
                    featureId=id,longitude=longitude,latitude=latitude,
                    title=address?.takeIf { it.isNotBlank() } ?: "Laadlocatie",
                    lastUpdated=null,
                    availability=AvailabilitySummary(null,null,CountReliability.Ambiguous),
                    address=address
                )
                val detailJson=detail.toString()
                val projected=parseMapLocationDetail(detailJson,seed)
                val hourly=parseHourlyAvailability(stats.toString())
                val enrichment=parseEnrichment(detailJson).copy(
                    statisticsAvailable=hourly.isNotEmpty(),hourlyAvailability=hourly)
                found[id]=FavoriteBatchLocation(projected,enrichment,hourly)
            }
            else -> error("Invalid batch status")
        }
    }
    require(found.size+missing.size==ids.size)
    return FavoriteBatchResult.Success(found,missing)
}
