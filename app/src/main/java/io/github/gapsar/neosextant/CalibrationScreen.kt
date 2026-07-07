package io.github.gapsar.neosextant

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.gapsar.neosextant.model.SolverMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCamera2Interop::class)
@Composable
fun CalibrationScreen(
    onNavigateBack: () -> Unit,
    getRawPitch: () -> Double?,
    getRawRoll: () -> Double?,
    onSaveCalibration: (Double, Double) -> Unit,
    currentPitchOffset: Double,
    currentRollOffset: Double,
    sensorCalibrator: SensorCalibrator,
    sensorPipeline: SensorPipeline,
    rawAccelState: State<SensorCalibrator.Vec3>,
    solverMode: SolverMode,
    startPitchAveraging: () -> Unit,
    stopPitchAveraging: () -> SensorCalibrator.Vec3?,
    onSaveOneshotCalibration: (Double, Double) -> Unit,
    currentOneshotPitchOffset: Double,
    currentOneshotRollOffset: Double,
    iso: Int = 3200,
    exposureTimeMs: Int = 250,
    supportsManualExposure: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember(iso, exposureTimeMs, supportsManualExposure) {
        buildAstroImageCapture(iso, exposureTimeMs, supportsManualExposure)
    }
    val scope = rememberCoroutineScope()
    val calibratingProgressStr = S.calibratingProgress
    val calibrationSuccessStr = S.calibrationSuccess
    val calibrationFailedStr = S.calibrationFailed

    // ─── Horizon Calibration State ───
    var heightOfEye by remember { mutableStateOf("2.0") } // Default 2 meters
    var currentPitch by remember { mutableStateOf(0.0) }
    var currentRoll by remember { mutableStateOf(0.0) }
    val REQUIRED_SAMPLES = 3
    val calibrationPitchOffsets = remember { mutableStateListOf<Double>() }
    val calibrationRollOffsets = remember { mutableStateListOf<Double>() }

    // ─── Star Calibration State ───
    var latDeg by remember { mutableStateOf("") }
    var latMin by remember { mutableStateOf("") }
    var latDir by remember { mutableStateOf("N") }
    var lonDeg by remember { mutableStateOf("") }
    var lonMin by remember { mutableStateOf("") }
    var lonDir by remember { mutableStateOf("E") }
    var statusMessage by remember { mutableStateOf("") }
    var isCalibrating by remember { mutableStateOf(false) }

    // Multi-sample 1-Shot calibration state
    val REQUIRED_ONESHOT_SAMPLES = 3
    val oneshotPitchOffsets = remember { mutableStateListOf<Double>() }
    val oneshotRollOffsets = remember { mutableStateListOf<Double>() }

    // Local state for displayed offsets — updates immediately on calibration success
    var displayedOneshotPitch by remember { mutableStateOf(currentOneshotPitchOffset) }
    var displayedOneshotRoll by remember { mutableStateOf(currentOneshotRollOffset) }

    var showAdvancedDialog by remember { mutableStateOf(false) }

    // Update pitch and roll readings periodically
    LaunchedEffect(Unit) {
        while (true) {
            val rawAlt = getRawPitch() ?: 0.0
            val rawRll = getRawRoll() ?: 0.0

            currentPitch = rawAlt
            currentRoll = rawRll
            delay(100)
        }
    }

    // Camera Setup (C-02: Non-blocking init)
    LaunchedEffect(cameraProviderFuture) {
        val cameraProvider = withContext(Dispatchers.IO) {
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
                preview,
                imageCapture
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
            val title = if (solverMode == SolverMode.ONE_SHOT) S.starCalibrationTitle else S.horizonCalibration
            TopAppBar(
                title = { Text(title) },
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

            // 2. Crosshair Overlay (only for horizon calibration, not 1-Shot)
            if (solverMode != SolverMode.ONE_SHOT) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val centerY = height / 2f

                    // Horizontal line (Horizon/Center target)
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
            }

            // 3. Controls Overlay (Conditional based on SolverMode)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(bottom = paddingValues.calculateBottomPadding())
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (solverMode == SolverMode.ONE_SHOT) {
                    // ─── STAR CALIBRATION UI ───
                    Text(
                        text = S.starCalibrationDesc,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    // Latitude Degree, Minutes, N/S
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(S.latitudeLabelCal + ":", color = Color.White, modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = latDeg,
                            onValueChange = { latDeg = it },
                            label = { Text(S.degLabel, color = Color.LightGray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.LightGray
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = latMin,
                            onValueChange = { latMin = it },
                            label = { Text(S.minLabel, color = Color.LightGray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.LightGray
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { latDir = if (latDir == "N") "S" else "N" },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text(latDir, color = Color.White)
                        }
                    }

                    // Longitude Degree, Minutes, E/W
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(S.longitudeLabelCal + ":", color = Color.White, modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = lonDeg,
                            onValueChange = { lonDeg = it },
                            label = { Text(S.degLabel, color = Color.LightGray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.LightGray
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = lonMin,
                            onValueChange = { lonMin = it },
                            label = { Text(S.minLabel, color = Color.LightGray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.LightGray
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { lonDir = if (lonDir == "E") "W" else "E" },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text(lonDir, color = Color.White)
                        }
                    }

                    if (statusMessage.isNotEmpty()) {
                        Text(
                            text = statusMessage,
                            color = if (statusMessage.contains(S.calibrationSuccess)) Color.Green else Color.Red,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Text(
                        text = "Current 1-Shot offsets: Pitch: %.4f° | Roll: %.4f°".format(displayedOneshotPitch, displayedOneshotRoll),
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Multi-sample calibration progress
                    if (oneshotPitchOffsets.isNotEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Calibration ${oneshotPitchOffsets.size}/$REQUIRED_ONESHOT_SAMPLES",
                                color = Color.Green,
                                style = MaterialTheme.typography.titleSmall
                            )
                            oneshotPitchOffsets.forEachIndexed { i, offset ->
                                Text(
                                    text = "#${i + 1}: P: %.4f° | R: %.4f°".format(offset, oneshotRollOffsets[i]),
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (oneshotPitchOffsets.size >= 2) {
                                val runningPitchAvg = oneshotPitchOffsets.average()
                                val runningRollAvg = oneshotRollOffsets.average()
                                Text(
                                    text = "Running avg: P: %.4f° | R: %.4f°".format(runningPitchAvg, runningRollAvg),
                                    color = Color.Yellow,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showAdvancedDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier.weight(1f).tutorialTarget(6)
                        ) {
                            Text(S.calibrateSensorsUpper, maxLines = 1)
                        }

                        Button(
                            onClick = {
                                val latD = latDeg.toDoubleOrNull()
                                val latM = latMin.toDoubleOrNull()
                                val lonD = lonDeg.toDoubleOrNull()
                                val lonM = lonMin.toDoubleOrNull()

                                if (latD == null || latM == null || lonD == null || lonM == null ||
                                    latD < 0 || latD > 90 || latM < 0 || latM >= 60 ||
                                    lonD < 0 || lonD > 180 || lonM < 0 || lonM >= 60) {
                                    statusMessage = "Invalid coordinates format"
                                    return@Button
                                }

                                isCalibrating = true
                                statusMessage = calibratingProgressStr

                                // Start sensor pitch/gyro averaging
                                startPitchAveraging()

                                captureCalibrationPhoto(context, imageCapture, onImageCaptured = { uri, path ->
                                    // Timestamp at shutter: the plate solve below can take many seconds,
                                    // and the expected zenith rotates ~15 arcsec per second of delay.
                                    val captureTime = TimeSynchronizer.getTrueTime()
                                    val avgGravity = stopPitchAveraging()
                                    if (avgGravity == null) {
                                        isCalibrating = false
                                        statusMessage = "Error: gravity vector not captured."
                                        return@captureCalibrationPhoto
                                    }

                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val py = com.chaquo.python.Python.getInstance()
                                            val pythonScript = py.getModule("celestial_navigator")
                                            val imageName = File(path).name

                                            val imageResultJsonStr = pythonScript.callAttr("image_processor", imageName, path).toString()
                                            val imageResultJson = org.json.JSONObject(imageResultJsonStr)
                                            val isSolved = imageResultJson.optInt("solved") == 1

                                            // Delete photo file since we don't need to persist calibration photos
                                            try { File(path).delete() } catch (e: Exception) {}

                                            if (!isSolved) {
                                                withContext(Dispatchers.Main) {
                                                    isCalibrating = false
                                                    statusMessage = "$calibrationFailedStr: Stars not recognized."
                                                }
                                                return@launch
                                            }

                                            val ra = imageResultJson.optDouble("ra_deg")
                                            val dec = imageResultJson.optDouble("dec_deg")
                                            val roll = imageResultJson.optDouble("roll_deg")

                                            val latVal = latD + (latM / 60.0)
                                            val latSigned = if (latDir == "S") -latVal else latVal
                                            val lonVal = lonD + (lonM / 60.0)
                                            val lonSigned = if (lonDir == "W") -lonVal else lonVal

                                            // C-03: Format as ISO 8601 UTC string for Python/Astropy
                                            val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                                            utcFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                            val captureTimeIso = utcFormat.format(captureTime)

                                            val calResultJsonStr = pythonScript.callAttr(
                                                "solve_calibration_offsets",
                                                ra, dec, roll,
                                                avgGravity.x, avgGravity.y, avgGravity.z,
                                                latSigned, lonSigned,
                                                captureTimeIso
                                            ).toString()

                                            val calResultJson = org.json.JSONObject(calResultJsonStr)
                                            val err = calResultJson.optString("error").takeIf { it.isNotEmpty() && it != "null" }

                                            withContext(Dispatchers.Main) {
                                                isCalibrating = false
                                                if (err != null) {
                                                    statusMessage = "$calibrationFailedStr: $err"
                                                } else {
                                                    val pitchOffset = calResultJson.getDouble("pitch_offset_deg")
                                                    val rollOffset = calResultJson.getDouble("roll_offset_deg")

                                                    // Reject outliers: absolute offset > 5° on either axis
                                                    val MAX_OFFSET_DEG = 5.0
                                                    if (abs(pitchOffset) > MAX_OFFSET_DEG || abs(rollOffset) > MAX_OFFSET_DEG) {
                                                        statusMessage = "Sample rejected: offset too large (P: %.2f° R: %.2f°, max ±%.0f°). Retake.".format(pitchOffset, rollOffset, MAX_OFFSET_DEG)
                                                        Log.w("CalibrationScreen", "Rejected 1-Shot sample: pitch=$pitchOffset, roll=$rollOffset (exceeds ±$MAX_OFFSET_DEG°)")
                                                        return@withContext
                                                    }

                                                    // Reject if deviation from running mean > 5° (likely false match)
                                                    if (oneshotPitchOffsets.isNotEmpty()) {
                                                        val runningPitchMean = oneshotPitchOffsets.average()
                                                        val runningRollMean = oneshotRollOffsets.average()
                                                        if (abs(pitchOffset - runningPitchMean) > MAX_OFFSET_DEG || abs(rollOffset - runningRollMean) > MAX_OFFSET_DEG) {
                                                            statusMessage = "Sample rejected: deviates too much from previous samples. Retake."
                                                            Log.w("CalibrationScreen", "Rejected 1-Shot sample: deviation from mean too large")
                                                            return@withContext
                                                        }
                                                    }

                                                    // Accumulate this sample
                                                    oneshotPitchOffsets.add(pitchOffset)
                                                    oneshotRollOffsets.add(rollOffset)

                                                    if (oneshotPitchOffsets.size >= REQUIRED_ONESHOT_SAMPLES) {
                                                        // All samples collected — save the averaged offsets
                                                        val avgPitch = oneshotPitchOffsets.average()
                                                        val avgRoll = oneshotRollOffsets.average()
                                                        Log.d("CalibrationScreen", "Multi-sample 1-Shot calibration complete. Pitch avg: $avgPitch, Roll avg: $avgRoll")
                                                        onSaveOneshotCalibration(avgPitch, avgRoll)
                                                        displayedOneshotPitch = avgPitch
                                                        displayedOneshotRoll = avgRoll
                                                        statusMessage = calibrationSuccessStr
                                                        oneshotPitchOffsets.clear()
                                                        oneshotRollOffsets.clear()
                                                    } else {
                                                        statusMessage = "Sample ${oneshotPitchOffsets.size}/$REQUIRED_ONESHOT_SAMPLES captured. Take another photo."
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e("CalibrationScreen", "Calibration failed", e)
                                            withContext(Dispatchers.Main) {
                                                isCalibrating = false
                                                statusMessage = "$calibrationFailedStr: ${e.message}"
                                            }
                                        }
                                    }
                                }, onError = { exc ->
                                    stopPitchAveraging()
                                    isCalibrating = false
                                    statusMessage = "$calibrationFailedStr: ${exc.message}"
                                })
                            },
                            enabled = !isCalibrating,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            if (isCalibrating) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                val label = if (oneshotPitchOffsets.isEmpty()) {
                                    S.calibrate
                                } else {
                                    "${S.calibrate} (${oneshotPitchOffsets.size + 1}/$REQUIRED_ONESHOT_SAMPLES)"
                                }
                                Text(label, maxLines = 1)
                            }
                        }
                    }

                    // Reset button — visible when at least one sample is recorded
                    if (oneshotPitchOffsets.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                oneshotPitchOffsets.clear()
                                oneshotRollOffsets.clear()
                                statusMessage = ""
                            }
                        ) {
                            Text("Reset", color = Color.Red)
                        }
                    }

                } else {
                    // ─── HORIZON CALIBRATION UI ───
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
                        text = "Sensor Roll: %.4f°".format(currentRoll),
                        color = Color.Yellow,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Text(
                        text = "${S.currentOffsetFmt.format(currentPitchOffset)} | Roll Offset: %.4f°".format(currentRollOffset),
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Multi-sample calibration progress
                    if (calibrationPitchOffsets.isNotEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Calibration ${calibrationPitchOffsets.size}/$REQUIRED_SAMPLES",
                                color = Color.Green,
                                style = MaterialTheme.typography.titleSmall
                            )
                            calibrationPitchOffsets.forEachIndexed { i, offset ->
                                Text(
                                    text = "#${i + 1}: P: %.4f° | R: %.4f°".format(offset, calibrationRollOffsets[i]),
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (calibrationPitchOffsets.size >= 2) {
                                val runningPitchAvg = calibrationPitchOffsets.average()
                                val runningRollAvg = calibrationRollOffsets.average()
                                Text(
                                    text = "Running avg: P: %.4f° | R: %.4f°".format(runningPitchAvg, runningRollAvg),
                                    color = Color.Yellow,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showAdvancedDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier.weight(1f).tutorialTarget(6)
                        ) {
                            Text(S.calibrateSensorsUpper, maxLines = 1)
                        }

                        Button(
                            onClick = {
                                val h = heightOfEye.toDoubleOrNull() ?: 0.0
                                val dipArcMin = 1.758 * sqrt(h)
                                val dipDeg = dipArcMin / 60.0

                                // True Pitch = -dipDeg
                                // Measured Pitch = currentPitch
                                val newPitchOffset = currentPitch + dipDeg
                                // True Roll = 0.0
                                val newRollOffset = currentRoll
                                calibrationPitchOffsets.add(newPitchOffset)
                                calibrationRollOffsets.add(newRollOffset)

                                if (calibrationPitchOffsets.size >= REQUIRED_SAMPLES) {
                                    // All samples collected — save the averaged offset
                                    val averagedPitchOffset = calibrationPitchOffsets.average()
                                    val averagedRollOffset = calibrationRollOffsets.average()
                                    Log.d("CalibrationScreen", "Multi-sample calibration complete. Pitch avg: $averagedPitchOffset, Roll avg: $averagedRollOffset")
                                    onSaveCalibration(averagedPitchOffset, averagedRollOffset)
                                    onNavigateBack()
                                }
                            },
                            modifier = Modifier.weight(1f).tutorialTarget(4),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            val label = if (calibrationPitchOffsets.isEmpty()) {
                                S.setHorizon
                            } else {
                                "${S.setHorizon} (${calibrationPitchOffsets.size + 1}/$REQUIRED_SAMPLES)"
                            }
                            Text(label, maxLines = 1)
                        }
                    }

                    // Reset button — visible when at least one sample is recorded
                    if (calibrationPitchOffsets.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                calibrationPitchOffsets.clear()
                                calibrationRollOffsets.clear()
                            }
                        ) {
                            Text("Reset", color = Color.Red)
                        }
                    }
                }
            }
        }
    }
}

private fun captureCalibrationPhoto(
    context: Context,
    imageCapture: ImageCapture,
    onImageCaptured: (Uri, String) -> Unit,
    onError: (ImageCaptureException) -> Unit
) {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis()) + ".jpg"
    val photoFile = File(
        context.getExternalFilesDir(null),
        name
    )

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e("CalibrationScreen", "Photo capture failed: ${exc.message}", exc)
                onError(exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = Uri.fromFile(photoFile)
                onImageCaptured(savedUri, photoFile.absolutePath)
            }
        })
}

@Composable
fun SphereCalibrationDialog(
    onDismiss: () -> Unit,
    calibrator: SensorCalibrator,
    context: Context,
    rawAccelState: State<SensorCalibrator.Vec3>
) {
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
            delay(16) // ~60fps polling from shared state
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
