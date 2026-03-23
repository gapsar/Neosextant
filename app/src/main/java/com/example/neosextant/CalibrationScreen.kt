package com.example.neosextant

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

// Helper to access accelerometer for the calibration dialog
// We need to pass the sensor values from MainActivity or listen here.
// Since MainActivity already listens, we can pass a "getRawAccel" function or similar.
// Or just let MainActivity pass `sensorCalibrator`.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    onNavigateBack: () -> Unit,
    getRawPitch: () -> Double?,
    onSaveCalibration: (Double) -> Unit,
    currentOffset: Double,
    sensorCalibrator: SensorCalibrator,
    sensorPipeline: SensorPipeline,
    rawAccelState: androidx.compose.runtime.State<SensorCalibrator.Vec3>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }

    var heightOfEye by remember { mutableStateOf("2.0") } // Default 2 meters
    var currentPitch by remember { mutableStateOf(0.0) }
    var showAdvancedDialog by remember { mutableStateOf(false) }

    // Update pitch reading periodically
    LaunchedEffect(Unit) {
        while (true) {
            // We now get the RAW pitch directly from MainActivity, which is unaffected
            // by the currently saved calibration offset.
            val rawAlt = getRawPitch() ?: 0.0

            currentPitch = rawAlt
            kotlinx.coroutines.delay(100)
        }
    }

    // Camera Setup (C-02: Non-blocking init)
    LaunchedEffect(cameraProviderFuture) {
        val cameraProvider = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cameraProviderFuture.get()
        }
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

    DisposableEffect(Unit) {
        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    Log.d("CalibrationScreen", "Camera unbound on dispose")
                }
            } catch (e: Exception) {
                Log.w("CalibrationScreen", "Error unbinding camera on dispose", e)
            }
        }
    }

    // --- UI Logic ---

    // We replace the "Advanced" dialog with this polished Sphere Calibration flow.
    if (showAdvancedDialog) {
        SphereCalibrationDialog(
            onDismiss = { showAdvancedDialog = false },
            calibrator = sensorCalibrator,
            context = context,
            rawAccelState = rawAccelState
        )
    }

    Scaffold(
        topBar = {
        // ... (No changes to topbar, but for context in replacement)
            TopAppBar(
                title = { Text(S.horizonCalibration) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = S.back)
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
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = S.alignHorizon,
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
                        label = { Text(S.heightOfEye, color = Color.LightGray) },
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
                    text = S.sensorPitchFmt.format(currentPitch),
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodyLarge
                )

                 Text(
                    text = S.currentOffsetFmt.format(currentOffset),
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showAdvancedDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        modifier = Modifier.weight(1f).tutorialTarget(4)
                    ) {
                         Text(S.calibrateSensorsUpper, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            val h = heightOfEye.toDoubleOrNull() ?: 0.0
                            val dipArcMin = 1.758 * sqrt(h)
                            val dipDeg = dipArcMin / 60.0

                            // Offset = True - Measured
                            // True = -dipDeg
                            // Measured = currentPitch (We ensured this is Raw above)
                            val newOffset = -dipDeg - currentPitch
                            onSaveCalibration(newOffset)
                            onNavigateBack()
                        },
                        modifier = Modifier.weight(1f).tutorialTarget(3),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(S.setHorizon)
                    }
                }
            }
        }
    }
}

