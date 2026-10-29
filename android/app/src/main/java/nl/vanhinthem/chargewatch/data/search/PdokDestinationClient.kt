package nl.vanhinthem.chargewatch.data.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** PDOK Locatieserver v3.1 is a free NL-only address / street / place geocoder. */
data class DestinationSuggestion(val id: String, val label: String, val type: String)
data class Destination(val label: String, val latitude: Double, val longitude: Double, val type: String)

sealed interface DestinationSuggestionsResult {
    data class Success(val items: List<DestinationSuggestion>) : DestinationSuggestionsResult
    data class Failure(val message: String) : DestinationSuggestionsResult
}

sealed interface DestinationLookupResult {
    data class Success(val destination: Destination) : DestinationLookupResult
    data class Failure(val message: String) : DestinationLookupResult
}

/**
 * Direct client: no ChargeWatch API/NAS calls for geocoding, no API key, no query history.
 * Requests are cancelable, including in-flight OkHttp calls when the user types more.
 * https://api.pdok.nl/bzk/locatieserver/search/v3_1/ui/
 */
class PdokDestinationClient(
    private val baseUrl: String = "https://api.pdok.nl/bzk/locatieserver/search/v3_1",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    private data class Cached(val storedAtMillis: Long, val items: List<DestinationSuggestion>)
    private val cache = object : LinkedHashMap<String, Cached>(40, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > 40
    }

    suspend fun suggest(query: String, latitude: Double?, longitude: Double?, limit: Int = 8): DestinationSuggestionsResult {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.length < 2) return DestinationSuggestionsResult.Success(emptyList())
        val bias = if (latitude != null && longitude != null &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            // Quantized cache key; use identical bias for request and cache identity.
            Pair(Math.round(latitude * 10.0) / 10.0, Math.round(longitude * 10.0) / 10.0)
        } else null
        val safeLimit = limit.coerceIn(1, 8)
        val cacheKey = normalized.lowercase(Locale.ROOT) + "|" + bias + "|" + safeLimit
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[cacheKey]?.takeIf { now - it.storedAtMillis < 600_000 }?.let {
                return DestinationSuggestionsResult.Success(it.items)
            }
        }
        return try {
            val builder = (baseUrl.trimEnd('/') + "/suggest").toHttpUrl().newBuilder()
                .addQueryParameter("q", normalized)
                .addQueryParameter("rows", safeLimit.toString())
                .addQueryParameter("wt", "json")
                .addQueryParameter("fl", "id,weergavenaam,type")
            if (bias != null) {
                builder.addQueryParameter("lat", bias.first.toString())
                builder.addQueryParameter("lon", bias.second.toString())
            }
            val items = parsePdokSuggestions(fetch(builder.build()))
            synchronized(cache) { cache[cacheKey] = Cached(System.currentTimeMillis(), items) }
            DestinationSuggestionsResult.Success(items)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DestinationSuggestionsResult.Failure("Adressen zoeken via PDOK is tijdelijk niet beschikbaar.")
        }
    }

    suspend fun lookup(suggestion: DestinationSuggestion): DestinationLookupResult = try {
        val url = (baseUrl.trimEnd('/') + "/lookup").toHttpUrl().newBuilder()
            .addQueryParameter("id", suggestion.id)
            .addQueryParameter("wt", "json")
            .addQueryParameter("fl", "id,weergavenaam,type,centroide_ll")
            .build()
        val destination = parsePdokDestination(fetch(url))
        DestinationLookupResult.Success(destination)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DestinationLookupResult.Failure("Het gekozen adres kon niet worden gevonden.")
    }

    private suspend fun fetch(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw IOException("PDOK HTTP ${it.code}")
                        val text = it.body?.string() ?: throw IOException("Empty PDOK response")
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }
}

internal fun parsePdokSuggestions(raw: String): List<DestinationSuggestion> {
    val docs = Json.parseToJsonElement(raw).jsonObject["response"]?.jsonObject
        ?.get("docs")?.jsonArray ?: error("Missing PDOK docs")
    return docs.mapNotNull { row ->
        val obj = row.jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val label = obj["weergavenaam"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (type !in setOf("adres", "postcode", "weg", "woonplaats", "gemeente")) return@mapNotNull null
        DestinationSuggestion(id, label, type)
    }.distinctBy { it.id }
}

internal fun parsePdokDestination(raw: String): Destination {
    val docs = Json.parseToJsonElement(raw).jsonObject["response"]?.jsonObject
        ?.get("docs")?.jsonArray ?: error("Missing PDOK docs")
    val obj = docs.firstOrNull()?.jsonObject ?: error("No matching PDOK object")
    val wkt = obj["centroide_ll"]?.jsonPrimitive?.contentOrNull ?: error("Missing WGS84 centroid")
    val match = Regex("""POINT\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s*\\)""", RegexOption.IGNORE_CASE)
        .matchEntire(wkt) ?: error("Invalid WGS84 POINT")
    // WKT uses longitude first. Do not confuse it with the Dutch RD projection.
    val longitude = match.groupValues[1].toDouble()
    val latitude = match.groupValues[2].toDouble()
    require(latitude.isFinite() && longitude.isFinite() && latitude in 50.0..54.0 && longitude in 3.0..8.0) {
        "Invalid Netherlands location"
    }
    return Destination(
        label = obj["weergavenaam"]?.jsonPrimitive?.contentOrNull ?: error("Missing destination name"),
        latitude = latitude,
        longitude = longitude,
        type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    )
}
