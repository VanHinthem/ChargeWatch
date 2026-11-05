package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.OverviewMarker

/** The storage grid is never used as a visible marker layout. */
internal data class ProjectedOverviewMarker(
    val marker: OverviewMarker,
    val x: Double,
    val y: Double
)

internal data class ProjectedLocationMarker(
    val location: MapLocation,
    val x: Double,
    val y: Double
)

internal data class OverviewVisualGroup(
    val members: List<OverviewMarker>,
    val locationCount: Int,
    val locationsWithFree: Int,
    val locationsWithNoFree: Int,
    val locationsUnknown: Int,
    val longitude: Double,
    val latitude: Double
) {
    val isIndividualLocation: Boolean
        get() = members.size == 1 && members.single() is OverviewMarker.Location
}

internal data class LocationVisualGroup(
    val members: List<MapLocation>,
    val locationCount: Int,
    val locationsWithFree: Int,
    val locationsWithNoFree: Int,
    val locationsUnknown: Int,
    val longitude: Double,
    val latitude: Double
)

/** Unified input: one position may represent one site or many server-side sites. */
private data class ProjectedGroupMember<T>(
    val item: T,
    val key: String,
    val x: Double,
    val y: Double,
    val longitude: Double,
    val latitude: Double,
    val count: Int,
    val free: Int,
    val noFree: Int,
    val unknown: Int
) {
    init {
        require(x.isFinite() && y.isFinite())
        require(longitude.isFinite() && latitude.isFinite())
        require(count > 0 && free >= 0 && noFree >= 0 && unknown >= 0)
        require(free.toLong() + noFree + unknown == count.toLong())
    }
}

private data class ProjectedGroup<T>(
    val members: List<T>,
    val count: Int,
    val free: Int,
    val noFree: Int,
    val unknown: Int,
    val longitude: Double,
    val latitude: Double
)

private class GroupBuilder<T>(first: ProjectedGroupMember<T>) {
    val items = mutableListOf(first)
    var x: Double = first.x
        private set
    var y: Double = first.y
        private set
    private var weight = first.count.toLong()

    fun add(item: ProjectedGroupMember<T>) {
        val nextWeight = weight + item.count
        x = (x * weight + item.x * item.count) / nextWeight
        y = (y * weight + item.y * item.count) / nextWeight
        weight = nextWeight
        items.add(item)
    }

    fun merge(other: GroupBuilder<T>) {
        val newWeight = weight + other.weight
        x = (x * weight + other.x * other.weight) / newWeight
        y = (y * weight + other.y * other.weight) / newWeight
        weight = newWeight
        items.addAll(other.items)
    }

    fun toGroup(): ProjectedGroup<T> {
        val count = items.sumOf { it.count }
        return ProjectedGroup(
            members = items.map { it.item },
            count = count,
            free = items.sumOf { it.free },
            noFree = items.sumOf { it.noFree },
            unknown = items.sumOf { it.unknown },
            longitude = items.sumOf { it.longitude * it.count } / count,
            latitude = items.sumOf { it.latitude * it.count } / count
        )
    }
}

/**
 * One screen-distance grouping engine for BOTH complete local sites and
 * nationwide backend summaries. Marker formatting and interaction must not
 * change when the viewport route switches.
 */
private fun <T> groupByScreenDistance(
    points: List<ProjectedGroupMember<T>>, thresholdPixels: Double
): List<ProjectedGroup<T>> {
    require(thresholdPixels > 0 && thresholdPixels.isFinite())
    val limit = thresholdPixels * thresholdPixels
    val ordered = points.sortedWith(
        compareByDescending<ProjectedGroupMember<T>> { it.count }
            .thenBy { it.x }.thenBy { it.y }.thenBy { it.key }
    )
    val builders = mutableListOf<GroupBuilder<T>>()
    ordered.forEach { point ->
        val closest = builders.minByOrNull { builder ->
            val dx = builder.x - point.x
            val dy = builder.y - point.y
            dx * dx + dy * dy
        }
        val squaredDistance = closest?.let { builder ->
            val dx = builder.x - point.x
            val dy = builder.y - point.y
            dx * dx + dy * dy
        } ?: Double.POSITIVE_INFINITY
        if (closest != null && squaredDistance < limit) closest.add(point)
        else builders.add(GroupBuilder(point))
    }
    var merged: Boolean
    do {
        merged = false
        outer@ for (i in builders.indices) {
            for (j in i + 1 until builders.size) {
                val a = builders[i]
                val b = builders[j]
                val dx = a.x - b.x
                val dy = a.y - b.y
                if (dx * dx + dy * dy < limit) {
                    a.merge(b)
                    builders.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
    } while (merged)
    return builders.map { it.toGroup() }
}

/** Complete viewports have actual per-location statuses and positions. */
internal fun clusterLocationsVisually(
    points: List<ProjectedLocationMarker>,
    thresholdPixels: Double
): List<LocationVisualGroup> = groupByScreenDistance(
    points.map { projected ->
        val location = projected.location
        val status = location.availability.status
        ProjectedGroupMember(
            item=location,
            key=location.featureId,
            x=projected.x,y=projected.y,
            longitude=location.longitude,latitude=location.latitude,
            count=1,
            free=if (status == AvailabilityStatus.Available) 1 else 0,
            noFree=if (status == AvailabilityStatus.NoneAvailable) 1 else 0,
            unknown=if (status == AvailabilityStatus.Unknown) 1 else 0
        )
    }, thresholdPixels
).map { group ->
    LocationVisualGroup(
        group.members,group.count,group.free,group.noFree,group.unknown,
        group.longitude,group.latitude
    )
}

/** Coarse backend summaries represent whole cells, never partial sites. */
internal fun clusterOverviewVisually(
    points: List<ProjectedOverviewMarker>,
    thresholdPixels: Double
): List<OverviewVisualGroup> = groupByScreenDistance(
    points.map { projected ->
        val marker = projected.marker
        val count: Int
        val green: Int
        val red: Int
        val unknown: Int
        when (marker) {
            is OverviewMarker.Cluster -> {
                count=marker.locationCount
                green=marker.locationsWithFree
                red=marker.locationsWithNoFree
                unknown=marker.locationsUnknown
            }
            is OverviewMarker.Location -> {
                count=1
                green=if (marker.availableCount > 0) 1 else 0
                red=if (marker.availableCount == 0 && marker.totalCount > 0) 1 else 0
                unknown=if (marker.totalCount == 0) 1 else 0
            }
        }
        ProjectedGroupMember(
            item=marker,
            key="${marker.gridLevel}:${marker.gridX}:${marker.gridY}",
            x=projected.x,y=projected.y,
            longitude=marker.longitude,latitude=marker.latitude,
            count=count,free=green,noFree=red,unknown=unknown
        )
    }, thresholdPixels
).map { group ->
    OverviewVisualGroup(
        group.members,group.count,group.free,group.noFree,group.unknown,
        group.longitude,group.latitude
    )
}
