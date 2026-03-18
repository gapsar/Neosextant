package com.example.basic_neosextant

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
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
import com.example.basic_neosextant.model.*
import com.example.basic_neosextant.ui.components.ImageMetadataCard
import com.example.basic_neosextant.ui.components.ImageSlotView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
    onImageLongClick: (ImageData) -> Unit,
    navigatedToMap: Boolean,
    onNavigatedToMapChange: (Boolean) -> Unit,
    computedLatitude: Double?,
    onComputedLatitudeChange: (Double?) -> Unit,
    computedLongitude: Double?,
    onComputedLongitudeChange: (Double?) -> Unit,
    supportsManualExposure: Boolean,
    startPitchAveraging: () -> Unit,
    stopPitchAveraging: () -> Double?,
    markCalibrationUsed: () -> Unit,
    analysisJobs: java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }
    var isNightModeAvailable by remember { mutableStateOf(false) }

    // H-09: Only set manual exposure if device supports it
    val canManualExposure = supportsManualExposure
    val imageCapture = remember(isNightModeAvailable) {
        val builder = ImageCapture.Builder()

        if (!isNightModeAvailable && canManualExposure) {
            val extender = Camera2Interop.Extender(builder)
            // Manual Exposure: ISO 1600, 200ms
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, 1600)
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 200_000_000L) // 200ms in nanoseconds
        } else if (!isNightModeAvailable) {
            // Fallback: hint high ISO via auto exposure
            Log.w("CameraView", "Manual exposure not supported, using auto exposure")
        }

        builder.build()
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


    // H-13: Use mode-aware readyCount for LaunchedEffect key to avoid race conditions
    val readyCount by remember(capturedImages, solverMode) {
        derivedStateOf {
            capturedImages.count {
                if (solverMode == SolverMode.LOP) it.lopData != null
                else it.tetra3Result.solved
            }
        }
    }

    // Navigate to map when 3 images are captured and fully processed for the current mode
    LaunchedEffect(capturedImages.size, readyCount) {
        val solvedImages = capturedImages.filter { it.tetra3Result.solved }
        if (readyCount == 3 && !navigatedToMap) {
            onNavigatedToMapChange(true)

            try {
                // Run heavy Python computation off the main thread
                val result = withContext(Dispatchers.IO) {
                    val py = Python.getInstance()
                    val pythonScript = py.getModule("celestial_navigator")

                    if (solverMode == SolverMode.ITERATIVE) {
                        // --- ITERATIVE SOLVER ---
                        val obsList = org.json.JSONArray()
                        solvedImages.forEach { img ->
                            val obs = org.json.JSONObject()
                            obs.put("ra", img.tetra3Result.raDeg)
                            obs.put("dec", img.tetra3Result.decDeg)
                            obs.put("alt", img.measuredHeight ?: 0.0)
                            obs.put("time_iso", img.timestamp)
                            obsList.put(obs)
                        }

                        // H-06, H-07: Pass height, pressure, temperature to solver
                        val heightM = altitude.toDoubleOrNull() ?: 0.0
                        val pressureHpa = pressure.toDoubleOrNull() ?: 1013.25
                        val temperatureC = temperature.toDoubleOrNull() ?: 15.0

                        val solveResultJsonStr = pythonScript.callAttr(
                            "solve_iterative",
                            obsList.toString(),
                            latitude.toDoubleOrNull() ?: 0.0,
                            longitude.toDoubleOrNull() ?: 0.0,
                            heightM,
                            pressureHpa,
                            temperatureC
                        ).toString()

                        org.json.JSONObject(solveResultJsonStr)
                    } else {
                        // --- C-04: LOP SOLVER ---
                        // Collect LOP JSON results from each solved image
                        val lopJsons = solvedImages.mapNotNull { img ->
                            val ld = img.lopData ?: return@mapNotNull null
                            val j = org.json.JSONObject()
                            j.put("intercept_nm", ld.interceptNm)
                            j.put("azimuth_deg", ld.azimuthDeg)
                            j.put("observed_altitude_deg", ld.observedAltitudeDeg)
                            j.put("computed_altitude_deg", ld.computedAltitudeDeg)
                            j.put("error", JSONObject.NULL)
                            j.toString()
                        }

                        if (lopJsons.size < 3) {
                            org.json.JSONObject().apply { put("error", "Need 3 LOPs but only ${lopJsons.size} available") }
                        } else {
                            val solveResultJsonStr = pythonScript.callAttr(
                                "lop_center_compute",
                                lopJsons[0],
                                lopJsons[1],
                                lopJsons[2],
                                latitude.toDoubleOrNull() ?: 0.0,
                                longitude.toDoubleOrNull() ?: 0.0
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
                    return@LaunchedEffect
                }

                if (!result.has("fixed_latitude") || !result.has("fixed_longitude")) {
                    solverError = "Missing coordinates in solver result"
                    Log.e("Solver", "Python error: $solverError")
                    onNavigatedToMapChange(false)
                    return@LaunchedEffect
                }

                val finalLatitude = result.getDouble("fixed_latitude")
                val finalLongitude = result.getDouble("fixed_longitude")
                val shiftNm = result.optDouble("final_shift_nm", result.optDouble("error_estimate_nm", Double.NaN))
                Log.d("Solver", "Fix: Lat=$finalLatitude, Lon=$finalLongitude, error=$shiftNm NM")

                onComputedLatitudeChange(finalLatitude)
                onComputedLongitudeChange(finalLongitude)

                // Save to History Repository
                val modeStr = if (solverMode == SolverMode.ITERATIVE) "ITERATIVE" else "LOP"
                historyRepository.saveEntry(
                    PositionEntry(
                        timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(TimeSynchronizer.getTrueTime()),
                        latitude = finalLatitude,
                        longitude = finalLongitude,
                        errorEstimateNm = if (shiftNm.isNaN()) null else shiftNm,
                        mode = modeStr
                    )
                )

                navController.navigate("map")
            } catch (e: Exception) {
                Log.e("Solver", "Failed to compute position fix", e)
                onNavigatedToMapChange(false) // Allow retry
            }
        }
    }


    // C-02: Non-blocking camera provider init
    LaunchedEffect(Unit) {
        val cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }
        val future = ExtensionsManager.getInstanceAsync(context, cameraProvider)
        future.addListener({
            try {
                extensionsManager = future.get()
                val available = extensionsManager?.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, ExtensionMode.NIGHT) ?: false
                isNightModeAvailable = available

                if (available) {
                    activeCameraSelector = extensionsManager!!.getExtensionEnabledCameraSelector(
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        ExtensionMode.NIGHT
                    )
                }
            } catch (e: Exception) {
                Log.e("CameraView", "Error getting ExtensionsManager", e)
            }
        }, ContextCompat.getMainExecutor(context))
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0 until 3) {
                        val imageInfo = capturedImages.getOrNull(i)
                        val job = imageInfo?.let { analysisJobs[it.id] }
                        val isProcessing = job?.isActive == true ||
                                (imageInfo?.tetra3Result?.analysisState == AnalysisState.PENDING)

                        ImageSlotView(
                            modifier = Modifier
                                .weight(1f)
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
                    // Optional: Show a placeholder when no image is selected to maintain layout stability
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp), // Approx height of the metadata card
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Select an image to see details", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

            // Settings Button (Top Left)
            IconButton(
                onClick = { navController.navigate("settings") },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 32.dp, start = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(40.dp)
                )
            }

            // Go to Map Button (Top Right)
            AnimatedVisibility(
                visible = capturedImages.size == 3 && computedLatitude != null && computedLongitude != null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 12.dp)
            ) {
                IconButton(onClick = {
                    if (computedLatitude != null && computedLongitude != null) {
                        navController.navigate("map")
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "Go to Map",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }


            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 52.dp), // Adjust padding to position button above the sheet
                contentAlignment = Alignment.BottomCenter
            ) {
                IconButton(
                    modifier = Modifier.size(80.dp).tutorialTarget(5),
                    onClick = {
                    if (!isTakingPicture && capturedImages.size < 3) {
                        isTakingPicture = true

                        // Start averaging pitch readings
                        startPitchAveraging()

                        takePhoto(
                            context = context,
                            imageCapture = imageCapture,
                            onImageCaptured = { uri, path ->
                                // Stop averaging and get the result
                                val avgAltitude = stopPitchAveraging()
                                // Note: avgAltitude already includes the offset (handled in pipeline)
                                val measuredHeight = avgAltitude

                                // Mark calibration used
                                markCalibrationUsed()

                                val captureTime = TimeSynchronizer.getTrueTime() // Capture accurate synchronized time at shutter
                                val imageName = File(path).name
                                // C-03: Format timestamp as ISO 8601 UTC for Python backend
                                val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                                utcFormat.timeZone = TimeZone.getTimeZone("UTC")
                                val timestamp = utcFormat.format(captureTime)
                                val newImageInfo = ImageData(uri = uri, name = imageName, timestamp = timestamp, measuredHeight = measuredHeight)

                                onAddImage(newImageInfo)
                                selectedImageInfo = newImageInfo // Show metadata immediately

                                // --- Start of animation and background processing ---
                                scope.launch {
                                    scaffoldState.bottomSheetState.expand() // Lift up bottom sheet

                                    val job = launch(Dispatchers.IO) { // <--- Run on a background thread
                                        try {
                                            val py = Python.getInstance()
                                            val pythonScript = py.getModule("celestial_navigator")

                                            // --- Image Solving ---
                                            val imageResultJsonStr = pythonScript.callAttr("image_processor", imageName, path).toString()
                                            val imageResultJson = JSONObject(imageResultJsonStr)
                                            val isSolved = imageResultJson.optInt("solved") == 1

                                            var tetra3Result: Tetra3AnalysisResult
                                            var finalImageData: ImageData

                                            val options = android.graphics.BitmapFactory.Options()
                                            options.inJustDecodeBounds = true
                                            android.graphics.BitmapFactory.decodeFile(path, options)
                                            val imageWidth = options.outWidth
                                            val imageHeight = options.outHeight

                                            // M-05: Use EXIF orientation instead of width > height heuristic
                                            val needsRotation = try {
                                                val exif = ExifInterface(path)
                                                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                                                orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
                                            } catch (e: Exception) {
                                                imageWidth > imageHeight // Fallback to old heuristic
                                            }

                                            val centroidsArray = imageResultJson.optJSONArray("centroids")
                                            val parsedCentroids = mutableListOf<Pair<Double, Double>>()
                                            if (centroidsArray != null) {
                                                for (i in 0 until centroidsArray.length()) {
                                                    val pt = centroidsArray.optJSONArray(i)
                                                    if (pt != null && pt.length() >= 2) {
                                                        val rawCy = pt.optDouble(0)
                                                        val rawCx = pt.optDouble(1)
                                                        if (needsRotation) {
                                                            // Rotate 90 degrees clockwise
                                                            parsedCentroids.add(Pair(rawCx, imageHeight - rawCy))
                                                        } else {
                                                            parsedCentroids.add(Pair(rawCy, rawCx))
                                                        }
                                                    }
                                                }
                                            }

                                            if (isSolved) {
                                                tetra3Result = Tetra3AnalysisResult(
                                                    analysisState = AnalysisState.SUCCESS,
                                                    solved = true,
                                                    raDeg = imageResultJson.optDouble("ra_deg"),
                                                    decDeg = imageResultJson.optDouble("dec_deg"),
                                                    rollDeg = imageResultJson.optDouble("roll_deg"),
                                                    fovDeg = imageResultJson.optDouble("fov_deg"),
                                                    centroids = parsedCentroids
                                                )
                                                var updatedImage = newImageInfo.copy(tetra3Result = tetra3Result)

                                                // Switch back to the main thread to update UI
                                                withContext(Dispatchers.Main) {
                                                    onUpdateImage(updatedImage)
                                                    selectedImageInfo = updatedImage
                                                }

                                                // --- LOP Calculation (only in LOP mode) ---
                                                if (solverMode == SolverMode.LOP) {
                                                    val localDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(captureTime)
                                                    val localTimeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(captureTime)
                                                    val timezoneStr = TimeZone.getDefault().id

                                                    val lopResultJsonStr = pythonScript.callAttr(
                                                        "lop_compute",
                                                        tetra3Result.raDeg,
                                                        tetra3Result.decDeg,
                                                        latitude.toDoubleOrNull() ?: 0.0,
                                                        longitude.toDoubleOrNull() ?: 0.0,
                                                        0.0, // Height of Eye = 0 to bypass Dip (sensor altitude is already Ho)
                                                        pressure.toDoubleOrNull() ?: 1013.25,
                                                        temperature.toDoubleOrNull() ?: 15.0,
                                                        measuredHeight ?: 0.0,
                                                        localDateStr,
                                                        localTimeStr,
                                                        timezoneStr
                                                    ).toString()

                                                    val lopJson = JSONObject(lopResultJsonStr)
                                                    val lopData = LineOfPositionData(
                                                        interceptNm = lopJson.optDouble("intercept_nm", 0.0),
                                                        azimuthDeg = lopJson.optDouble("azimuth_deg"),
                                                        observedAltitudeDeg = lopJson.optDouble("observed_altitude_deg"),
                                                        computedAltitudeDeg = lopJson.optDouble("computed_altitude_deg", 0.0),
                                                        errorMessage = lopJson.optString("error").takeIf { it.isNotEmpty() && it != "null" }
                                                    )
                                                    finalImageData = updatedImage.copy(lopData = lopData)
                                                } else {
                                                    // Iterative mode: no LOP computation needed
                                                    finalImageData = updatedImage
                                                }
                                            } else {
                                                val errorMessage = imageResultJson.optString("error_message", "Unknown error")
                                                tetra3Result = Tetra3AnalysisResult(analysisState = AnalysisState.FAILURE, solved = false, errorMessage = errorMessage, centroids = parsedCentroids)
                                                finalImageData = newImageInfo.copy(tetra3Result = tetra3Result)
                                            }
                                            // Switch back to the main thread for the final UI update
                                            withContext(Dispatchers.Main) {
                                                onUpdateImage(finalImageData)
                                                selectedImageInfo = finalImageData
                                            }

                                            // --- Image Compression ---
                                            // Compress the image after all solving is done to save disk space
                                            try {
                                                val originalFile = java.io.File(path)
                                                if (originalFile.exists() && originalFile.length() > 0) {
                                                    val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                                                    if (bitmap != null) {
                                                        val finalBitmap = if (needsRotation) {
                                                            val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                                                            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                                        } else {
                                                            bitmap
                                                        }
                                                        val outputStream = java.io.FileOutputStream(originalFile)
                                                        finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 40, outputStream)
                                                        outputStream.flush()
                                                        outputStream.close()
                                                        if (finalBitmap != bitmap) finalBitmap.recycle()
                                                        bitmap.recycle()
                                                        Log.d("ImageCompression", "Successfully compressed and rotated $imageName")
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e("ImageCompression", "Failed to compress $imageName", e)
                                            }

                                        } catch (e: Exception) {
                                            Log.e("PythonExecution", "Error processing image or LOP", e)
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
                                }
                            },
                            onError = {
                                isTakingPicture = false
                                stopPitchAveraging() // Ensure we stop averaging on error
                            }
                        )
                    }
                }) {
                    Icon(
                        imageVector = Icons.Outlined.CameraAlt,
                        contentDescription = "Take picture",
                        modifier = Modifier.size(60.dp)
                    )
                }
            }
        }
    }

    // Display Iterative Solver Error Dialog
    if (solverError != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { solverError = null },
            title = { androidx.compose.material3.Text("Navigation Failed") },
            text = { androidx.compose.material3.Text(solverError!!) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { solverError = null }) {
                    androidx.compose.material3.Text("Dismiss")
                }
            }
        )
    }
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
                val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                onImageCaptured(savedUri, photoFile.absolutePath)
            }
        })
}
