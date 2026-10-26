package nl.vanhinthem.chargewatch.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Original connector-face line drawings, inspired by standard socket geometry.
 * They distinguish Type 2, CCS Combo 2, CHAdeMO and unknown connectors.
 */
@Composable
internal fun ConnectorSocketIcon(connectorType: String?, sizeDp: Int = 40) {
    val ink = MaterialTheme.colorScheme.onSurface
    val kind = when (connectorType?.uppercase(Locale.ROOT)) {
        "IEC_62196_T2", "TYPE_2" -> 2
        "IEC_62196_T2_COMBO", "CCS", "CCS2" -> 3
        "CHADEMO" -> 4
        else -> 0
    }
    Canvas(Modifier.size(sizeDp.dp)) {
        val u = size.minDimension / 100f
        val stroke = 5.2f * u
        fun contact(x: Float, y: Float, radius: Float = 5.2f) {
            drawCircle(ink, radius * u, Offset(x * u, y * u))
        }
        if (kind == 3) {
            // CCS Combo 2: upper Type 2 face, joined lower DC power contacts.
            val shape = Path().apply {
                moveTo(17f * u, 42f * u)
                cubicTo(17f * u, 8f * u, 83f * u, 8f * u, 83f * u, 42f * u)
                lineTo(83f * u, 59f * u)
                cubicTo(92f * u, 63f * u, 92f * u, 92f * u, 72f * u, 93f * u)
                lineTo(28f * u, 93f * u)
                cubicTo(8f * u, 92f * u, 8f * u, 63f * u, 17f * u, 59f * u)
                close()
            }
            drawPath(shape, ink, style = Stroke(stroke))
            listOf(35f to 32f, 50f to 27f, 65f to 32f,
                31f to 49f, 69f to 49f, 40f to 60f, 60f to 60f).forEach { contact(it.first, it.second, 3.7f) }
            contact(34f, 78f, 8f)
            contact(66f, 78f, 8f)
        } else if (kind == 4) {
            // CHAdeMO: distinct round connector face with asymmetrical contacts.
            drawCircle(ink, 40f * u, Offset(50f * u, 50f * u), style = Stroke(stroke))
            contact(29f, 35f, 8f)
            contact(71f, 35f, 8f)
            contact(34f, 69f, 8f)
            contact(66f, 69f, 8f)
            contact(50f, 50f, 4f)
            contact(50f, 20f, 3f)
        } else if (kind == 2) {
            // Type 2 / Mennekes: seven contacts inside a notched socket face.
            val shape = Path().apply {
                moveTo(20f * u, 35f * u)
                cubicTo(20f * u, 8f * u, 80f * u, 8f * u, 80f * u, 35f * u)
                lineTo(80f * u, 68f * u)
                lineTo(67f * u, 83f * u)
                lineTo(33f * u, 83f * u)
                lineTo(20f * u, 68f * u)
                close()
            }
            drawPath(shape, ink, style = Stroke(stroke))
            listOf(35f to 32f, 50f to 27f, 65f to 32f,
                31f to 53f, 69f to 53f, 41f to 68f, 59f to 68f).forEach { contact(it.first, it.second) }
        } else {
            drawCircle(ink, 38f * u, Offset(50f * u, 50f * u), style = Stroke(stroke))
            listOf(50f to 27f, 30f to 62f, 70f to 62f).forEach { contact(it.first, it.second) }
        }
    }
}
