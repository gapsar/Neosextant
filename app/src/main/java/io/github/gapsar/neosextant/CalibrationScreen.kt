package io.github.gapsar.neosextant

import android.content.Context
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
    stopPitchAveraging: (MainActivity.ExposureWindow?) -> SensorCalibrator.Vec3?,
    snapshotRawGravity: (MainActivity.ExposureWindow?) -> MainActivity.RawGravitySnapshot? = { null },
    onSaveOneshotCalibration: (Double, Double) -> Unit,
    currentOneshotPitchOffset: Double,
    currentOneshotRollOffset: Double,
    session: NavigationViewModel.StarCalibrationSession,
    iso: Int = 3200,
    exposureTimeMs: Int = 250,
    supportsManualExposure: Boolean = true,
    // Feed the same refraction model the solver uses: the calibration must
    // predict what the sensor actually reads, refraction included, or the
    // fitted offset absorbs R(alt) and biases every later fix by it.
    temperature: String = "15",
    pressure: String = "1013.25"
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
    // Held by the ViewModel so a long affine session survives leaving the screen.
    var latDeg by session.latDeg
    var latMin by session.latMin
    var latDir by session.latDir
    var lonDeg by session.lonDeg
    var lonMin by session.lonMin
    var lonDir by session.lonDir
    var statusMessage by remember { mutableStateOf("") }
    var isCalibrating by remember { mutableStateOf(false) }

    // Multi-sample 1-Shot calibration state
    val REQUIRED_ONESHOT_SAMPLES = 3
    val oneshotPitchOffsets = session.oneshotPitchOffsets
    val oneshotRollOffsets = session.oneshotRollOffsets
    var multiAttitude by session.multiAttitude

    // ─── Affine 1-Shot calibration state ───
    // Accumulates raw (measured, expected) gravity pairs across DIFFERENT
    // attitudes so a full 3×3 A + bias b can be fit. Unlike the averaged-offset
    // path above, this deliberately WANTS attitude spread, so it is not subject
    // to the deviate-from-mean rejection. Each JSON entry is
    // {meas:[x,y,z], mag:Float, exp:[x,y,z]}, the mean over one burst.
    //
    // 24 pairs is not arbitrary: A and b are 12 parameters, and simulation over
    // ~1400 sessions showed a 12-parameter fit reproduces 6 points almost
    // exactly no matter how wrong it is elsewhere. Attitude GROUPING is left to
    // Python, which clusters the measured directions — what the user believes
    // are distinct attitudes very often is not.
    val MIN_AFFINE_SAMPLES = 24

    // Each press takes a short burst and averages it into one sample. Single
    // shots on a tripod scattered by up to ~1 deg in the star-solved attitude,
    // which alone put the fit's held-out error over its 0.20 deg gate.
    val CAL_BURST_COUNT = 5
    val CAL_BURST_MIN_GOOD = 3
    // Measured gravity moving more than this within one burst means the phone
    // moved, and averaging would blend two attitudes.
    val CAL_BURST_MAX_MOTION_DEG = 1.0

    // False plate-solve match detector, shared by the legacy accumulator and
    // the affine pair collector. It is the angle between measured and expected
    // gravity, NOT the pitch/roll decomposition: in landscape the same 0.4 deg
    // splits into ~6 deg of pitch and ~7 deg of roll, which a per-axis bound
    // rejected, so the attitudes the affine fit needs most never reached it.
    // Real misalignments measured on a Pixel 8a were 0.4-0.7 deg.
    val MAX_MISALIGNMENT_DEG = 5.0
    // The legacy path stores pitch/roll, so it still needs them to be small:
    // they are only a usable correction near the attitude they were fitted at.
    val MAX_OFFSET_DEG = 5.0
    // A burst whose gravity is this far from the session's first one is a new
    // attitude (same tolerance as the affine fit's attitude clustering).
    val NEW_ATTITUDE_DEG = 15.0
    val affinePairs = session.affinePairs
    var affineStatus by session.affineStatus

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
                            color = if (statusMessage.contains(S.calibrationSuccess) ||
                                statusMessage.startsWith("Burst accepted")) Color.Green else Color.Red,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Text(
                        text = "Current 1-Shot offsets: Pitch: %.4f° | Roll: %.4f°".format(displayedOneshotPitch, displayedOneshotRoll),
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )

                    // ─── Affine calibration status ───
                    if (sensorCalibrator.hasValidAffine()) {
                        Text(
                            text = "Affine calibration ACTIVE (used for 1-Shot solves)",
                            color = Color(0xFF66BB6A),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (affineStatus.isNotEmpty()) {
                        Text(
                            text = affineStatus,
                            color = Color.Yellow,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (sensorCalibrator.hasValidAffine()) {
                        TextButton(onClick = {
                            sensorCalibrator.resetAffineCalibration()
                            session.reset()
                            affineStatus = "Affine calibration cleared — reverted to pitch/roll offset path."
                        }) {
                            Text("Reset affine calibration", color = Color.LightGray)
                        }
                    }

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

                                val latVal = latD + (latM / 60.0)
                                val latSigned = if (latDir == "S") -latVal else latVal
                                val lonVal = lonD + (lonM / 60.0)
                                val lonSigned = if (lonDir == "W") -lonVal else lonVal
                                val pressureHpa = pressure.toDoubleOrNull() ?: 1013.25
                                val temperatureC = temperature.toDoubleOrNull() ?: 15.0
                                val burstId = System.currentTimeMillis()
                                // Snapshot on the main thread: the fit runs on IO with this
                                // burst's pair appended.
                                val existingPairs = affinePairs.toList()

                                scope.launch {
                                    try {
                                        // ── 1. Capture the burst. Each shot gets its own gravity
                                        // window and time, anchored to the camera's exposure start
                                        // (takePhotoSuspend), the same as the 1-Shot burst.
                                        val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                                        utcFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                        val captures = mutableListOf<CalCapture>()
                                        for (i in 0 until CAL_BURST_COUNT) {
                                            statusMessage = "Capturing ${i + 1}/$CAL_BURST_COUNT…"
                                            startPitchAveraging()
                                            val capture = try {
                                                takePhotoSuspend(context, imageCapture)
                                            } catch (e: ImageCaptureException) {
                                                stopPitchAveraging(null)
                                                throw e
                                            }
                                            val exposureWindow = capture.shutterElapsedNs?.let { start ->
                                                val durNs = if (supportsManualExposure)
                                                    exposureTimeMs * 1_000_000L else 0L
                                                MainActivity.ExposureWindow(start, start + durNs)
                                            }
                                            val captureTime = exposureWindow?.let {
                                                TimeSynchronizer.getTrueTimeAtElapsedNanos(it.midpointNs)
                                            } ?: TimeSynchronizer.getTrueTime()
                                            val gravity = stopPitchAveraging(exposureWindow)
                                            // Raw (non-sphere-fit) gravity for the SAME window, used
                                            // only by the affine fit.
                                            val rawSnap = snapshotRawGravity(exposureWindow)
                                            if (gravity == null) {
                                                File(capture.path).delete()
                                                continue
                                            }
                                            captures.add(CalCapture(capture.path,
                                                utcFormat.format(captureTime), gravity, rawSnap))
                                        }

                                        // ── 2. Solve every shot, then average the good ones into
                                        // one sample (and fit, once there are enough).
                                        statusMessage = "Solving ${captures.size} shots…"
                                        val burst = withContext(Dispatchers.IO) {
                                            val solved = solveCalibrationShots(
                                                context, captures, latSigned, lonSigned,
                                                pressureHpa, temperatureC, MAX_MISALIGNMENT_DEG,
                                                session.sessionId, burstId)
                                            val result = combineCalibrationBurst(solved)
                                            var fitJson: String? = null
                                            val pair = result?.pairJson
                                            if (result != null && pair != null &&
                                                solved.size >= CAL_BURST_MIN_GOOD &&
                                                result.motionDeg <= CAL_BURST_MAX_MOTION_DEG &&
                                                existingPairs.size + 1 >= MIN_AFFINE_SAMPLES) {
                                                val allPairs = org.json.JSONArray()
                                                existingPairs.forEach { allPairs.put(org.json.JSONObject(it)) }
                                                allPairs.put(pair)
                                                fitJson = com.chaquo.python.Python.getInstance()
                                                    .getModule("celestial_navigator")
                                                    .callAttr("fit_affine_calibration", allPairs.toString())
                                                    .toString()
                                            }
                                            Triple(solved, result, fitJson)
                                        }
                                        val (solved, result, fitJson) = burst

                                        // ── 3. Apply, on the main thread.
                                        if (result == null || solved.size < CAL_BURST_MIN_GOOD) {
                                            statusMessage = "$calibrationFailedStr: only ${solved.size} of " +
                                                "$CAL_BURST_COUNT shots were usable (star match and gravity " +
                                                "check). Retake."
                                            return@launch
                                        }
                                        if (result.motionDeg > CAL_BURST_MAX_MOTION_DEG) {
                                            statusMessage = "$calibrationFailedStr: the phone moved during the " +
                                                "burst (%.1f°). Keep it still and retake.".format(result.motionDeg)
                                            return@launch
                                        }

                                        // A clearly different attitude turns this into an affine-only
                                        // session (see StarCalibrationSession.multiAttitude).
                                        val first = session.firstGravity
                                        if (first == null) {
                                            session.firstGravity = result.gravity
                                        } else if (!multiAttitude &&
                                            angleDeg(first, result.gravity) > NEW_ATTITUDE_DEG) {
                                            multiAttitude = true
                                            oneshotPitchOffsets.clear()
                                            oneshotRollOffsets.clear()
                                        }

                                        // ── Affine collection (independent of the legacy filters
                                        // below, so attitude spread is preserved). ──
                                        val pairJson = result.pairJson
                                        if (pairJson != null) {
                                            affinePairs.add(pairJson.toString())
                                            if (fitJson != null) {
                                                try {
                                                    val fit = org.json.JSONObject(fitJson)
                                                    val fitErr = fit.optString("error")
                                                        .takeIf { it.isNotEmpty() && it != "null" }
                                                    if (fitErr == null && fit.optBoolean("healthy", false)) {
                                                        val aArr = fit.getJSONArray("A")
                                                        val bArr = fit.getJSONArray("b")
                                                        val a = FloatArray(9) { i -> aArr.getDouble(i).toFloat() }
                                                        val b = SensorCalibrator.Vec3(
                                                            bArr.getDouble(0).toFloat(),
                                                            bArr.getDouble(1).toFloat(),
                                                            bArr.getDouble(2).toFloat()
                                                        )
                                                        sensorCalibrator.saveAffineParams(
                                                            SensorCalibrator.AffineParams(a, b, true)
                                                        )
                                                        // Held-out (leave-one-attitude-out) error is the only
                                                        // number here that means anything; in-sample is ~0.05°
                                                        // even for a fit that is degrees wrong, so it is not shown.
                                                        affineStatus = ("Affine cal ✓ — held-out %.2f° (≈%.0f nmi), " +
                                                            "%d samples / %d attitudes, spread %.0f°").format(
                                                            fit.optDouble("loco_rms_deg", Double.NaN),
                                                            fit.optDouble("loco_rms_deg", 0.0) * 60.0,
                                                            fit.optInt("n_pairs", affinePairs.size),
                                                            fit.optInt("n_clusters", 0),
                                                            fit.optDouble("attitude_spread_deg", 0.0)
                                                        )
                                                    } else {
                                                        val reasons = fit.optJSONArray("reject_reasons")
                                                        val why = if (reasons != null && reasons.length() > 0) {
                                                            (0 until reasons.length())
                                                                .joinToString("; ") { reasons.optString(it) }
                                                        } else "not yet reliable"
                                                        affineStatus = ("Affine not adopted: %s. %d samples / %d attitudes, " +
                                                            "spread %.0f° — tilt the phone to clearly different " +
                                                            "angles between bursts.").format(
                                                            why,
                                                            fit.optInt("n_pairs", affinePairs.size),
                                                            fit.optInt("n_clusters", 0),
                                                            fit.optDouble("attitude_spread_deg", 0.0)
                                                        )
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w("CalibrationScreen", "Affine fit parse failed (non-fatal)", e)
                                                }
                                            } else {
                                                affineStatus = "Affine: ${affinePairs.size}/$MIN_AFFINE_SAMPLES samples — " +
                                                    "aim for ~8 clearly different phone tilts (portrait and " +
                                                    "landscape), 3 bursts each"
                                            }
                                        }

                                        if (multiAttitude) {
                                            statusMessage = "Burst accepted (%d/%d shots, %.2f° off). This session covers " +
                                                "several attitudes, so it only feeds the affine fit; the 1-Shot " +
                                                "offsets are kept.".format(solved.size, CAL_BURST_COUNT,
                                                    result.misalignmentDeg)
                                            return@launch
                                        }

                                        val pitchOffset = result.pitchOffsetDeg
                                        val rollOffset = result.rollOffsetDeg

                                        // Pitch/roll are only a usable correction when both are small;
                                        // in landscape they blow up even for a sub-degree misalignment.
                                        if (abs(pitchOffset) > MAX_OFFSET_DEG || abs(rollOffset) > MAX_OFFSET_DEG) {
                                            statusMessage = "Sample not usable for the 1-Shot offsets at this attitude " +
                                                "(P: %.2f° R: %.2f°, max ±%.0f°); hold the phone upright.".format(
                                                    pitchOffset, rollOffset, MAX_OFFSET_DEG)
                                            Log.w("CalibrationScreen", "Legacy sample skipped: pitch=$pitchOffset, roll=$rollOffset")
                                            return@launch
                                        }

                                        // Reject if deviation from running mean > 5° (likely false match)
                                        if (oneshotPitchOffsets.isNotEmpty()) {
                                            val runningPitchMean = oneshotPitchOffsets.average()
                                            val runningRollMean = oneshotRollOffsets.average()
                                            if (abs(pitchOffset - runningPitchMean) > MAX_OFFSET_DEG || abs(rollOffset - runningRollMean) > MAX_OFFSET_DEG) {
                                                statusMessage = "Sample rejected: deviates too much from previous samples. Retake."
                                                Log.w("CalibrationScreen", "Rejected 1-Shot sample: deviation from mean too large")
                                                return@launch
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
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.e("CalibrationScreen", "Calibration failed", e)
                                        statusMessage = "$calibrationFailedStr: ${e.message}"
                                    } finally {
                                        isCalibrating = false
                                    }
                                }
                            },
                            enabled = !isCalibrating,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            if (isCalibrating) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                val label = if (multiAttitude) {
                                    "${S.calibrate} (${affinePairs.size}/$MIN_AFFINE_SAMPLES)"
                                } else if (oneshotPitchOffsets.isEmpty()) {
                                    S.calibrate
                                } else {
                                    "${S.calibrate} (${oneshotPitchOffsets.size + 1}/$REQUIRED_ONESHOT_SAMPLES)"
                                }
                                Text(label, maxLines = 1)
                            }
                        }
                    }

                    // Reset button — visible when the session holds anything
                    if (oneshotPitchOffsets.isNotEmpty() || affinePairs.isNotEmpty() ||
                        session.firstGravity != null) {
                        TextButton(
                            onClick = {
                                session.reset()
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

/** One captured shot of a calibration burst, before plate solving. */
private class CalCapture(
    val path: String,
    val captureTimeIso: String,
    /** Calibrated (sphere-fit) gravity over the exposure. */
    val gravity: SensorCalibrator.Vec3,
    /** Raw gravity over the same window; null if the snapshot failed. */
    val raw: MainActivity.RawGravitySnapshot?
)

/** One shot that matched the stars and passed the misalignment gate. */
private class CalSolved(
    val capture: CalCapture,
    val pitchOffsetDeg: Double,
    val rollOffsetDeg: Double,
    val misalignmentDeg: Double,
    /** Expected gravity direction in the sensor frame (unit). */
    val exp: DoubleArray
)

/** A burst reduced to one calibration sample. */
private class CalBurstResult(
    val pitchOffsetDeg: Double,
    val rollOffsetDeg: Double,
    val misalignmentDeg: Double,
    /** Mean calibrated gravity direction (unit). */
    val gravity: SensorCalibrator.Vec3,
    /** Largest angle between a shot's measured gravity and the burst mean. */
    val motionDeg: Double,
    /** Mean affine pair {meas, mag, exp}; null if a raw snapshot was missing. */
    val pairJson: org.json.JSONObject?
)

/** Every calibration shot, one JSON object per line, so a session can be refitted offline. */
private const val CALIBRATION_LOG_FILE = "calibration_shots.jsonl"

private fun appendCalibrationLog(context: Context, line: org.json.JSONObject) {
    try {
        File(context.filesDir, CALIBRATION_LOG_FILE).appendText(line.toString() + "\n")
    } catch (e: Exception) {
        Log.w("CalibrationScreen", "Could not write the calibration log", e)
    }
}

private fun vecJson(x: Double, y: Double, z: Double) = org.json.JSONArray(listOf(x, y, z))

/**
 * Plate-solves each shot, computes its pitch/roll offsets, misalignment and
 * expected gravity, and deletes the photo. Logs every shot, kept or not.
 * Returns the shots that matched and whose measured gravity is within
 * [maxMisalignmentDeg] of the expected one. Runs Python, so call it off the
 * main thread.
 */
private fun solveCalibrationShots(
    context: Context,
    captures: List<CalCapture>,
    latDeg: Double,
    lonDeg: Double,
    pressureHpa: Double,
    temperatureC: Double,
    maxMisalignmentDeg: Double,
    sessionId: Long,
    burstId: Long
): List<CalSolved> {
    val py = com.chaquo.python.Python.getInstance().getModule("celestial_navigator")
    val solved = mutableListOf<CalSolved>()
    for (c in captures) {
        val log = org.json.JSONObject()
            .put("session", sessionId).put("burst", burstId)
            .put("time", c.captureTimeIso).put("lat", latDeg).put("lon", lonDeg)
            .put("pressure_hpa", pressureHpa).put("temperature_c", temperatureC)
            .put("cal_g", vecJson(c.gravity.x.toDouble(), c.gravity.y.toDouble(), c.gravity.z.toDouble()))
        c.raw?.let {
            log.put("raw_dir", vecJson(it.direction.x.toDouble(), it.direction.y.toDouble(), it.direction.z.toDouble()))
                .put("raw_mag", it.magnitude)
        }
        try {
            val image = org.json.JSONObject(
                py.callAttr("image_processor", File(c.path).name, c.path).toString())
            if (image.optInt("solved") != 1) {
                log.put("solved", false)
                continue
            }
            val ra = image.optDouble("ra_deg")
            val dec = image.optDouble("dec_deg")
            val roll = image.optDouble("roll_deg")
            log.put("solved", true).put("ra", ra).put("dec", dec).put("roll", roll)

            val cal = org.json.JSONObject(py.callAttr(
                "solve_calibration_offsets", ra, dec, roll,
                c.gravity.x, c.gravity.y, c.gravity.z,
                latDeg, lonDeg, c.captureTimeIso, pressureHpa, temperatureC).toString())
            val err = cal.optString("error").takeIf { it.isNotEmpty() && it != "null" }
            if (err != null) {
                log.put("error", err)
                continue
            }
            val misalignment = cal.optDouble("misalignment_deg", Double.NaN)
            log.put("pitch_offset", cal.optDouble("pitch_offset_deg"))
                .put("roll_offset", cal.optDouble("roll_offset_deg"))
                .put("misalignment_deg", misalignment)

            val pair = org.json.JSONObject(py.callAttr(
                "solve_calibration_pair", ra, dec, roll,
                latDeg, lonDeg, c.captureTimeIso, pressureHpa, temperatureC).toString())
            val pairErr = pair.optString("error").takeIf { it.isNotEmpty() && it != "null" }
            if (pairErr != null) {
                log.put("error", pairErr)
                continue
            }
            val expArr = pair.getJSONArray("exp")
            log.put("exp", expArr)

            // A false plate-solve match gives an essentially random expected
            // direction, far from what the accelerometer reads.
            val kept = !misalignment.isNaN() && misalignment <= maxMisalignmentDeg
            log.put("kept", kept)
            if (kept) {
                solved.add(CalSolved(
                    c,
                    cal.getDouble("pitch_offset_deg"),
                    cal.getDouble("roll_offset_deg"),
                    misalignment,
                    DoubleArray(3) { expArr.getDouble(it) }
                ))
            }
        } catch (e: Exception) {
            Log.w("CalibrationScreen", "Calibration shot failed: ${c.path}", e)
            log.put("error", e.message ?: e.toString())
        } finally {
            try { File(c.path).delete() } catch (e: Exception) {}
            appendCalibrationLog(context, log)
            Log.d("CalibrationScreen", "Calibration shot: $log")
        }
    }
    return solved
}

private fun unitMean(vectors: List<DoubleArray>): DoubleArray {
    val m = DoubleArray(3)
    for (v in vectors) for (i in 0..2) m[i] += v[i]
    val n = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
    return DoubleArray(3) { m[it] / n }
}

private fun unit(v: SensorCalibrator.Vec3): DoubleArray {
    val n = sqrt((v.x * v.x + v.y * v.y + v.z * v.z).toDouble())
    return doubleArrayOf(v.x / n, v.y / n, v.z / n)
}

private fun angleDeg(a: DoubleArray, b: DoubleArray): Double =
    Math.toDegrees(Math.acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))

private fun angleDeg(a: SensorCalibrator.Vec3, b: SensorCalibrator.Vec3): Double =
    angleDeg(unit(a), unit(b))

private fun median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/**
 * Reduces a burst to one sample: mean directions for the affine pair (the
 * phone is still, so every shot sees the same two vectors plus noise), and the
 * median pitch/roll for the legacy path. Null if no shot survived.
 */
private fun combineCalibrationBurst(solved: List<CalSolved>): CalBurstResult? {
    if (solved.isEmpty()) return null
    val gravities = solved.map { unit(it.capture.gravity) }
    val gMean = unitMean(gravities)
    val motion = gravities.maxOf { angleDeg(it, gMean) }

    val raws = solved.mapNotNull { it.capture.raw }
    val pairJson = if (raws.size == solved.size) {
        val meas = unitMean(raws.map {
            doubleArrayOf(it.direction.x.toDouble(), it.direction.y.toDouble(), it.direction.z.toDouble())
        })
        val exp = unitMean(solved.map { it.exp })
        org.json.JSONObject()
            .put("meas", vecJson(meas[0], meas[1], meas[2]))
            .put("mag", raws.map { it.magnitude }.average())
            .put("exp", vecJson(exp[0], exp[1], exp[2]))
    } else null

    return CalBurstResult(
        pitchOffsetDeg = median(solved.map { it.pitchOffsetDeg }),
        rollOffsetDeg = median(solved.map { it.rollOffsetDeg }),
        misalignmentDeg = median(solved.map { it.misalignmentDeg }),
        gravity = SensorCalibrator.Vec3(gMean[0].toFloat(), gMean[1].toFloat(), gMean[2].toFloat()),
        motionDeg = motion,
        pairJson = pairJson
    )
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
