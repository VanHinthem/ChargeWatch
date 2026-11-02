package nl.vanhinthem.chargewatch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.vanhinthem.chargewatch.ui.ChargeWatchApp
import nl.vanhinthem.chargewatch.widget.WIDGET_FAVORITE_PARAM
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    private var widgetTarget by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        widgetTarget = readWidgetTarget(intent)
        setContent {
            ChargeWatchApp(
                widgetTarget = widgetTarget,
                onWidgetTargetConsumed = { widgetTarget = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetTarget = readWidgetTarget(intent)
    }

    private fun readWidgetTarget(intent: Intent?): String? {
        if (intent?.action != "nl.vanhinthem.chargewatch.OPEN_WIDGET_FAVORITE") return null
        val uri = intent.data ?: return null
        if (uri.scheme != "chargewatch" || uri.host != "widget" ||
            uri.pathSegments.size != 2 || uri.pathSegments[0].toIntOrNull() == null) return null
        val featureId = uri.pathSegments[1]
        return featureId.takeIf { it.isNotBlank() && it == intent.getStringExtra(WIDGET_FAVORITE_PARAM) }
    }
}
