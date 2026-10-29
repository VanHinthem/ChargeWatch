package nl.vanhinthem.chargewatch.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.vanhinthem.chargewatch.data.favorites.FavoriteAvailability
import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
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
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Favorieten", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (favorites.size == 1) "1 opgeslagen laadlocatie"
                    else "${favorites.size} opgeslagen laadlocaties",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = onRefresh,
                    enabled = favorites.isNotEmpty()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Beschikbaarheid verversen")
                }
            }
        }

        refreshMessage?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (favorites.isEmpty()) {
            EmptyFavoritesState(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = favorites,
                    key = { _, favorite -> favorite.featureId }
                ) { index, favorite ->
                    FavoriteCard(
                        favorite = favorite,
                        availability = availability[favorite.featureId],
                        canMoveUp = index > 0,
                        canMoveDown = index < favorites.lastIndex,
                        onEdit = {
                            alias = favorite.alias.orEmpty()
                            editing = favorite
                        },
                        onMoveUp = { onMove(favorite.featureId, -1) },
                        onMoveDown = { onMove(favorite.featureId, 1) },
                        onRemove = { onRemove(favorite) }
                    )
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
                    supportingText = {
                        if (favorite.title != favorite.displayName) {
                            Text("Oorspronkelijke naam: ${favorite.title}")
                        }
                    },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(favorite.featureId, alias)
                        editing = null
                    }
                ) {
                    Text("Opslaan")
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text("Annuleren")
                }
            }
        )
    }
}

@Composable
private fun FavoriteCard(
    favorite: FavoriteLocation,
    availability: FavoriteAvailability?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val current = availability ?: FavoriteAvailability(
        label = "?",
        description = "Niet gecontroleerd",
        status = AvailabilityStatus.Unknown,
        sourceUpdated = null
    )
    val statusColor = favoriteStatusColor(current.status)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AvailabilityBadge(
                    label = current.label,
                    color = statusColor
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        favorite.displayName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    favorite.operatorName?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    favorite.address
                        ?.takeIf { it.isNotBlank() && it != favorite.displayName }
                        ?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                }

                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favoriet",
                    tint = Color(0xFFF3AF25),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(26.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            availabilityStatusLabel(current),
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    current.description,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Naam wijzigen")
                }
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Omhoog")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Omlaag")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Favoriet verwijderen",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun AvailabilityBadge(
    label: String,
    color: Color
) {
    Surface(
        modifier = Modifier.size(62.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Surface(
            modifier = Modifier.padding(4.dp),
            shape = CircleShape,
            color = color
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EmptyFavoritesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.StarBorder,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Nog geen favorieten",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            "Open een laadlocatie op de kaart en tik op de ster om hem hier op te slaan.",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun favoriteStatusColor(status: AvailabilityStatus): Color = when (status) {
    AvailabilityStatus.Available -> Color(0xFF168653)
    AvailabilityStatus.NoneAvailable -> Color(0xFFD43B3B)
    AvailabilityStatus.Unknown -> Color(0xFF818D96)
}

private fun availabilityStatusLabel(availability: FavoriteAvailability): String = when (availability.status) {
    AvailabilityStatus.Available -> "Beschikbaar"
    AvailabilityStatus.NoneAvailable -> "Geen laadpunten vrij"
    AvailabilityStatus.Unknown -> "Onbekend"
}
