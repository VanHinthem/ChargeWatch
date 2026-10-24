package nl.vanhinthem.chargewatch.data.ndw

import java.util.Locale
import kotlin.math.sqrt

data class NdwViewport(
    val minLongitude: Double,
    val minLatitude: Double,
    val maxLongitude: Double,
    val maxLatitude: Double
) {
    init {
        require(minLongitude < maxLongitude)
        require(minLatitude < maxLatitude)
        require(minLongitude in -180.0..180.0 && maxLongitude in -180.0..180.0)
        require(minLatitude in -90.0..90.0 && maxLatitude in -90.0..90.0)
    }

    val areaSquareDegrees: Double
        get() = (maxLongitude - minLongitude) * (maxLatitude - minLatitude)

    fun bounded(maxAreaSquareDegrees: Double = 1.0): NdwViewport {
        if (areaSquareDegrees <= maxAreaSquareDegrees) return this

        val scale = sqrt(maxAreaSquareDegrees / areaSquareDegrees)
        val centerLongitude = (minLongitude + maxLongitude) / 2.0
        val centerLatitude = (minLatitude + maxLatitude) / 2.0
        val halfWidth = (maxLongitude - minLongitude) * scale / 2.0
        val halfHeight = (maxLatitude - minLatitude) * scale / 2.0

        return NdwViewport(
            minLongitude = centerLongitude - halfWidth,
            minLatitude = centerLatitude - halfHeight,
            maxLongitude = centerLongitude + halfWidth,
            maxLatitude = centerLatitude + halfHeight
        )
    }

    fun asBboxParameter(): String = String.format(
        Locale.US,
        "%.6f,%.6f,%.6f,%.6f",
        minLongitude,
        minLatitude,
        maxLongitude,
        maxLatitude
    )
}
