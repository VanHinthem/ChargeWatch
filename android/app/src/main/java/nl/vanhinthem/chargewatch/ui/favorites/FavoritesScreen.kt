package nl.vanhinthem.chargewatch.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
import nl.vanhinthem.chargewatch.data.favorites.FavoriteAvailability
import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus

@Composable
fun FavoritesScreen(
    favorites: List<FavoriteLocation>,
    availability: Map<String, FavoriteAvailability>,
    refreshing: Boolean,
    refreshMessage: String?,
    onRefresh: () -> Unit,
    onRename: (String, String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (FavoriteLocation) -> Unit
) {
    var editing by remember { mutableStateOf<FavoriteLocation?>(null) }
    var alias by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Favorieten", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onRefresh, enabled = !refreshing && favorites.isNotEmpty()) { Text(if (refreshing) "Verversen…" else "Beschikbaarheid verversen") }
        if (refreshing) CircularProgressIndicator()
        refreshMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (favorites.isEmpty()) {
            Text("Nog geen favorieten. Tik op een laadlocatie op de kaart en kies Voeg toe aan favorieten.")
        }
        favorites.forEachIndexed { index, favorite ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(favorite.displayName, style = MaterialTheme.typography.titleMedium)
                    favorite.address?.takeIf { it != favorite.displayName }?.let { Text(it) }
                    val current = availability[favorite.featureId]
                    Text(
                        if (current == null) "? · Niet gecontroleerd" else "${current.label} · ${current.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { alias = favorite.alias.orEmpty(); editing = favorite }) {
                            Icon(Icons.Default.Edit, contentDescription = "Naam wijzigen")
                        }
                        IconButton(onClick = { onMove(favorite.featureId, -1) }, enabled = index > 0) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Omhoog")
                        }
                        IconButton(onClick = { onMove(favorite.featureId, 1) }, enabled = index < favorites.lastIndex) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Omlaag")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onRemove(favorite) }) { Text("Verwijderen") }
                    }
                }
            }
        }
    }

    editing?.let { favorite ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Favoriet hernoemen") },
            text = {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Eigen naam") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(favorite.featureId, alias); editing = null }) { Text("Opslaan") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuleren") } }
        )
    }
}
