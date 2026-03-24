package io.github.gapsar.neosextant.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
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
                Image(
                    painter = rememberAsyncImagePainter(imageInfo.uri),
                    contentDescription = S.capturedImage,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                if (isProcessing) {
                    CircularProgressIndicator(color = Color.White)
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
