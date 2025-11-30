package com.example.basic_neosextant

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    onNavigateBack: () -> Unit,
    getCurrentPitch: () -> Double?,
    onSaveCalibration: (Double) -> Unit,
    currentOffset: Double
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }
    
    var heightOfEye by remember { mutableStateOf("2.0") } // Default 2 meters
    var currentPitch by remember { mutableStateOf(0.0) }
    
    // Update pitch reading periodically
    LaunchedEffect(Unit) {
        while (true) {
            currentPitch = getCurrentPitch() ?: 0.0
            kotlinx.coroutines.delay(100)
        }
    }

    // Camera Setup
    LaunchedEffect(cameraProviderFuture) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview
            )
        } catch (exc: Exception) {
            Log.e("CalibrationScreen", "Use case binding failed", exc)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Horizon Calibration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Camera Preview
            AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

            // 2. Crosshair Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f
                
                // Horizontal line (Horizon target)
                drawLine(
                    color = Color.Red,
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = 4f
                )
                
                // Vertical center marker
                drawLine(
                    color = Color.Red,
                    start = Offset(width / 2f, centerY - 50f),
                    end = Offset(width / 2f, centerY + 50f),
                    strokeWidth = 4f
                )
            }

            // 3. Controls Overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(paddingValues) // Respect scaffold padding (mostly for bottom nav bars if any)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Align the Red Line with the Horizon",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = heightOfEye,
                        onValueChange = { heightOfEye = it },
                        label = { Text("Height of Eye (m)", color = Color.LightGray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier.width(150.dp)
                    )
                }

                Text(
                    text = "Sensor Pitch: %.2f°".format(currentPitch),
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodyLarge
                )
                
                 Text(
                    text = "Current Offset: %.2f°".format(currentOffset),
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = {
                        val h = heightOfEye.toDoubleOrNull() ?: 0.0
                        // Dip calculation
                        // Dip in arcminutes = 1.758 * sqrt(height in meters)
                        val dipArcMin = 1.758 * sqrt(h)
                        val dipDeg = dipArcMin / 60.0
                        
                        // The true horizon is at -dipDeg
                        // We measured 'currentPitch'
                        // So: True = Measured + Offset
                        // Offset = True - Measured
                        // Offset = -dipDeg - currentPitch
                        
                        val newOffset = -dipDeg - currentPitch
                        onSaveCalibration(newOffset)
                        onNavigateBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("CALIBRATE")
                }
            }
        }
    }
}
