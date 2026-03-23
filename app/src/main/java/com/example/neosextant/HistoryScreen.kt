package com.example.neosextant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
    historyRepository: HistoryRepository
) {
    // State to hold the list of entries
    var entries by remember { mutableStateOf(historyRepository.getHistory()) }

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

@Composable
fun HistoryCard(entry: PositionEntry, onDelete: () -> Unit) {
    val latStr = String.format(Locale.US, "%.5f°", entry.latitude)
    val lonStr = String.format(Locale.US, "%.5f°", entry.longitude)
    val errStr = if (entry.errorEstimateNm != null) {
        String.format(Locale.US, "%.2f NM", entry.errorEstimateNm)
    } else "N/A"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
            }

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
