package nl.vanhinthem.chargewatch.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.vanhinthem.chargewatch.BuildConfig
import nl.vanhinthem.chargewatch.data.filters.ChargingFilters
import nl.vanhinthem.chargewatch.data.filters.supportsFiltering
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SearchLocation(
    val featureId: String,
    val address: String,
    val city: String,
    val operatorName: String?,
    val latitude: Double,
    val longitude: Double,
    val availableCount: Int,
    val totalCount: Int,
    val unknownCount: Int,
    val distanceMeters: Double? = null,
    val feedIsStale: Boolean? = null
)

sealed interface SearchResult {
    data class Success(val locations: List<SearchLocation>) : SearchResult
    data class Failure(val message: String) : SearchResult
}

class LocationSearchClient(
    private val baseUrl: String = BuildConfig.CHARGEWATCH_API_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    /** Existing PostGIS nearest-charger endpoint; no distance calculation in Android. */
    suspend fun nearby(latitude: Double, longitude: Double, radiusMeters: Int = 25000, limit: Int = 5, filters: ChargingFilters = ChargingFilters()): SearchResult =
        withContext(Dispatchers.IO) {
            if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0)
                return@withContext SearchResult.Failure("Ongeldige bestemmingscoördinaten.")
            if (!baseUrl.startsWith("https://"))
                return@withContext SearchResult.Failure("ChargeWatch API is niet geconfigureerd.")
            val url = (baseUrl.trimEnd('/') + "/api/v1/locations").toHttpUrl()
                .newBuilder()
                .addQueryParameter("latitude", latitude.toString())
                .addQueryParameter("longitude", longitude.toString())
                .addQueryParameter("radiusMeters", radiusMeters.coerceIn(100, 100000).toString())
                .addQueryParameter("limit", limit.coerceIn(1, 20).toString())
                .let(filters::appendTo)
                .build()
            try {
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                    .execute().use { response ->
                        if (!response.isSuccessful)
                            return@use SearchResult.Failure("Laadlocaties ophalen gaf HTTP ${response.code}.")
                        if (!supportsFiltering(filters.active, response.header("X-ChargeWatch-Filter-Version")))
                            return@use SearchResult.Failure("Filters vereisen een bijgewerkte ChargeWatch API.")
                        val raw = response.body?.string()
                            ?: return@use SearchResult.Failure("Lege laadlocatierespons.")
                        runCatching { SearchResult.Success(parseSearchLocations(raw)) }
                            .getOrElse { SearchResult.Failure("Ongeldige laadlocaties ontvangen.") }
                    }
            } catch (_: IOException) {
                SearchResult.Failure("Dichtstbijzijnde laadlocaties zijn tijdelijk niet beschikbaar.")
            } catch (_: IllegalArgumentException) {
                SearchResult.Failure("Ongeldige ChargeWatch API URL.")
            }
        }

    suspend fun search(query: String, limit: Int = 8): SearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext SearchResult.Success(emptyList())
        if (!baseUrl.startsWith("https://"))
            return@withContext SearchResult.Failure("ChargeWatch API is niet geconfigureerd.")

        val url = (baseUrl.trimEnd('/') + "/api/v1/locations").toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", trimmed)
            .addQueryParameter("limit", limit.coerceIn(1, 20).toString())
            .build()

        try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                .execute().use { response ->
                    if (!response.isSuccessful)
                        return@use SearchResult.Failure("Zoeken gaf HTTP ${response.code}.")
                    val raw = response.body?.string()
                        ?: return@use SearchResult.Failure("Lege zoekrespons.")
                    runCatching { SearchResult.Success(parseSearchLocations(raw)) }
                        .getOrElse { SearchResult.Failure("Ongeldige zoekresultaten ontvangen.") }
                }
        } catch (_: IOException) {
            SearchResult.Failure("Zoeken is tijdelijk niet beschikbaar.")
        } catch (_: IllegalArgumentException) {
            SearchResult.Failure("Ongeldige ChargeWatch API URL.")
        }
    }
}

internal fun parseSearchLocations(raw: String): List<SearchLocation> =
    Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
        val obj = element.jsonObject
        fun text(name: String) = obj[name]?.jsonPrimitive?.contentOrNull
        val country = text("countryCode") ?: return@mapNotNull null
        val party = text("partyId") ?: return@mapNotNull null
        val source = text("sourceId") ?: return@mapNotNull null
        val latitude = obj["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val longitude = obj["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@mapNotNull null

        SearchLocation(
            featureId = "$country-$party-$source",
            address = text("address").orEmpty(),
            city = text("city").orEmpty(),
            operatorName = text("operatorName"),
            latitude = latitude,
            longitude = longitude,
            availableCount = obj["availableCount"]?.jsonPrimitive?.intOrNull ?: 0,
            totalCount = obj["evseCount"]?.jsonPrimitive?.intOrNull ?: 0,
            unknownCount = obj["unknownCount"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE,
            distanceMeters = obj["distanceMeters"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 },
            feedIsStale = obj["feedIsStale"]?.jsonPrimitive?.booleanOrNull
        )
    }
