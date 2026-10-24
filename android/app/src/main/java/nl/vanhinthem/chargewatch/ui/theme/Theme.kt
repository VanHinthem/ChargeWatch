package nl.vanhinthem.chargewatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AvailableGreen = Color(0xFF168653)
val NoneAvailableRed = Color(0xFFD43B3B)
val UnknownGray = Color(0xFF818D96)
val OccupancyBlue = Color(0xFF278DA7)
val FavoriteAmber = Color(0xFFF3AF25)
val LightSurface = Color(0xFFF5F7F8)
val DarkSurface = Color(0xFF151D24)
val DarkCard = Color(0xFF26313A)

private val LightColors = lightColorScheme(
    primary = AvailableGreen,
    error = NoneAvailableRed,
    surface = LightSurface,
    background = LightSurface
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF54B985),
    error = Color(0xFFFF6B6B),
    surface = DarkSurface,
    background = DarkSurface,
    surfaceVariant = DarkCard
)

@Composable
fun ChargeWatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
