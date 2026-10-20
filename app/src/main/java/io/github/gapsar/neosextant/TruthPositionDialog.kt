package io.github.gapsar.neosextant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The time as the user reads and types it; stored in [TruthPositions.utcFormat]. */
private fun displayFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
    isLenient = false
}

private fun toDisplay(utc: String): String = try {
    displayFormat().format(TruthPositions.utcFormat().parse(utc)!!)
} catch (_: Exception) { utc }

private fun fromDisplay(text: String): String? = try {
    TruthPositions.utcFormat().format(displayFormat().parse(text.trim())!!)
} catch (_: Exception) { null }

/**
 * Edits a true position. With [showTime] the user also types the UTC time the
 * position was read (it starts at [defaultTimeUtc]); without it, the time is
 * the moment of the save.
 */
@Composable
fun TruthPositionDialog(
    title: String,
    initial: TruthPosition?,
    defaultTimeUtc: String,
    showTime: Boolean,
    onSave: (TruthPosition) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var latText by remember { mutableStateOf(initial?.let { "%.5f".format(Locale.US, it.latitude) } ?: "") }
    var lonText by remember { mutableStateOf(initial?.let { "%.5f".format(Locale.US, it.longitude) } ?: "") }
    var timeText by remember { mutableStateOf(toDisplay(initial?.timeUtc ?: defaultTimeUtc)) }

    val lat = TruthPositions.parseCoordinate(latText, isLatitude = true)
    val lon = TruthPositions.parseCoordinate(lonText, isLatitude = false)
    val time = if (showTime) fromDisplay(timeText) else ""
    val canSave = lat != null && lon != null && time != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(S.truePositionHelp, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text(S.latitudeLabelCal) },
                    singleLine = true,
                    isError = latText.isNotBlank() && lat == null,
                    supportingText = if (latText.isNotBlank() && lat == null) { { Text(S.coordinateInvalid) } } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text(S.longitudeLabelCal) },
                    singleLine = true,
                    isError = lonText.isNotBlank() && lon == null,
                    supportingText = if (lonText.isNotBlank() && lon == null) { { Text(S.coordinateInvalid) } } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showTime) {
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = { timeText = it },
                        label = { Text(S.truthTimeLabel) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        isError = time == null,
                        supportingText = if (time == null) { { Text(S.truthTimeInvalid) } } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(TruthPosition(lat!!, lon!!, if (showTime) time!! else TruthPositions.nowUtc()))
                }
            ) { Text(if (showTime) S.save else S.saveReferenceNow) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = onClear) {
                        Text(S.clear, color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(S.cancel) }
            }
        }
    )
}
