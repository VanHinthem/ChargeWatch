package nl.vanhinthem.chargewatch.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
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
    onReorder: (String, Int) -> Unit,
    onRemove: (FavoriteLocation, Int) -> Unit,
    onOpen: (FavoriteLocation) -> Unit
) {
    var editing by remember { mutableStateOf<FavoriteLocation?>(null) }
    var alias by remember { mutableStateOf("") }
    var visibleFavorites by remember { mutableStateOf(favorites) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val reorderThresholdPx = with(density) { 92.dp.toPx() }

    LaunchedEffect(favorites) {
        if (draggedId == null) visibleFavorites = favorites
    }

    fun swapDragged(direction: Int) {
        val id = draggedId ?: return
        val from = visibleFavorites.indexOfFirst { it.featureId == id }
        val to = from + direction
        if (from < 0 || to !in visibleFavorites.indices) return
        val updated = visibleFavorites.toMutableList()
        val item = updated.removeAt(from)
        updated.add(to, item)
        visibleFavorites = updated
        dragOffsetPx += if (direction > 0) -reorderThresholdPx else reorderThresholdPx
    }

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
                    items = visibleFavorites,
                    key = { _, favorite -> favorite.featureId }
                ) { index, favorite ->
                    val isDragging = draggedId == favorite.featureId
                    SwipeFavoriteCard(
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffsetPx else 0f
                                scaleX = if (isDragging) 1.015f else 1f
                                scaleY = if (isDragging) 1.015f else 1f
                            },
                        favorite = favorite,
                        availability = availability[favorite.featureId],
                        isDragging = isDragging,
                        gesturesEnabled = draggedId == null || isDragging,
                        onOpen = { onOpen(favorite) },
                        onEdit = {
                            alias = favorite.alias.orEmpty()
                            editing = favorite
                        },
                        onRemove = { onRemove(favorite, index) },
                        onDragStart = {
                            draggedId = favorite.featureId
                            dragOffsetPx = 0f
                        },
                        onDrag = { deltaY ->
                            if (draggedId == favorite.featureId) {
                                dragOffsetPx += deltaY
                                while (dragOffsetPx > reorderThresholdPx) {
                                    val before = visibleFavorites.indexOfFirst { it.featureId == favorite.featureId }
                                    swapDragged(1)
                                    val after = visibleFavorites.indexOfFirst { it.featureId == favorite.featureId }
                                    if (before == after) {
                                        dragOffsetPx = reorderThresholdPx
                                        break
                                    }
                                }
                                while (dragOffsetPx < -reorderThresholdPx) {
                                    val before = visibleFavorites.indexOfFirst { it.featureId == favorite.featureId }
                                    swapDragged(-1)
                                    val after = visibleFavorites.indexOfFirst { it.featureId == favorite.featureId }
                                    if (before == after) {
                                        dragOffsetPx = -reorderThresholdPx
                                        break
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            val target = visibleFavorites.indexOfFirst { it.featureId == favorite.featureId }
                            draggedId = null
                            dragOffsetPx = 0f
                            if (target >= 0) onReorder(favorite.featureId, target)
                        }
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
private fun SwipeFavoriteCard(
    modifier: Modifier,
    favorite: FavoriteLocation,
    availability: FavoriteAvailability?,
    isDragging: Boolean,
    gesturesEnabled: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.24f }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = gesturesEnabled && !isDragging,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    IconButton(
                        onClick = {
                            onEdit()
                            scope.launch { dismissState.reset() }
                        }
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Naam wijzigen",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    IconButton(
                        onClick = onRemove
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Favoriet verwijderen",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    ) {
        FavoriteCard(
            favorite = favorite,
            availability = availability,
            elevated = isDragging,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(favorite.featureId, gesturesEnabled) {
                    if (!gesturesEnabled) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDragStart()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd
                    )
                }
                .clickable(enabled = !isDragging, onClick = onOpen)
        )
    }
}

@Composable
private fun FavoriteCard(
    modifier: Modifier,
    favorite: FavoriteLocation,
    availability: FavoriteAvailability?,
    elevated: Boolean
) {
    val current = availability ?: FavoriteAvailability(
        label = "?",
        description = "Niet gecontroleerd",
        status = AvailabilityStatus.Unknown,
        sourceUpdated = null
    )
    val statusColor = favoriteStatusColor(current.status)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = if (elevated) 5.dp else 2.dp,
        shadowElevation = if (elevated) 8.dp else 0.dp
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

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Favoriet",
                        tint = Color(0xFFF3AF25),
                        modifier = Modifier.size(27.dp)
                    )
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Lang indrukken en slepen om te verplaatsen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .size(22.dp)
                    )
                }
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
