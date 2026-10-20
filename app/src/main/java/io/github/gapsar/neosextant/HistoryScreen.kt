package io.github.gapsar.neosextant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    historyRepository: HistoryRepository,
    onViewOnMap: (PositionEntry) -> Unit
) {
    // State to hold the list of entries
    var entries by remember { mutableStateOf(historyRepository.getHistory()) }
    var truthEditEntry by remember { mutableStateOf<PositionEntry?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val exportFailedLabel = S.exportFailed

    truthEditEntry?.let { entry ->
        fun saveTruth(t: TruthPosition?) {
            historyRepository.saveEntry(entry.copy(
                truthLatitude = t?.latitude, truthLongitude = t?.longitude, truthTimeUtc = t?.timeUtc))
            entries = historyRepository.getHistory()
            truthEditEntry = null
        }
        TruthPositionDialog(
            title = S.truePosition,
            initial = entry.truth(),
            defaultTimeUtc = fixTimeUtc(entry) ?: TruthPositions.nowUtc(),
            showTime = true,
            onSave = { saveTruth(it) },
            onClear = { saveTruth(null) },
            onDismiss = { truthEditEntry = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(S.positionHistory) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = S.back)
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = {
                            val message = try {
                                "exports/" + historyRepository.exportForEvaluation().name
                            } catch (e: Exception) {
                                android.util.Log.e("HistoryScreen", "Export failed", e)
                                "$exportFailedLabel: ${e.message}"
                            }
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                        }) {
                            Text(S.export)
                        }
                        TextButton(onClick = {
                            historyRepository.clearAll()
                            entries = historyRepository.getHistory()
                        }) {
                            Text(S.clearAll, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = S.noRecordedPositions,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        HistoryCard(
                            entry = entry,
                            onClick = { onViewOnMap(entry) },
                            onEditTruth = { truthEditEntry = entry },
                            onDelete = {
                                historyRepository.deleteEntry(entry.id)
                                entries = historyRepository.getHistory()
                            }
                        )
                    }
                }
            }
        }
    }
}

fun PositionEntry.truth(): TruthPosition? {
    val lat = truthLatitude ?: return null
    val lon = truthLongitude ?: return null
    return TruthPosition(lat, lon, truthTimeUtc ?: "")
}

/** UTC time of the first shot of the fix, in the shot timestamp format. */
private fun fixTimeUtc(entry: PositionEntry): String? = try {
    org.json.JSONArray(entry.imagesJson ?: "[]").optJSONObject(0)
        ?.optString("timestamp")?.takeIf { it.isNotEmpty() }
} catch (_: Exception) { null }

@Composable
fun HistoryCard(entry: PositionEntry, onClick: () -> Unit, onEditTruth: () -> Unit, onDelete: () -> Unit) {
    val truth = entry.truth()
    val fixTime = remember(entry) { fixTimeUtc(entry) }
    val latStr = String.format(Locale.US, "%.5f°", entry.latitude)
    val lonStr = String.format(Locale.US, "%.5f°", entry.longitude)
    val errStr = if (entry.errorEstimateNm != null) {
        String.format(Locale.US, "%.2f NM", entry.errorEstimateNm)
    } else "N/A"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.timestampStr,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text("Lat: $latStr", style = MaterialTheme.typography.bodyLarge)
                        Text("Lon: $lonStr", style = MaterialTheme.typography.bodyLarge)
                    }
                    Column {
                        Text("Mode: ${entry.mode}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text("Err: $errStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                if (truth != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val errorNm = TruthPositions.distanceNm(entry.latitude, entry.longitude, truth.latitude, truth.longitude)
                    val gapS = fixTime?.let { TruthPositions.secondsBetween(it, truth.timeUtc) }
                    Text(
                        S.fixError(errorNm) + (gapS?.takeIf { kotlin.math.abs(it) > 60.0 }
                            ?.let { " (" + S.truthTimeGap(it / 60.0) + ")" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = onEditTruth,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = S.truePosition,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = S.deleteEntry,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
