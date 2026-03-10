package com.example.basic_neosextant.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.basic_neosextant.model.*
import java.util.*

@Composable
fun ImageMetadataCard(imageInfo: ImageData, onRemoveClick: () -> Unit) {
    Card(
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Image Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            MetadataRow("File Name:", imageInfo.name)
            MetadataRow("Timestamp:", imageInfo.timestamp)
            imageInfo.measuredHeight?.let { MetadataRow("Measured Height:", String.format(Locale.US, "%.2f°", it)) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

            when (imageInfo.tetra3Result.analysisState) {
                AnalysisState.PENDING -> MetadataRow("Status:", "Processing...")
                AnalysisState.FAILURE -> MetadataRow("Status:", "Failed: ${imageInfo.tetra3Result.errorMessage}")
                AnalysisState.SUCCESS -> {
                    if (imageInfo.tetra3Result.solved) {
                        MetadataRow("Status:", "Solved")
                        imageInfo.tetra3Result.raDeg?.let { MetadataRow("RA:", String.format(Locale.US, "%.4f°", it)) }
                        imageInfo.tetra3Result.decDeg?.let { MetadataRow("Dec:", String.format(Locale.US, "%.4f°", it)) }

                        // LOP Data (only in LOP mode or when lopData is present)
                        imageInfo.lopData?.let { lop ->
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("LOP Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            if (lop.errorMessage != null) {
                                MetadataRow("Error:", lop.errorMessage)
                            } else {
                                lop.interceptNm?.let { MetadataRow("Intercept:", String.format(Locale.US, "%.2f NM", it)) }
                                lop.azimuthDeg?.let { MetadataRow("Azimuth:", String.format(Locale.US, "%.2f°", it)) }
                            }
                        }
                    } else {
                        MetadataRow("Status:", "Not Solved")
                        imageInfo.tetra3Result.errorMessage?.let { MetadataRow("Reason:", it) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onRemoveClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Remove Image")
            }
        }
    }
}


@Composable
fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top // Aligns label and value to the top
    ) {
        // Label Text
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(150.dp) // Increased width for better spacing
        )
        // Value Text
        Text(
            text = value,
            modifier = Modifier.weight(1f) // Ensures value text uses remaining space and wraps properly
        )
    }
}
