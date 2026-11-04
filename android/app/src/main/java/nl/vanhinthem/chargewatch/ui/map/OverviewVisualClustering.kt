package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.ndw.OverviewMarker

/** A server grid cell is data ownership, never a visible map pin position. */
internal data class ProjectedOverviewMarker(
    val marker: OverviewMarker,
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
        get() = members.size == 1 && members[0] is OverviewMarker.Location
}

private fun siteCount(marker: OverviewMarker): Int =
    if (marker is OverviewMarker.Cluster) marker.locationCount else 1

private fun greenCount(marker: OverviewMarker): Int = when (marker) {
    is OverviewMarker.Cluster -> marker.locationsWithFree
    is OverviewMarker.Location -> if (marker.availableCount > 0) 1 else 0
}

private fun redCount(marker: OverviewMarker): Int = when (marker) {
    is OverviewMarker.Cluster -> marker.locationsWithNoFree
    is OverviewMarker.Location ->
        if (marker.availableCount == 0 && marker.totalCount > 0) 1 else 0
}

private fun unknownCount(marker: OverviewMarker): Int = when (marker) {
    is OverviewMarker.Cluster -> marker.locationsUnknown
    is OverviewMarker.Location -> if (marker.totalCount == 0) 1 else 0
}

private class Builder(points: List<ProjectedOverviewMarker>) {
    val points = points.toMutableList()
    var x = 0.0
        private set
    var y = 0.0
        private set
    var weight = 0
        private set

    init { recalculate() }

    fun add(point: ProjectedOverviewMarker) {
        points.add(point)
        recalculate()
    }

    fun addAll(other: Builder) {
        points.addAll(other.points)
        recalculate()
    }

    private fun recalculate() {
        weight = points.sumOf { siteCount(it.marker) }
        require(weight > 0)
        x = points.sumOf { it.x * siteCount(it.marker) } / weight
        y = points.sumOf { it.y * siteCount(it.marker) } / weight
    }

    fun result(): OverviewVisualGroup {
        val markers = points.map { it.marker }
        val count = markers.sumOf(::siteCount)
        val green = markers.sumOf(::greenCount)
        val red = markers.sumOf(::redCount)
        val unknown = markers.sumOf(::unknownCount)
        require(green + red + unknown == count)
        return OverviewVisualGroup(
            members=markers,
            locationCount=count,
            locationsWithFree=green,
            locationsWithNoFree=red,
            locationsUnknown=unknown,
            longitude=markers.sumOf { it.longitude * siteCount(it) } / count,
            latitude=markers.sumOf { it.latitude * siteCount(it) } / count
        )
    }
}

/**
 * Viewport-pixel grouping over the <=500 complete server summaries, just like
 * the original map's distance-based clustering over individual locations.
 * A coarse grid is *not* allowed to dictate the visible marker lattice.
 *
 * Works on location-weighted centroids; every source marker belongs to exactly
 * one visual cluster, with no approximation to its green/red/unknown counts.
 * The caller must pass the unmodified map projection coordinates.
 */
internal fun clusterOverviewVisually(
    points: List<ProjectedOverviewMarker>,
    thresholdPixels: Double
): List<OverviewVisualGroup> {
    require(thresholdPixels > 0 && thresholdPixels.isFinite())
    val sorted = points.sortedWith(
        compareByDescending<ProjectedOverviewMarker> { siteCount(it.marker) }
            .thenBy { it.x }.thenBy { it.y }
            .thenBy { it.marker.gridX }.thenBy { it.marker.gridY }
    )
    val clusters = mutableListOf<Builder>()
    val thresholdSquared = thresholdPixels * thresholdPixels
    sorted.forEach { point ->
        val nearest = clusters.minByOrNull {
            (it.x - point.x) * (it.x - point.x) +
                (it.y - point.y) * (it.y - point.y)
        }
        val distance = nearest?.let {
            (it.x - point.x) * (it.x - point.x) +
                (it.y - point.y) * (it.y - point.y)
        } ?: Double.POSITIVE_INFINITY
        if (nearest != null && distance < thresholdSquared) nearest.add(point)
        else clusters.add(Builder(listOf(point)))
    }
    // Identical merge pass to the old visual clustering behavior: avoid
    // competing neighboring circles after a group centroid has moved.
    var merged: Boolean
    do {
        merged = false
        outer@ for (i in clusters.indices) {
            for (j in i + 1 until clusters.size) {
                val first = clusters[i]
                val second = clusters[j]
                val dx = first.x - second.x
                val dy = first.y - second.y
                if (dx * dx + dy * dy < thresholdSquared) {
                    first.addAll(second)
                    clusters.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
    } while (merged)
    return clusters.map { it.result() }
}
