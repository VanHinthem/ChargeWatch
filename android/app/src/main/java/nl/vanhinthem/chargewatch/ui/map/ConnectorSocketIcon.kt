package nl.vanhinthem.chargewatch.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Stylized socket face, not a manufacturer-specific plug photograph. */
@Composable
internal fun ConnectorSocketIcon(connectorType: String?) {
    val outline = MaterialTheme.colorScheme.onSurface
    val plate = MaterialTheme.colorScheme.surfaceVariant
    val kind = when (connectorType?.uppercase(Locale.ROOT)) {
        "IEC_62196_T2", "TYPE_2" -> 2
        "IEC_62196_T2_COMBO", "CCS", "CCS2" -> 3
        "CHADEMO" -> 4
        else -> 0
    }
    Canvas(Modifier.size(40.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * .42f
        drawCircle(plate, r, center)
        drawCircle(outline, r, center, style = Stroke(width = size.width * .055f))
        val holes: List<Pair<Float, Float>> = when (kind) {
            2, 3 -> listOf(
                -.36f to -.38f, 0f to -.51f, .36f to -.38f,
                -.42f to .03f, .42f to .03f,
                -.23f to .31f, .23f to .31f
            )
            4 -> listOf(
                -.47f to -.2f, -.12f to -.42f, .32f to -.32f,
                .46f to .12f, .12f to .42f, -.32f to .31f
            )
            else -> listOf(0f to -.35f, -.3f to .2f, .3f to .2f)
        }
        holes.forEach { (x, y) ->
            drawCircle(outline, size.width * .055f,
                Offset(center.x + x * r, center.y + y * r))
        }
        if (kind == 3) {
            // CCS adds two lower DC pins beneath the Type 2 contact arrangement.
            drawCircle(outline, size.width * .09f, Offset(center.x - r * .26f, center.y + r * .7f))
            drawCircle(outline, size.width * .09f, Offset(center.x + r * .26f, center.y + r * .7f))
        }
    }
}
