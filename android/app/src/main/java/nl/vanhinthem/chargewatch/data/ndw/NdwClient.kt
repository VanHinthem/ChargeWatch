package nl.vanhinthem.chargewatch.data.ndw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class NdwClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val parser: NdwGeoJsonParser = NdwGeoJsonParser()
) {
    suspend fun load(viewport: NdwViewport): NdwResult = withContext(Dispatchers.IO) {
        val bounded = viewport.bounded()
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("bbox", bounded.asBboxParameter())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/geo+json, application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 429 -> NdwResult.RateLimited(
                        retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                    )
                    response.code in 500..599 -> NdwResult.Unavailable("NDW gaf HTTP ${response.code}.")
                    !response.isSuccessful -> NdwResult.Unavailable("NDW gaf HTTP ${response.code}.")
                    else -> {
                        val raw = response.body?.string()
                            ?: return@use NdwResult.Unavailable("NDW gaf een lege response.")
                        try {
                            NdwResult.Success(parser.parse(raw))
                        } catch (ex: Exception) {
                            NdwResult.Unavailable("NDW-data kon niet worden verwerkt.")
                        }
                    }
                }
            }
        } catch (ex: IOException) {
            NdwResult.Unavailable("NDW is tijdelijk niet bereikbaar.")
        }
    }

    companion object {
        const val ENDPOINT =
            "https://dotnl.ndw.nu/api/rest/geojson/dynamic-road-status/charge-point-data/v1/features"
        private const val USER_AGENT = "ChargeWatch-Android/0.1 (+https://github.com/VanHinthem/ChargeWatch)"
    }
}

sealed interface NdwResult {
    data class Success(val locations: List<MapLocation>) : NdwResult
    data class RateLimited(val retryAfterSeconds: Long?) : NdwResult
    data class Unavailable(val message: String) : NdwResult
}
