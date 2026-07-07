package io.github.gapsar.neosextant.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.gapsar.neosextant.model.*
import io.github.gapsar.neosextant.S

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageSlotView(
    modifier: Modifier = Modifier,
    imageInfo: ImageData?,
    isSelected: Boolean,
    isProcessing: Boolean,
    onClick: (ImageData) -> Unit,
    onLongClick: (ImageData) -> Unit = {}
) {
    val borderColor = when {
        isProcessing -> MaterialTheme.colorScheme.primary // Blue for processing
        imageInfo != null && imageInfo.burstSubResults.isNotEmpty() -> {
            // Burst-aware border color
            val allDone = imageInfo.burstSubResults.all { it.tetra3Result.analysisState != AnalysisState.PENDING }
            val solvedCount = imageInfo.burstSubResults.count { it.tetra3Result.solved }
            when {
                !allDone -> MaterialTheme.colorScheme.primary
                solvedCount == imageInfo.burstSubResults.size -> Color(0xFF4CAF50) // Green: all solved
                solvedCount > 0 -> Color(0xFFFF9800) // Orange: partial
                else -> Color(0xFFF44336) // Red: all failed
            }
        }
        imageInfo?.tetra3Result?.analysisState == AnalysisState.SUCCESS && imageInfo.tetra3Result.solved -> Color(0xFF4CAF50) // Green for solved
        imageInfo?.tetra3Result?.analysisState == AnalysisState.FAILURE -> MaterialTheme.colorScheme.error // Red for failure
        else -> Color.Gray // Default for empty or pending
    }

    val borderWidth = if (isSelected) 4.dp else 2.dp

    Card(
        modifier = modifier
            .combinedClickable(
                enabled = imageInfo != null,
                onClick = { imageInfo?.let { onClick(it) } },
                onLongClick = { imageInfo?.let { onLongClick(it) } }
            ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (imageInfo != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageInfo.uri)
                        .crossfade(true)
                        .size(256)
                        .memoryCacheKey(imageInfo.uri.toString())
                        .diskCachePolicy(coil.request.CachePolicy.DISABLED)
                        .build(),
                    contentDescription = S.capturedImage,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (isProcessing) {
                    CircularProgressIndicator(color = Color.White)
                }

                // Burst progress badge
                if (imageInfo.burstSubResults.isNotEmpty()) {
                    val totalBurst = imageInfo.burstSubResults.size
                    val processedBurst = imageInfo.burstSubResults.count {
                        it.tetra3Result.analysisState != AnalysisState.PENDING
                    }
                    val solvedBurst = imageInfo.burstSubResults.count {
                        it.tetra3Result.solved
                    }
                    val failedBurst = imageInfo.burstSubResults.count {
                        it.tetra3Result.analysisState == AnalysisState.FAILURE
                    }
                    val allDone = processedBurst == totalBurst
                    val badgeColor = when {
                        !allDone -> MaterialTheme.colorScheme.primary // Blue while still processing
                        solvedBurst == totalBurst -> Color(0xFF4CAF50) // Green: all solved
                        solvedBurst > 0 -> Color(0xFFFF9800) // Orange: some solved, some failed
                        else -> Color(0xFFF44336) // Red: all failed
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(badgeColor.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$processedBurst/$totalBurst",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(S.empty)
                }
            }
        }
    }
}

