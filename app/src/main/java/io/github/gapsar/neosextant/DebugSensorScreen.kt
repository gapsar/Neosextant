package io.github.gapsar.neosextant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSensorScreen(
    rawAccel: String,
    rawGyro: String,
    kalmanCal: String,
    kalmanRaw: String,
    androidGrav: String,
    isRecording: Boolean,
    logFilePath: String,
    isWaveRecording: Boolean,
    waveRecordProgress: Float,
    waveModelResult: String?,
    iso: Int,
    onIsoChange: (Int) -> Unit,
    exposureTimeMs: Int,
    onExposureTimeChange: (Int) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onStartWaveModeling: () -> Unit,
    onOpenTrainingCapture: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensor Debug Logger") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { if (isRecording) onStopRecording() else onStartRecording() },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isRecording) "STOP RECORDING" else "START RECORDING (FASTEST)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (logFilePath.isNotEmpty()) {
                Text(
                    text = "Saving to:\n$logFilePath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            DataStreamView(title = "Raw Accelerometer", data = rawAccel)
            DataStreamView(title = "Raw Gyroscope", data = rawGyro)
            DataStreamView(title = "Kalman Filter (With Sphere Fit)", data = kalmanCal)
            DataStreamView(title = "Kalman Filter (Raw / No Sphere Fit)", data = kalmanRaw)
            DataStreamView(title = "Native Android Gravity", data = androidGrav)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Camera Settings (For Astrophotography Tweaking)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text("Exposure Time: $exposureTimeMs ms", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = exposureTimeMs.toFloat(),
                onValueChange = { onExposureTimeChange(it.toInt()) },
                valueRange = 50f..500f
            )

            Text("ISO: $iso", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = iso.toFloat(),
                onValueChange = { onIsoChange(it.toInt()) },
                valueRange = 100f..6400f
            )

            Button(
                onClick = onOpenTrainingCapture,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("TRAINING CAPTURE (KEEP ORIGINALS)", fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Bursts of 7 full-res photos with production capture settings; each is solved and kept with a JSON sidecar in training_images/ for the optimizer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Wave Modeling & True Gravity Extraction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Button(
                onClick = onStartWaveModeling,
                enabled = !isWaveRecording,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text(if (isWaveRecording) "RECORDING WAVE MODEL..." else "RECORD 30s WAVE MODEL", fontWeight = FontWeight.Bold)
            }

            if (isWaveRecording) {
                LinearProgressIndicator(
                    progress = { waveRecordProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (waveModelResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Wave Model Result", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))

                        var isSuccess = false
                        var tgStr = ""
                        var amp = 0.0
                        var period = 0.0
                        var errorMsg = ""
                        
                        try {
                            val json = org.json.JSONObject(waveModelResult)
                            if (json.optBoolean("success")) {
                                val tg = json.getJSONArray("true_gravity")
                                tgStr = String.format(java.util.Locale.US, "X: %+.4f\nY: %+.4f\nZ: %+.4f", tg.getDouble(0), tg.getDouble(1), tg.getDouble(2))
                                amp = json.getDouble("wave_amplitude_deg")
                                period = json.getDouble("wave_period_sec")
                                isSuccess = true
                            } else {
                                errorMsg = "Error: ${json.optString("error")}"
                            }
                        } catch (e: Exception) {
                            errorMsg = "Parsing Error: ${e.message}\nRaw: $waveModelResult"
                        }
                        
                        if (isSuccess) {
                            Text("True Gravity (Mean):", style = MaterialTheme.typography.labelSmall)
                            Text(tgStr, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Max Wave Amplitude: $amp °", style = MaterialTheme.typography.bodyMedium)
                            Text("Dominant Wave Period: $period s", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(errorMsg, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun DataStreamView(title: String, data: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = data,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
