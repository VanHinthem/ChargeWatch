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

    /**
     * A visible (screen-distance) cluster may comprise multiple backend cells.
     * The picker must page through ALL of its underlying locations; exposing
     * only the first canonical cell would silently hide sites.
     */
    suspend fun loadGroup(
        members: List<OverviewMarker>, offset: Int, limit: Int,
        filters: ChargingFilters, feedLastObservedAt: String? = null,
        feedIsStale: Boolean? = null
    ): OverviewCellResult {
        if (offset !in 0..100000 || limit !in 1..100 || members.isEmpty())
            return OverviewCellResult.Unavailable("Ongeldige cluster-aanvraag.")
        val total=members.sumOf { if (it is OverviewMarker.Cluster) it.locationCount.toLong() else 1L }
        if (total <= 0 || total > Int.MAX_VALUE)
            return OverviewCellResult.Unavailable("Ongeldige clustergrootte.")
        val slices=runCatching { overviewGroupPageSlices(members,offset,limit) }
            .getOrElse { return OverviewCellResult.Unavailable("Ongeldige clusterindeling.") }
        val locations=mutableListOf<MapLocation>()
        for (slice in slices) {
            when (val member=slice.marker) {
                is OverviewMarker.Location -> {
                    if (slice.localOffset != 0 || slice.limit != 1)
                        return OverviewCellResult.Unavailable("Ongeldige locatiepagina.")
                    locations+=member.asMapLocation(feedLastObservedAt,feedIsStale)
                }
                is OverviewMarker.Cluster -> {
                    when (val fetched=loadCell(member,slice.localOffset,slice.limit,filters)) {
                        is OverviewCellResult.Unavailable -> return fetched
                        is OverviewCellResult.Success -> {
                            // A feed update between requests invalidates this
                            // particular visual group's old membership.
                            if (fetched.page.totalCount != member.locationCount ||
                                fetched.page.locations.size != slice.limit)
                                return OverviewCellResult.Unavailable(
                                    "Clustergegevens zijn gewijzigd. Ververs de kaart.")
                            locations.addAll(fetched.page.locations)
                        }
                    }
                }
            }
        }
        return OverviewCellResult.Success(
            OverviewCellPage(offset,limit,total.toInt(),offset.toLong()+locations.size<total,locations)
        )
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