@Composable
fun SphereCalibrationDialog(
    onDismiss: () -> Unit,
    calibrator: SensorCalibrator,
    context: Context,
    rawAccelState: State<SensorCalibrator.Vec3>
) {
    // Use shared raw accelerometer state from MainActivity (no duplicate listener)
    val vibrator = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
    }

    // State
    var currentAccel by remember { mutableStateOf(SensorCalibrator.Vec3(0f, 0f, 0f)) }
    var step by remember { mutableIntStateOf(0) }
    var isStable by remember { mutableStateOf(false) }
    var waitingForMove by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var lastCapturedVector by remember { mutableStateOf<SensorCalibrator.Vec3?>(null) }

    // Buffer for stability check
    val stabilityWindow = remember { ArrayDeque<SensorCalibrator.Vec3>() }
    val captureBuffer = remember { mutableListOf<SensorCalibrator.Vec3>() }
    val WINDOW_SIZE = 30
    val VARIANCE_THRESHOLD = 0.05f
    val CAPTURE_COUNT = 50

    val steps = S.sphereSteps

    // Initialize calibrator
    DisposableEffect(Unit) {
        calibrator.clearPoints()
        onDispose { }
    }

    // Read raw accel from shared state (no duplicate sensor listener)
    LaunchedEffect(Unit) {
        var lastProcessedAccel = SensorCalibrator.Vec3(0f, 0f, 0f)
        while (true) {
            val vec = rawAccelState.value
            // Only process if the value actually changed
            if (vec.x != lastProcessedAccel.x || vec.y != lastProcessedAccel.y || vec.z != lastProcessedAccel.z) {
                lastProcessedAccel = vec
                currentAccel = vec

                // 1. Update Stability Window
                if (stabilityWindow.size >= WINDOW_SIZE) stabilityWindow.removeFirst()
                stabilityWindow.add(vec)

                // 2. Check Stability
                if (stabilityWindow.size == WINDOW_SIZE) {
                    val meanX = stabilityWindow.map { v -> v.x }.average()
                    val meanY = stabilityWindow.map { v -> v.y }.average()
                    val meanZ = stabilityWindow.map { v -> v.z }.average()
                    val currentMean = SensorCalibrator.Vec3(meanX.toFloat(), meanY.toFloat(), meanZ.toFloat())

                    val varX = stabilityWindow.map { v -> (v.x - meanX) * (v.x - meanX) }.average()
                    val varY = stabilityWindow.map { v -> (v.y - meanY) * (v.y - meanY) }.average()
                    val varZ = stabilityWindow.map { v -> (v.z - meanZ) * (v.z - meanZ) }.average()

                    val totalVar = varX + varY + varZ
                    var stableNow = totalVar < VARIANCE_THRESHOLD

                    waitingForMove = false
                    if (stableNow && lastCapturedVector != null) {
                        val dist = (currentMean - lastCapturedVector!!).magnitude()
                        if (dist < 4.0f) {
                            stableNow = false
                            waitingForMove = true
                        }
                    }

                    isStable = stableNow
                } else {
                    isStable = false
                    waitingForMove = false
                }

                // 3. Auto-Capture if Stable and not finished
                if (step < steps.size && isStable) {
                    captureBuffer.add(vec)
                    progress = captureBuffer.size / CAPTURE_COUNT.toFloat()

                    if (captureBuffer.size >= CAPTURE_COUNT) {
                        val avgX = captureBuffer.map { v -> v.x }.average().toFloat()
                        val avgY = captureBuffer.map { v -> v.y }.average().toFloat()
                        val avgZ = captureBuffer.map { v -> v.z }.average().toFloat()
                        val avgVec = SensorCalibrator.Vec3(avgX, avgY, avgZ)

                        calibrator.recordDataPoint(avgX, avgY, avgZ)
                        lastCapturedVector = avgVec

                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(200)
                        }

                        captureBuffer.clear()
                        stabilityWindow.clear()
                        isStable = false
                        progress = 0f
                        step++
                    }
                } else {
                    if (!isStable && captureBuffer.isNotEmpty()) {
                        captureBuffer.clear() // Clear all contaminated readings
                        progress = 0f
                    }
                }
            }
            kotlinx.coroutines.delay(16) // ~60fps polling from shared state
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = S.sensorCalibrationTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )

                if (step < steps.size) {
                    // Progress Indicator
                    LinearProgressIndicator(
                        progress = { (step + progress) / steps.size.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color.Green,
                        trackColor = Color.DarkGray,
                    )

                    Text(
                        text = S.stepProgressFmt.format(step + 1, steps.size),
                        color = Color.Gray
                    )

                    // Main Instruction
                    Text(
                        text = steps[step],
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.Yellow,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    // Status
                    if (waitingForMove) {
                        Text(S.changePositionAlert, color = Color.Yellow, style = MaterialTheme.typography.titleMedium)
                    } else if (isStable) {
                        Text(S.holdStillRecording, color = Color.Green, style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(S.keepDeviceSteady, color = Color.Red, style = MaterialTheme.typography.titleMedium)
                    }

                    // Debug Raw
                    Text(
                        text = "x:%.2f y:%.2f z:%.2f".format(currentAccel.x, currentAccel.y, currentAccel.z),
                        color = Color.DarkGray,
                        style = MaterialTheme.typography.bodySmall
                    )

                } else {
                    // DONE
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = S.done,
                        tint = Color.Green,
                        modifier = Modifier.size(64.dp)
                    )

                    Text(
                        text = S.calibrationComplete,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )

                    Button(
                        onClick = {
                            val params = calibrator.solveCalibration()
                            calibrator.saveParams(params)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                    ) {
                        Text(S.saveConfiguration, color = Color.Black)
                    }
                }
            }
        }
    }
}
