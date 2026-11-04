package nl.vanhinthem.chargewatch.data.ndw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.vanhinthem.chargewatch.BuildConfig
import nl.vanhinthem.chargewatch.data.filters.ChargingFilters
import nl.vanhinthem.chargewatch.data.filters.supportsFiltering
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Feature-gated API. Never silently converts an incomplete overview to legacy viewport data. */
class MapOverviewClient(
    private val baseUrl: String = BuildConfig.CHARGEWATCH_API_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
) {
    suspend fun load(viewport: NdwViewport, zoom: Double, filters: ChargingFilters): OverviewResult =
        withContext(Dispatchers.IO) {
            if (!baseUrl.startsWith("https://") || zoom !in 0.0..22.0 || !zoom.isFinite())
                return@withContext OverviewResult.Unavailable("Ongeldige overview-configuratie.")
            val url = (baseUrl.trimEnd('/')+"/api/v1/map/overview").toHttpUrl().newBuilder()
                .addQueryParameter("bbox",viewport.asBboxParameter())
                .addQueryParameter("zoom",zoom.toString())
                .let(filters::appendTo).build()
            try {
                client.newCall(Request.Builder().url(url).header("Accept","application/json").build())
                    .execute().use { response ->
                        if (response.code == 429) return@use OverviewResult.RateLimited(
                            response.header("Retry-After")?.toLongOrNull())
                        if (response.code == 404) return@use OverviewResult.Unavailable(
                            "Landelijke kaart is nog niet geactiveerd op de ChargeWatch API.")
                        if (!response.isSuccessful) return@use OverviewResult.Unavailable(
                            "Landelijke kaart gaf HTTP ${response.code}.")
                        if (!supportsFiltering(filters.active,response.header("X-ChargeWatch-Filter-Version")))
                            return@use OverviewResult.Unavailable("Overzicht ondersteunt deze filters niet.")
                        val body = response.body?.string()
                            ?: return@use OverviewResult.Unavailable("Leeg kaartoverzicht.")
                        runCatching { OverviewResult.Success(parseOverview(body)) }
                            .getOrElse { OverviewResult.Unavailable("Ongeldige of onvolledige landelijke kaartgegevens.") }
                    }
            } catch (_: IOException) {
                OverviewResult.Unavailable("Landelijke kaart is tijdelijk niet bereikbaar.")
            } catch (_: IllegalArgumentException) {
                OverviewResult.Unavailable("Ongeldige kaartaanvraag.")
            }
        }

    suspend fun loadCell(
        cluster: OverviewMarker.Cluster, offset: Int, limit: Int,
        filters: ChargingFilters
    ): OverviewCellResult = withContext(Dispatchers.IO) {
        if (!baseUrl.startsWith("https://") || offset < 0 || limit !in 1..100)
            return@withContext OverviewCellResult.Unavailable("Ongeldige cel-aanvraag.")
        val url = (baseUrl.trimEnd('/')+"/api/v1/map/overview/cell").toHttpUrl().newBuilder()
            .addQueryParameter("gridLevel",cluster.gridLevel.toString())
            .addQueryParameter("gridX",cluster.gridX.toString())
            .addQueryParameter("gridY",cluster.gridY.toString())
            .addQueryParameter("offset",offset.toString())
            .addQueryParameter("limit",limit.toString())
            .let(filters::appendTo).build()
        try {
            client.newCall(Request.Builder().url(url).header("Accept","application/json").build())
                .execute().use { response ->
                    if (!response.isSuccessful) return@use OverviewCellResult.Unavailable(
                        "Clusterlocaties ophalen gaf HTTP ${response.code}.")
                    if (!supportsFiltering(filters.active,response.header("X-ChargeWatch-Filter-Version")))
                        return@use OverviewCellResult.Unavailable("Clusterfilter wordt niet ondersteund.")
                    val body = response.body?.string()
                        ?: return@use OverviewCellResult.Unavailable("Lege clusterrespons.")
                    runCatching { OverviewCellResult.Success(parseOverviewCell(body,cluster,offset,limit)) }
                        .getOrElse { OverviewCellResult.Unavailable("Ongeldige clusterlocaties ontvangen.") }
                }
        } catch (_: IOException) {
            OverviewCellResult.Unavailable("Clusterlocaties zijn tijdelijk niet bereikbaar.")
        } catch (_: IllegalArgumentException) {
            OverviewCellResult.Unavailable("Ongeldige clusteraanvraag.")
        }
    }
}
