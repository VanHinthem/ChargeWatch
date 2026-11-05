package nl.vanhinthem.chargewatch.data.ndw

import kotlinx.serialization.json.*
import nl.vanhinthem.chargewatch.data.filters.ChargingFilters
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.pow

sealed interface OverviewMarker {
    val gridLevel: Int
    val gridX: Long
    val gridY: Long
    val longitude: Double
    val latitude: Double

    data class Cluster(
        override val gridLevel: Int, override val gridX: Long, override val gridY: Long,
        override val longitude: Double, override val latitude: Double,
        val locationCount: Int, val locationsWithFree: Int,
        val locationsWithNoFree: Int, val locationsUnknown: Int
    ) : OverviewMarker

    data class Location(
        override val gridLevel: Int, override val gridX: Long, override val gridY: Long,
        override val longitude: Double, override val latitude: Double,
        val featureId: String, val availableCount: Int, val totalCount: Int,
        val countQuality: String
    ) : OverviewMarker {
        fun asMapLocation(feedLastObservedAt: String?, feedIsStale: Boolean?): MapLocation =
            MapLocation(featureId,longitude,latitude,"Laadlocatie",null,
                AvailabilitySummary(availableCount,totalCount,CountReliability.Safe),
                feedLastObservedAt=feedLastObservedAt,feedIsStale=feedIsStale)
    }
}

data class OverviewCoverage(
    val gridLevel: Int, val minCellX: Long, val minCellY: Long,
    val maxCellX: Long, val maxCellY: Long
) {
    fun includes(marker: OverviewMarker): Boolean =
        marker.gridLevel == gridLevel &&
        marker.gridX in minCellX..maxCellX && marker.gridY in minCellY..maxCellY
}

data class OverviewDataset(
    val markers: List<OverviewMarker>,
    val coverage: OverviewCoverage,
    val representedLocationCount: Long,
    val feedLastObservedAt: String?,
    val feedIsStale: Boolean
) {
    fun individualLocations(): List<MapLocation> = markers.filterIsInstance<OverviewMarker.Location>()
        .map { it.asMapLocation(feedLastObservedAt,feedIsStale) }
}

data class OverviewCellPage(
    val offset: Int, val limit: Int, val totalCount: Int,
    val hasMore: Boolean, val locations: List<MapLocation>
)

sealed interface OverviewResult {
    data class Success(val data: OverviewDataset) : OverviewResult
    data class RateLimited(val retryAfterSeconds: Long?) : OverviewResult
    data class Unavailable(val message: String) : OverviewResult
}

sealed interface OverviewCellResult {
    data class Success(val page: OverviewCellPage) : OverviewCellResult
    data class Unavailable(val message: String) : OverviewCellResult
}

/** Mirrors API MapGrid.Bounds; cell identity never depends on screen pixel positions. */
internal fun overviewCellBounds(level: Int, x: Long, y: Long): NdwViewport {
    require(level in 0..22)
    val n = 1L shl level
    require(x in 0 until n && y in 0 until n)
    val west = 360.0 * x / n - 180.0
    val east = 360.0 * (x+1) / n - 180.0
    fun latitude(yi: Long): Double = atan(kotlin.math.sinh(PI * (1-2.0*yi/n))) * 180 / PI
    return NdwViewport(west,latitude(y+1),east,latitude(y))
}

