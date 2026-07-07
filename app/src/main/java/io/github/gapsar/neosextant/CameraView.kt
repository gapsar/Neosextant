package io.github.gapsar.neosextant

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.hardware.camera2.CaptureRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.chaquo.python.Python
import io.github.gapsar.neosextant.model.*
import io.github.gapsar.neosextant.ui.components.ImageMetadataCard
import io.github.gapsar.neosextant.ui.components.ImageSlotView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ensures the Chaquopy temp directory exists before Python calls.
 * Android can clean the cache directory at any time, so we must
 * re-create it before every Python invocation.
 */
fun ensureChaquopyTmpDir(context: Context) {
    val tmpDir = File(context.cacheDir, "chaquopy/tmp")
    if (!tmpDir.exists()) {
        tmpDir.mkdirs()
        Log.d("CameraView", "Re-created chaquopy/tmp directory")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCamera2Interop::class)
@Composable
fun CameraView(
    navController: NavController,
    historyRepository: HistoryRepository,
    latitude: String,
    longitude: String,
    altitude: String,
    temperature: String,
    pressure: String,
    solverMode: SolverMode,
    getCurrentPitch: () -> Double?,
    capturedImages: List<ImageData>,
    forceSheetExpand: Boolean = false,
    onAddImage: (ImageData) -> Unit,
    onUpdateImage: (ImageData) -> Unit,
    onRemoveImage: (ImageData) -> Unit,
    onRemoveAllImages: () -> Unit,
    onImageLongClick: (ImageData) -> Unit,
    navigatedToMap: Boolean,
    onNavigatedToMapChange: (Boolean) -> Unit,
    computedLatitude: Double?,
    onComputedLatitudeChange: (Double?) -> Unit,
    computedLongitude: Double?,
    onComputedLongitudeChange: (Double?) -> Unit,
    computedPrecision: Double?,
    onComputedPrecisionChange: (Double?) -> Unit,
    lastSolvedCount: Int,
    onLastSolvedCountChange: (Int) -> Unit,
    onIndividualFixesChange: (List<Pair<Double, Double>>) -> Unit,
    supportsManualExposure: Boolean,
    startPitchAveraging: () -> Unit,
    stopPitchAveraging: () -> SensorCalibrator.Vec3?,
    markCalibrationUsed: () -> Unit,
    analysisJobs: java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>,
    isRedTintMode: Boolean,
    sensorPipeline: SensorPipeline,
    getCalibrationOffset: () -> Double,
    getRollOffset: () -> Double,
    getOneshotCalibrationOffset: () -> Double,
    getOneshotRollOffset: () -> Double,
    iso: Int,
    exposureTimeMs: Int
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { 
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var isNightModeAvailable by remember { mutableStateOf(false) }

    val imageCapture = remember(iso, exposureTimeMs, supportsManualExposure) {
        buildAstroImageCapture(iso, exposureTimeMs, supportsManualExposure)
    }
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    var isTakingPicture by remember { mutableStateOf(false) }
    var selectedImageInfo by remember(forceSheetExpand) {
        mutableStateOf(if (forceSheetExpand) capturedImages.firstOrNull() else null)
    }
    var extensionsManager by remember { mutableStateOf<ExtensionsManager?>(null) }
    var activeCameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var solverError by remember { mutableStateOf<String?>(null) }
    var isSolving by remember { mutableStateOf(false) }

    // Burst capture
    val BURST_COUNT = 7
    var burstProgress by remember { mutableStateOf("") } // e.g. "3/7"

    // H-13: Use mode-aware readyCount for LaunchedEffect key to avoid race conditions
    // For burst images: ready when all sub-results are processed and at least one solved
    val readyCount by remember(capturedImages, solverMode) {
        derivedStateOf {
            capturedImages.count { img ->
                if (img.burstSubResults.isNotEmpty()) {
                    // Burst mode: all sub-results processed, at least one solved
                    val allProcessed = img.burstSubResults.all { it.tetra3Result.analysisState != AnalysisState.PENDING }
                    val anySolved = img.burstSubResults.any { it.tetra3Result.solved }
                    allProcessed && anySolved
                } else {
                    // Non-burst: original logic
                    if (solverMode == SolverMode.LOP) img.lopData != null
                    else img.tetra3Result.solved
                }
            }
        }
    }

    // Navigate to map when 3 images are captured and fully processed for the current mode
    LaunchedEffect(capturedImages.size, readyCount) {
        // readyImages: images that have been fully processed (burst-aware)
        val readyImages = capturedImages.filter { img ->
            if (img.burstSubResults.isNotEmpty()) {
                val allProcessed = img.burstSubResults.all { it.tetra3Result.analysisState != AnalysisState.PENDING }
                val anySolved = img.burstSubResults.any { it.tetra3Result.solved }
                allProcessed && anySolved
            } else {
                img.tetra3Result.solved
            }
        }
        val isReady = if (solverMode == SolverMode.ITERATIVE) readyCount >= 3 else if (solverMode == SolverMode.ONE_SHOT) readyCount >= 1 else readyCount == 3
        
        if (isReady && readyCount != lastSolvedCount) {
            onLastSolvedCountChange(readyCount)
            isSolving = true

            try {
                // Run heavy Python computation off the main thread
                val result = withContext(Dispatchers.IO) {
                    ensureChaquopyTmpDir(context)
                    val py = Python.getInstance()
                    val pythonScript = py.getModule("celestial_navigator")

                    if (solverMode == SolverMode.ITERATIVE) {
                        // --- ITERATIVE SOLVER (burst-aware) ---
                        // For each slot, median the burst sub-results' ra/dec/alt
                        val (obsList, skippedNoAlt) = buildAltitudeObservations(readyImages)
                        if (skippedNoAlt > 0) {
                            Log.w("Solver", "$skippedNoAlt sight(s) without sensor altitude excluded from iterative solve")
                        }
                        if (obsList.length() < 2) {
                            org.json.JSONObject().apply { put("error", "No sensor altitude recorded for enough sights — retake photos") }
                        } else {
                            // H-06, H-07: Pass height, pressure, temperature to solver
                            val heightM = altitude.toDoubleOrNull() ?: 0.0
                            val pressureHpa = pressure.toDoubleOrNull() ?: 1013.25
                            val temperatureC = temperature.toDoubleOrNull() ?: 15.0

                            val solveResultJsonStr = pythonScript.callAttr(
                                "solve_iterative",
                                obsList.toString(),
                                0.0, // Hardcoded initial latitude for iterative solver
                                0.0, // Hardcoded initial longitude for iterative solver
                                heightM,
                                pressureHpa,
                                temperatureC
                            ).toString()

                            org.json.JSONObject(solveResultJsonStr)
                        }
                    } else if (solverMode == SolverMode.ONE_SHOT) {
                        // --- 1-SHOT BURST MULTI SOLVER ---
                        // Build burst groups: each slot's solved burst sub-results form a group
                        val burstGroups = org.json.JSONArray()
                        var skippedNoGravity = 0
                        readyImages.forEach { img ->
                            val burstGroup = org.json.JSONArray()
                            if (img.burstSubResults.isNotEmpty()) {
                                // measuredHeight == null marks a sub-shot whose gravity capture
                                // failed (Vec3(0,0,0) placeholder) — never send those to the solver
                                val validSubs = img.burstSubResults.filter { it.tetra3Result.solved && it.measuredHeight != null }
                                skippedNoGravity += img.burstSubResults.count { it.tetra3Result.solved && it.measuredHeight == null }
                                validSubs.forEach { sub ->
                                    val obs = org.json.JSONObject()
                                    obs.put("ra", sub.tetra3Result.raDeg ?: 0.0)
                                    obs.put("dec", sub.tetra3Result.decDeg ?: 0.0)
                                    obs.put("roll", sub.tetra3Result.rollDeg ?: 0.0)
                                    obs.put("gx", sub.gravityVector.x)
                                    obs.put("gy", sub.gravityVector.y)
                                    obs.put("gz", sub.gravityVector.z)
                                    obs.put("time_iso", sub.timestamp)
                                    burstGroup.put(obs)
                                }
                            } else {
                                // Non-burst fallback
                                val g = img.gravityVector
                                if (g == null) {
                                    skippedNoGravity++
                                } else {
                                    val obs = org.json.JSONObject()
                                    obs.put("ra", img.tetra3Result.raDeg ?: 0.0)
                                    obs.put("dec", img.tetra3Result.decDeg ?: 0.0)
                                    obs.put("roll", img.tetra3Result.rollDeg ?: 0.0)
                                    obs.put("gx", g.x)
                                    obs.put("gy", g.y)
                                    obs.put("gz", g.z)
                                    obs.put("time_iso", img.timestamp)
                                    burstGroup.put(obs)
                                }
                            }
                            if (burstGroup.length() > 0) {
                                burstGroups.put(burstGroup)
                            }
                        }
                        if (skippedNoGravity > 0) {
                            Log.w("Solver", "$skippedNoGravity 1-Shot sub-shot(s) without gravity vector excluded from solve")
                        }
                        if (burstGroups.length() == 0) {
                            org.json.JSONObject().apply { put("error", "No gravity vector recorded for 1-Shot") }
                        } else {
                            val solveResultJsonStr = pythonScript.callAttr(
                                "solve_oneshot_burst_multi",
                                burstGroups.toString()
                            ).toString()
                            org.json.JSONObject(solveResultJsonStr)
                        }
                    } else {
                        // --- C-04: LOP SOLVER (iterative, burst-aware) ---
                        val (obsList, skippedNoAlt) = buildAltitudeObservations(readyImages)
                        if (skippedNoAlt > 0) {
                            Log.w("Solver", "$skippedNoAlt sight(s) without sensor altitude excluded from LOP solve")
                        }
                        if (obsList.length() < 2) {
                            org.json.JSONObject().apply { put("error", "No sensor altitude recorded for enough sights — retake photos") }
                        } else {
                            val heightM = altitude.toDoubleOrNull() ?: 0.0
                            val pressureHpa = pressure.toDoubleOrNull() ?: 1013.25
                            val temperatureC = temperature.toDoubleOrNull() ?: 15.0

                            val solveResultJsonStr = pythonScript.callAttr(
                                "solve_lop_iterative",
                                obsList.toString(),
                                latitude.toDoubleOrNull() ?: 0.0,
                                longitude.toDoubleOrNull() ?: 0.0,
                                heightM,
                                pressureHpa,
                                temperatureC
                            ).toString()
                            org.json.JSONObject(solveResultJsonStr)
                        }
                    }
                }

                val errorStr = result.optString("error", "")
                if (result.has("error") && errorStr.isNotEmpty() && errorStr != "null") {
                    solverError = errorStr
                    Log.e("Solver", "Python error: $solverError")
                    onNavigatedToMapChange(false)
                    isSolving = false
                    return@LaunchedEffect
                }

                if (!result.has("fixed_latitude") || !result.has("fixed_longitude")) {
                    solverError = "Missing coordinates in solver result"
                    Log.e("Solver", "Python error: $solverError")
                    isSolving = false
                    return@LaunchedEffect
                }

                val finalLatitude = result.getDouble("fixed_latitude")
                val finalLongitude = result.getDouble("fixed_longitude")
                val shiftNm = result.optDouble("final_shift_nm", result.optDouble("error_estimate_nm", Double.NaN))
                Log.d("Solver", "Fix: Lat=$finalLatitude, Lon=$finalLongitude, error=$shiftNm NM")

                // Per-slot fixes for the map's multi-fix markers; absent (→ empty list)
                // for Iterative/LOP results, which also clears fixes from a previous solve
                val parsedIndividualFixes = mutableListOf<Pair<Double, Double>>()
                result.optJSONArray("individual_fixes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val fix = arr.optJSONObject(i) ?: continue
                        val la = fix.optDouble("lat", Double.NaN)
                        val lo = fix.optDouble("lon", Double.NaN)
                        if (!la.isNaN() && !lo.isNaN()) parsedIndividualFixes.add(Pair(la, lo))
                    }
                }
                onIndividualFixesChange(parsedIndividualFixes)

                onComputedLatitudeChange(finalLatitude)
                onComputedLongitudeChange(finalLongitude)
                if (!shiftNm.isNaN()) {
                    onComputedPrecisionChange(shiftNm)
                }
                
                // M-17: Delayed Image Compression & Downscaling — copy to history dir
                val historyDir = java.io.File(context.getExternalFilesDir(null), "history_images")
                historyDir.mkdirs()
                
                val finalImages = readyImages.mapNotNull { img ->
                    val path = img.uri.path ?: return@mapNotNull null
                    try {
                        val originalFile = java.io.File(path)
                        if (originalFile.exists() && originalFile.length() > 0) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                            if (bitmap != null) {
                                val rotationDegrees = try {
                                    val exif = androidx.exifinterface.media.ExifInterface(path)
                                    val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                    when (orientation) {
                                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                        else -> 0f
                                    }
                                } catch (e: Exception) {
                                    // Fallback: if width > height, assume sensor native landscape needing 90° rotation
                                    if (bitmap.width > bitmap.height) 90f else 0f
                                }
                                
                                var finalBitmap = if (rotationDegrees != 0f) {
                                    val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees) }
                                    android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                } else {
                                    bitmap
                                }
                                
                                // Scale down to max 1024
                                val maxDimension = 1024
                                var scaleRatio = 1.0
                                if (finalBitmap.width > maxDimension || finalBitmap.height > maxDimension) {
                                    val ratio = Math.min(maxDimension.toFloat() / finalBitmap.width, maxDimension.toFloat() / finalBitmap.height)
                                    val newWidth = (finalBitmap.width * ratio).toInt()
                                    val newHeight = (finalBitmap.height * ratio).toInt()
                                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(finalBitmap, newWidth, newHeight, true)
                                    if (finalBitmap != bitmap) finalBitmap.recycle()
                                    finalBitmap = scaledBitmap
                                    scaleRatio = ratio.toDouble()
                                }
                                
                                // Save compressed copy to history directory
                                val historyFile = java.io.File(historyDir, img.name)
                                val outputStream = java.io.FileOutputStream(historyFile)
                                finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
                                outputStream.flush()
                                outputStream.close()
                                
                                if (finalBitmap != bitmap) finalBitmap.recycle()
                                bitmap.recycle()
                                Log.d("ImageCompression", "Compressed and saved to history: ${img.name}")
                                
                                var updatedTetra3Result = img.tetra3Result
                                if (scaleRatio != 1.0) {
                                    val scaledCentroids = updatedTetra3Result.centroids.map { Pair(it.first * scaleRatio, it.second * scaleRatio) }
                                    val scaledStars = updatedTetra3Result.matchedStars.map { it.copy(x = it.x * scaleRatio, y = it.y * scaleRatio) }
                                    updatedTetra3Result = updatedTetra3Result.copy(centroids = scaledCentroids, matchedStars = scaledStars)
                                }
                                
                                // Return image with updated URI pointing to history copy
                                return@mapNotNull img.copy(uri = Uri.fromFile(historyFile), tetra3Result = updatedTetra3Result)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CameraView", "Failed to compress image for history", e)
                    }
                    img
                }

                // Save to History Repository
                val modeStr = if (solverMode == SolverMode.ITERATIVE) "ITERATIVE" else if (solverMode == SolverMode.ONE_SHOT) "ONE_SHOT" else "LOP"
                val imagesJsonStr = HistoryRepository.serializeImages(finalImages)
                
                historyRepository.saveEntry(
                    PositionEntry(
                        timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(TimeSynchronizer.getTrueTime()),
                        latitude = finalLatitude,
                        longitude = finalLongitude,
                        errorEstimateNm = if (shiftNm.isNaN()) null else shiftNm,
                        mode = modeStr,
                        estimatedLatitude = latitude.toDoubleOrNull() ?: 0.0,
                        estimatedLongitude = longitude.toDoubleOrNull() ?: 0.0,
                        imagesJson = imagesJsonStr
                    )
                )

                // S-01: Delete original full-resolution photos after history save
                // The compressed copies in history_images/ are kept; originals (~10 MB each) are no longer needed.
                // After deletion, update capturedImages to point to the surviving history copies
                // so the ImageViewer and centroid overlay still work.
                readyImages.forEach { img ->
                    try {
                        val originalPath = img.uri.path
                        if (originalPath != null) {
                            val originalFile = java.io.File(originalPath)
                            // Only delete if it's NOT already in history_images (avoid deleting history copies)
                            if (originalFile.exists() && !originalPath.contains("history_images")) {
                                originalFile.delete()
                                Log.d("StorageCleanup", "Deleted original: ${originalFile.name}")
                            }
                            // Update capturedImages with the history entry: it carries both the
                            // downscaled copy's URI and the coordinates rescaled to match it.
                            val historyFile = java.io.File(historyDir, img.name)
                            if (historyFile.exists()) {
                                val historyImage = finalImages.find { it.name == img.name }
                                onUpdateImage(historyImage ?: img.copy(uri = Uri.fromFile(historyFile)))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("StorageCleanup", "Failed to delete original image", e)
                    }
                }
                
                isSolving = false
                
                if (!navigatedToMap) {
                    onNavigatedToMapChange(true)
                    navController.navigate("map")
                }
            } catch (e: Exception) {
                Log.e("Solver", "Failed to compute position fix", e)
                isSolving = false
            }
        }
    }


    // C-02: Non-blocking camera provider init
    var intrinsicsJson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }
        
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    val jsonObj = org.json.JSONObject()
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val distortion = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_DISTORTION)
                        if (distortion != null) {
                            val dArr = org.json.JSONArray()
                            distortion.forEach { dArr.put(it) }
                            jsonObj.put("distortion", dArr)
                        }
                        val intrinsics = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                        if (intrinsics != null) {
                            val iArr = org.json.JSONArray()
                            intrinsics.forEach { iArr.put(it) }
                            jsonObj.put("intrinsics", iArr)
                        }
                    } else {
                        val radDistortion = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_RADIAL_DISTORTION)
                        if (radDistortion != null) {
                            val dArr = org.json.JSONArray()
                            radDistortion.forEach { dArr.put(it) }
                            jsonObj.put("radial_distortion", dArr)
                        }
                    }
                    
                    val focalLengths = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    if (focalLengths != null && focalLengths.isNotEmpty()) {
                        jsonObj.put("focal_length_mm", focalLengths[0])
                    }
                    
                    val sensorSize = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (sensorSize != null) {
                        jsonObj.put("sensor_width_mm", sensorSize.width)
                        jsonObj.put("sensor_height_mm", sensorSize.height)
                    }
                    
                    intrinsicsJson = jsonObj.toString()
                    Log.d("CameraView", "Intrinsics: $intrinsicsJson")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e("CameraView", "Failed to fetch camera intrinsics", e)
        }
    }

    // H-08: Unbind camera on navigation away
    DisposableEffect(Unit) {
        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    Log.d("CameraView", "Camera unbound on dispose")
                }
            } catch (e: Exception) {
                Log.w("CameraView", "Error unbinding camera on dispose", e)
            }
        }
    }

    LaunchedEffect(cameraProviderFuture, activeCameraSelector) {
        val cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }

        // Configure Preview with high resolution and 16:9 aspect ratio preference
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                activeCameraSelector,
                preview,
                imageCapture
            )
        } catch (exc: Exception) {
            Log.e("CameraView", "Use case binding failed", exc)
        }
    }

    LaunchedEffect(forceSheetExpand) {
        if (forceSheetExpand) {
            scaffoldState.bottomSheetState.expand()
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 60.dp,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        sheetDragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable(onClick = {
                        scope.launch {
                            if (scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded) {
                                scaffoldState.bottomSheetState.partialExpand()
                            } else {
                                scaffoldState.bottomSheetState.expand()
                            }
                        }
                    }),
                contentAlignment = Alignment.Center
            ) { BottomSheetDefaults.DragHandle() }
        },
        sheetContent = {
            // *** FIX 1: Add a minimum height to the content Column ***
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp) // Ensures sheet has a stable minimum size
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                val maxSlots = if (solverMode == SolverMode.ITERATIVE || solverMode == SolverMode.ONE_SHOT) Math.max(3, java.lang.Math.min(6, capturedImages.size + 1)) else 3
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = if (maxSlots == 1) Arrangement.Center else Arrangement.spacedBy(8.dp)
                ) {
                    items(maxSlots) { i ->
                        val imageInfo = capturedImages.getOrNull(i)
                        val isProcessing = if (imageInfo != null && imageInfo.burstSubResults.isNotEmpty()) {
                            imageInfo.burstSubResults.any { it.tetra3Result.analysisState == AnalysisState.PENDING }
                        } else {
                            imageInfo?.tetra3Result?.analysisState == AnalysisState.PENDING
                        }

                        ImageSlotView(
                            modifier = Modifier
                                .fillParentMaxWidth(0.31f)
                                .aspectRatio(1f),
                            imageInfo = imageInfo,
                            isSelected = imageInfo != null && imageInfo.id == selectedImageInfo?.id,
                            isProcessing = isProcessing,
                            onClick = { info ->
                                selectedImageInfo = info
                                scope.launch {
                                    if (scaffoldState.bottomSheetState.currentValue != SheetValue.Expanded) {
                                        scaffoldState.bottomSheetState.expand()
                                    }
                                }
                            },
                            onLongClick = { info -> onImageLongClick(info) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // *** FIX 2: Replace AnimatedVisibility with a simple 'if' block ***
                if (selectedImageInfo != null) {
                    ImageMetadataCard(
                        imageInfo = selectedImageInfo!!,
                        onRemoveClick = {
                            analysisJobs.remove(selectedImageInfo!!.id)?.cancel()
                            try {
                                selectedImageInfo!!.uri.path?.let { java.io.File(it).delete() }
                            } catch (e: Exception) {
                                Log.e("CameraView", "Failed to delete image file", e)
                            }
                            onRemoveImage(selectedImageInfo!!)
                            selectedImageInfo = null
                            if (capturedImages.size < 3) { // Reset navigation flag
                                onNavigatedToMapChange(false)
                                onComputedLatitudeChange(null)
                                onComputedLongitudeChange(null)
                            }
                        }
                    )
                } else {
                    // Show "Remove All" when images exist but none selected; otherwise placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (capturedImages.isNotEmpty()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(S.selectImage, style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        onRemoveAllImages()
                                        onNavigatedToMapChange(false)
                                        onComputedLatitudeChange(null)
                                        onComputedLongitudeChange(null)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(S.removeAllImages)
                                }
                            }
                        } else {
                            Text(S.selectImage, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

            // "Shot in progress" overlay
            if (isTakingPicture || isSolving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (isTakingPicture) S.capturingHoldStill else "Computing position...",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            // Settings & Help Buttons (Top Left)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 32.dp, start = 12.dp)
            ) {
                IconButton(onClick = { navController.navigate("settings") }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = S.settings,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = { navController.navigate("help") }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = S.helpTitle,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Go to Map Button (Top Right)
            val mapButtonVisible = if (solverMode == SolverMode.ONE_SHOT) readyCount >= 1 else readyCount >= 3
            AnimatedVisibility(
                visible = mapButtonVisible && computedLatitude != null && computedLongitude != null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 12.dp)
            ) {
                if (solverMode == SolverMode.ITERATIVE && computedPrecision != null) {
                    ExtendedFloatingActionButton(
                        onClick = { navController.navigate("map") },
                        icon = { Icon(Icons.Default.Map, contentDescription = S.goToMap) },
                        text = { Text("Map (±%.1f NM)".format(computedPrecision)) }
                    )
                } else {
                    IconButton(onClick = { navController.navigate("map") }) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = S.goToMap,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }


            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 52.dp), // Adjust padding to position button above the sheet
                contentAlignment = Alignment.BottomCenter
            ) {
                IconButton(
                    modifier = Modifier.size(80.dp).tutorialTarget(7),
                    onClick = {
                    val maxImages = if (solverMode == SolverMode.ITERATIVE || solverMode == SolverMode.ONE_SHOT) Int.MAX_VALUE else 3
                    if (!isTakingPicture && !isSolving && capturedImages.size < maxImages) {
                        isTakingPicture = true
                        burstProgress = "1/$BURST_COUNT"

                        scope.launch {
                            try {
                                // Collect burst photos with individual gravity snapshots
                                val burstData = mutableListOf<BurstSubResult>()
                                val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                                utcFormat.timeZone = TimeZone.getTimeZone("UTC")

                                for (i in 0 until BURST_COUNT) {
                                    burstProgress = "${i + 1}/$BURST_COUNT"

                                    // Start pitch averaging for this individual shot
                                    startPitchAveraging()

                                    // Take one photo (suspend until captured)
                                    val (uri, path) = takePhotoSuspend(context, imageCapture)

                                    // Get individual gravity snapshot for this burst image
                                    val avgGravityRaw = stopPitchAveraging()
                                    val gravityVector: SensorCalibrator.Vec3
                                    val measuredHeight: Double?

                                    if (avgGravityRaw != null) {
                                        if (solverMode == SolverMode.ONE_SHOT) {
                                            val pitchOffset = getOneshotCalibrationOffset()
                                            val rollOffset = getOneshotRollOffset()
                                            gravityVector = sensorPipeline.applyOffsets(avgGravityRaw, pitchOffset, rollOffset)
                                            measuredHeight = 0.0
                                        } else {
                                            val pitchOffset = getCalibrationOffset()
                                            val rollOffset = getRollOffset()
                                            val offsetGravity = sensorPipeline.applyOffsets(avgGravityRaw, pitchOffset, rollOffset)
                                            gravityVector = offsetGravity

                                            val cameraVector = SensorCalibrator.Vec3(0f, 0f, -1f)
                                            val calDot = offsetGravity.x * cameraVector.x +
                                                         offsetGravity.y * cameraVector.y +
                                                         offsetGravity.z * cameraVector.z
                                            val calThetaDeg = Math.toDegrees(Math.acos(calDot.coerceIn(-1f, 1f).toDouble()))
                                            measuredHeight = 90.0 - calThetaDeg
                                        }
                                    } else {
                                        gravityVector = SensorCalibrator.Vec3(0f, 0f, 0f)
                                        measuredHeight = null
                                    }

                                    val captureTime = TimeSynchronizer.getTrueTime()
                                    val timestamp = utcFormat.format(captureTime)

                                    burstData.add(BurstSubResult(
                                        path = path,
                                        uri = uri,
                                        gravityVector = gravityVector,
                                        timestamp = timestamp,
                                        measuredHeight = measuredHeight
                                    ))

                                    Log.d("CameraView", "Burst capture ${i + 1}/$BURST_COUNT done: $path")
                                }

                                burstProgress = ""

                                // Mark calibration used once for the whole burst
                                markCalibrationUsed()

                                // Create ImageData with the first image as the display thumbnail
                                val firstBurst = burstData.first()
                                val newImageInfo = ImageData(
                                    uri = firstBurst.uri,
                                    name = File(firstBurst.path).name,
                                    timestamp = firstBurst.timestamp,
                                    measuredHeight = firstBurst.measuredHeight,
                                    gravityVector = firstBurst.gravityVector,
                                    burstSubResults = burstData
                                )

                                onAddImage(newImageInfo)
                                selectedImageInfo = newImageInfo
                                scaffoldState.bottomSheetState.expand()

                                // Process all burst sub-images in background
                                val job = launch(Dispatchers.IO) {
                                    try {
                                        ensureChaquopyTmpDir(context)
                                        val py = Python.getInstance()
                                        val pythonScript = py.getModule("celestial_navigator")
                                        val intrinsicsStr = intrinsicsJson ?: "{}"

                                        var currentImage = newImageInfo

                                        for (idx in burstData.indices) {
                                            val sub = burstData[idx]
                                            val subPath = sub.path
                                            val subName = File(subPath).name

                                            try {
                                                val imageResultJsonStr = pythonScript.callAttr("image_processor", subName, subPath, intrinsicsStr).toString()
                                                val imageResultJson = JSONObject(imageResultJsonStr)
                                                val isSolved = imageResultJson.optInt("solved") == 1

                                                val subTetra3Result = if (isSolved) {
                                                    Tetra3AnalysisResult(
                                                        analysisState = AnalysisState.SUCCESS,
                                                        solved = true,
                                                        raDeg = imageResultJson.optDouble("ra_deg"),
                                                        decDeg = imageResultJson.optDouble("dec_deg"),
                                                        rollDeg = imageResultJson.optDouble("roll_deg"),
                                                        fovDeg = imageResultJson.optDouble("fov_deg")
                                                    )
                                                } else {
                                                    val errorMessage = imageResultJson.optString("error_message", "Unknown error")
                                                    Tetra3AnalysisResult(analysisState = AnalysisState.FAILURE, solved = false, errorMessage = errorMessage)
                                                }

                                                // Update the sub-result with the solve result
                                                val updatedSubs = currentImage.burstSubResults.toMutableList()
                                                updatedSubs[idx] = updatedSubs[idx].copy(tetra3Result = subTetra3Result)
                                                currentImage = currentImage.copy(burstSubResults = updatedSubs)

                                                // If this is the first image in the burst, also update the parent's tetra3Result
                                                // for display (centroids, matched stars on first image)
                                                // Parse centroids for the first sub-shot even when the solve failed —
                                                // the detected-star overlay helps diagnose why a solve found no match.
                                                if (idx == 0) {
                                                    // Python exif_transposes the image before centroid detection,
                                                    // so returned coordinates are already in the display frame —
                                                    // no Kotlin-side rotation must be applied.
                                                    val centroidsArray = imageResultJson.optJSONArray("centroids")
                                                    val parsedCentroids = mutableListOf<Pair<Double, Double>>()
                                                    if (centroidsArray != null) {
                                                        for (ci in 0 until centroidsArray.length()) {
                                                            val pt = centroidsArray.optJSONArray(ci)
                                                            if (pt != null && pt.length() >= 2) {
                                                                parsedCentroids.add(Pair(pt.optDouble(0), pt.optDouble(1)))
                                                            }
                                                        }
                                                    }

                                                    val matchedStarsArray = imageResultJson.optJSONArray("matched_stars")
                                                    val parsedMatchedStars = mutableListOf<MatchedStar>()
                                                    if (matchedStarsArray != null) {
                                                        for (si in 0 until matchedStarsArray.length()) {
                                                            val starObj = matchedStarsArray.optJSONObject(si) ?: continue
                                                            val displayY = starObj.optDouble("y", Double.NaN)
                                                            val displayX = starObj.optDouble("x", Double.NaN)
                                                            if (displayY.isNaN() || displayX.isNaN()) continue
                                                            parsedMatchedStars.add(MatchedStar(
                                                                name = starObj.optString("name").takeIf { it != "null" && it.isNotEmpty() },
                                                                constellation = starObj.optString("constellation").takeIf { it != "null" && it.isNotEmpty() },
                                                                hipId = starObj.optInt("hip_id", -1),
                                                                y = displayY,
                                                                x = displayX,
                                                                magnitude = starObj.optDouble("magnitude").takeIf { !it.isNaN() }
                                                            ))
                                                        }
                                                    }

                                                    // Set parent's tetra3Result for display purposes
                                                    val parentTetra3 = subTetra3Result.copy(
                                                        centroids = parsedCentroids,
                                                        matchedStars = parsedMatchedStars
                                                    )
                                                    currentImage = currentImage.copy(tetra3Result = parentTetra3)
                                                }

                                                Log.d("CameraView", "Burst sub ${idx + 1}/${burstData.size}: solved=$isSolved")

                                            } catch (e: Exception) {
                                                Log.e("CameraView", "Burst sub ${idx + 1} failed", e)
                                                val updatedSubs = currentImage.burstSubResults.toMutableList()
                                                updatedSubs[idx] = updatedSubs[idx].copy(
                                                    tetra3Result = Tetra3AnalysisResult(analysisState = AnalysisState.FAILURE, errorMessage = e.message)
                                                )
                                                currentImage = currentImage.copy(burstSubResults = updatedSubs)
                                            }

                                            // Update UI after each sub-result
                                            withContext(Dispatchers.Main) {
                                                onUpdateImage(currentImage)
                                                selectedImageInfo = currentImage
                                            }
                                        }

                                        // --- LOP: compute this slot's line of position from the median observation ---
                                        if (solverMode == SolverMode.LOP) {
                                            val solvedSubs = currentImage.burstSubResults.filter { it.tetra3Result.solved }
                                            val ras = solvedSubs.mapNotNull { it.tetra3Result.raDeg }
                                            val decs = solvedSubs.mapNotNull { it.tetra3Result.decDeg }
                                            val alts = solvedSubs.mapNotNull { it.measuredHeight }
                                            if (ras.isNotEmpty() && decs.isNotEmpty() && alts.isNotEmpty()) {
                                                try {
                                                    val lopResultJsonStr = pythonScript.callAttr(
                                                        "lop_compute",
                                                        ras.sorted()[ras.size / 2],
                                                        decs.sorted()[decs.size / 2],
                                                        latitude.toDoubleOrNull() ?: 0.0,
                                                        longitude.toDoubleOrNull() ?: 0.0,
                                                        0.0, // Height of Eye = 0 to bypass Dip (sensor altitude is already Ho)
                                                        pressure.toDoubleOrNull() ?: 1013.25,
                                                        temperature.toDoubleOrNull() ?: 15.0,
                                                        alts.sorted()[alts.size / 2],
                                                        currentImage.timestamp
                                                    ).toString()
                                                    val lopJson = JSONObject(lopResultJsonStr)
                                                    currentImage = currentImage.copy(lopData = LineOfPositionData(
                                                        interceptNm = lopJson.optDouble("intercept_nm", 0.0),
                                                        azimuthDeg = lopJson.optDouble("azimuth_deg"),
                                                        observedAltitudeDeg = lopJson.optDouble("observed_altitude_deg"),
                                                        computedAltitudeDeg = lopJson.optDouble("computed_altitude_deg", 0.0),
                                                        errorMessage = lopJson.optString("error").takeIf { it.isNotEmpty() && it != "null" }
                                                    ))
                                                    withContext(Dispatchers.Main) {
                                                        onUpdateImage(currentImage)
                                                        selectedImageInfo = currentImage
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e("CameraView", "lop_compute failed for slot", e)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CameraView", "Burst processing failed", e)
                                        val errorResult = Tetra3AnalysisResult(analysisState = AnalysisState.FAILURE, errorMessage = e.message)
                                        val errorImage = newImageInfo.copy(tetra3Result = errorResult)
                                        withContext(Dispatchers.Main) {
                                            onUpdateImage(errorImage)
                                            selectedImageInfo = errorImage
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) {
                                            isTakingPicture = false
                                        }
                                    }
                                }
                                analysisJobs[newImageInfo.id] = job

                            } catch (e: Exception) {
                                Log.e("CameraView", "Burst capture failed", e)
                                burstProgress = ""
                                isTakingPicture = false
                                stopPitchAveraging() // Ensure cleanup
                            }
                        }
                    }
                }) {
                    Icon(
                        imageVector = Icons.Outlined.CameraAlt,
                        contentDescription = S.takePicture,
                        modifier = Modifier.size(60.dp)
                    )
                }
            }
        }
    }

    // Display Iterative Solver Error Dialog
    if (solverError != null) {
        val dialogModifier = if (isRedTintMode) {
            Modifier.graphicsLayer {
                colorFilter = ColorFilter.colorMatrix(RedTintMatrix)
            }
        } else {
            Modifier
        }
        androidx.compose.material3.AlertDialog(
            modifier = dialogModifier,
            onDismissRequest = { solverError = null },
            title = { androidx.compose.material3.Text(S.navigationFailed) },
            text = { androidx.compose.material3.Text(solverError!!) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { solverError = null }) {
                    androidx.compose.material3.Text(S.dismiss)
                }
            }
        )
    }
}

/**
 * Astro capture configuration shared by CameraView and CalibrationScreen —
 * star-calibration photos must use the exact same capture settings as
 * navigation photos for the calibration to be valid.
 * H-09: manual-exposure keys are only applied when the device supports them
 * (MANUAL_SENSOR capability); otherwise auto-exposure is left on so LEGACY/
 * LIMITED hardware still produces usable captures.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun buildAstroImageCapture(iso: Int, exposureTimeMs: Int, supportsManualExposure: Boolean): ImageCapture {
    val builder = ImageCapture.Builder()
    val extender = Camera2Interop.Extender(builder)

    if (supportsManualExposure) {
        // Manual Exposure
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeMs * 1_000_000L)
    } else {
        Log.w("CameraView", "Manual exposure not supported on this device — using auto exposure")
    }

    // Lock focus to infinity for star photography
    extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
    extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)

    // Disable auto-processing
    extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
    extender.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
    extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
    extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
    extender.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
    extender.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)

    return builder.build()
}

/**
 * Builds the observation array for the Iterative/LOP solvers.
 * Slots without any measured sensor altitude are excluded — a missing sensor
 * reading must never be sent to the solver as a real 0° sight.
 * Returns the observations plus the number of slots that were skipped.
 */
private fun buildAltitudeObservations(images: List<ImageData>): Pair<org.json.JSONArray, Int> {
    val obsList = org.json.JSONArray()
    var skipped = 0
    images.forEach { img ->
        val obs = org.json.JSONObject()
        if (img.burstSubResults.isNotEmpty()) {
            // Burst: median ra, dec, alt from solved sub-results
            val solvedSubs = img.burstSubResults.filter { it.tetra3Result.solved }
            val ras = solvedSubs.mapNotNull { it.tetra3Result.raDeg }
            val decs = solvedSubs.mapNotNull { it.tetra3Result.decDeg }
            val alts = solvedSubs.mapNotNull { it.measuredHeight }
            if (ras.isEmpty() || decs.isEmpty() || alts.isEmpty()) {
                skipped++
                return@forEach
            }
            obs.put("ra", ras.sorted()[ras.size / 2])
            obs.put("dec", decs.sorted()[decs.size / 2])
            obs.put("alt", alts.sorted()[alts.size / 2])
        } else {
            val alt = img.measuredHeight
            if (alt == null) {
                skipped++
                return@forEach
            }
            obs.put("ra", img.tetra3Result.raDeg)
            obs.put("dec", img.tetra3Result.decDeg)
            obs.put("alt", alt)
        }
        obs.put("time_iso", img.timestamp)
        obsList.put(obs)
    }
    return Pair(obsList, skipped)
}


fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onImageCaptured: (Uri, String) -> Unit, // Return both URI and the file path
    onError: (ImageCaptureException) -> Unit
) {
    // 1. Create a file in the app's external files directory
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis()) + ".jpg"
    val photoFile = File(
        context.getExternalFilesDir(null), // App-specific storage
        name
    )

    // 2. Create output options for the new file
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    // 3. Take the picture
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraView", "Photo capture failed: ${exc.message}", exc)
                onError(exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                // 4. Return the URI and the absolute file path
                val savedUri = Uri.fromFile(photoFile)
                onImageCaptured(savedUri, photoFile.absolutePath)
            }
        })
}

suspend fun takePhotoSuspend(
    context: Context,
    imageCapture: ImageCapture
): Pair<Uri, String> = suspendCancellableCoroutine { cont ->
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis()) + ".jpg"
    val photoFile = File(context.getExternalFilesDir(null), name)
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                if (cont.isActive) cont.resumeWithException(exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = Uri.fromFile(photoFile)
                if (cont.isActive) cont.resume(Pair(savedUri, photoFile.absolutePath))
            }
        }
    )
}