internal fun parseOverview(raw: String): OverviewDataset {
    val root = Json.parseToJsonElement(raw).jsonObject
    fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
    fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    require(root.int("schemaVersion") == 1 && root.int("filterVersion") == 1)
    require(root["dataVersion"] == null || root["dataVersion"] is JsonNull)
    val level = requireNotNull(root.int("gridLevel"))
    require(level in 0..22)
    val coverageObj = requireNotNull(root["coverage"]).jsonObject
    require(coverageObj["complete"]?.jsonPrimitive?.booleanOrNull == true)
    val coverage = OverviewCoverage(level,
        requireNotNull(coverageObj.long("minCellX")),requireNotNull(coverageObj.long("minCellY")),
        requireNotNull(coverageObj.long("maxCellX")),requireNotNull(coverageObj.long("maxCellY")))
    val n = 1L shl level
    require(coverage.minCellX in 0 until n && coverage.maxCellX in coverage.minCellX until n)
    require(coverage.minCellY in 0 until n && coverage.maxCellY in coverage.minCellY until n)
    val markers = requireNotNull(root["markers"]).jsonArray.map { item ->
        val obj = item.jsonObject
        val markerLevel = requireNotNull(obj.int("gridLevel"))
        val x = requireNotNull(obj.long("gridX"))
        val y = requireNotNull(obj.long("gridY"))
        val lon = requireNotNull(obj.number("longitude"))
        val lat = requireNotNull(obj.number("latitude"))
        require(lon.isFinite() && lat.isFinite() && lon in -180.0..180.0 && lat in -85.05113..85.05113)
        val marker: OverviewMarker = when (obj.text("kind")) {
            "cluster" -> {
                val count = requireNotNull(obj.int("locationCount"))
                val green = requireNotNull(obj.int("locationsWithFree"))
                val red = requireNotNull(obj.int("locationsWithNoFree"))
                val unknown = requireNotNull(obj.int("locationsUnknown"))
                require(count >= 2 && green >= 0 && red >= 0 && unknown >= 0 &&
                    green.toLong()+red+unknown == count.toLong())
                OverviewMarker.Cluster(markerLevel,x,y,lon,lat,count,green,red,unknown)
            }
            "location" -> {
                val id = requireNotNull(obj.text("featureId"))
                val available = requireNotNull(obj.int("availableCount"))
                val total = requireNotNull(obj.int("totalCount"))
                val quality = requireNotNull(obj.text("countQuality"))
                require(id.isNotBlank() && total >= 0 && available in 0..total &&
                    quality == if (total > 0) "known" else "unknown")
                OverviewMarker.Location(markerLevel,x,y,lon,lat,id,available,total,quality)
            }
            else -> error("Unknown overview marker kind")
        }
        require(coverage.includes(marker))
        marker
    }
    require(markers.size <= 500)
    require(markers.map { it.gridX to it.gridY }.distinct().size == markers.size)
    val represented = markers.sumOf { if (it is OverviewMarker.Cluster) it.locationCount.toLong() else 1L }
    require(represented == requireNotNull(root.long("representedLocationCount")))
    val feedIsStale = requireNotNull(root["feedIsStale"]?.jsonPrimitive?.booleanOrNull)
    return OverviewDataset(markers, coverage, represented, root.text("feedLastObservedAt"),feedIsStale)
}

internal fun parseOverviewCell(raw: String, cluster: OverviewMarker.Cluster, offset: Int, limit: Int): OverviewCellPage {
    val root = Json.parseToJsonElement(raw).jsonObject
    fun integer(key: String) = root[key]?.jsonPrimitive?.intOrNull
    require(integer("gridLevel") == cluster.gridLevel &&
        root["gridX"]?.jsonPrimitive?.longOrNull == cluster.gridX &&
        root["gridY"]?.jsonPrimitive?.longOrNull == cluster.gridY &&
        integer("offset") == offset && integer("limit") == limit)
    val total = requireNotNull(integer("totalCount"))
    val hasMore = requireNotNull(root["hasMore"]?.jsonPrimitive?.booleanOrNull)
    val locations = requireNotNull(root["items"]).jsonArray.map { element ->
        val obj = element.jsonObject
        val id = requireNotNull(obj["featureId"]?.jsonPrimitive?.contentOrNull)
        val lon = requireNotNull(obj["longitude"]?.jsonPrimitive?.doubleOrNull)
        val lat = requireNotNull(obj["latitude"]?.jsonPrimitive?.doubleOrNull)
        val free = requireNotNull(obj["availableCount"]?.jsonPrimitive?.intOrNull)
        val count = requireNotNull(obj["totalCount"]?.jsonPrimitive?.intOrNull)
        require(id.isNotBlank() && lon in -180.0..180.0 && lat in -85.05113..85.05113 &&
            free in 0..count)
        val address = obj["address"]?.jsonPrimitive?.contentOrNull
        MapLocation(id,lon,lat,address?.takeIf(String::isNotBlank) ?: "Laadlocatie",null,
            AvailabilitySummary(free,count,CountReliability.Safe),
            address=address,operatorName=obj["operatorName"]?.jsonPrimitive?.contentOrNull)
    }
    require(total >= 0 && locations.size <= limit &&
        (offset.toLong() + locations.size < total) == hasMore)
    return OverviewCellPage(offset,limit,total,hasMore,locations)
}

/** Deterministic pagination across a visual group of canonical cells. */
internal data class OverviewGroupPageSlice(
    val marker: OverviewMarker,
    val localOffset: Int,
    val limit: Int
)

internal fun overviewGroupPageSlices(
    members: List<OverviewMarker>, offset: Int, limit: Int
): List<OverviewGroupPageSlice> {
    require(offset in 0..100000 && limit in 1..100)
    var toSkip=offset.toLong()
    var remaining=limit
    val result=mutableListOf<OverviewGroupPageSlice>()
    val sorted=members.sortedWith(
        compareBy<OverviewMarker> { it.gridLevel }
            .thenBy { it.gridY }.thenBy { it.gridX }
    )
    require(sorted.map { Triple(it.gridLevel,it.gridX,it.gridY) }.distinct().size==sorted.size)
    for (item in sorted) {
        val count=if (item is OverviewMarker.Cluster) item.locationCount else 1
        require(count > 0)
        if (toSkip >= count) {
            toSkip-=count
            continue
        }
        if (remaining == 0) break
        val take=minOf(count-toSkip.toInt(),remaining)
        result+=OverviewGroupPageSlice(item,toSkip.toInt(),take)
        remaining-=take
        toSkip=0
    }
    return result
}
