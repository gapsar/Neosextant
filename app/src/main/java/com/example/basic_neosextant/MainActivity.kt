package com.example.basic_neosextant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.hardware.camera2.CaptureRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.AspectRatio
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberAsyncImagePainter
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.basic_neosextant.compose.Basic_neosextantTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import kotlin.math.atan2
import kotlin.math.sqrt

// Helper class for vector math
data class Vec3(val x: Float, val y: Float, val z: Float) {
    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )
    fun magnitudeSquared(): Float = x * x + y * y + z * z
    fun magnitude(): Float = sqrt(magnitudeSquared())
    fun normalized(): Vec3 {
        val mag = magnitude()
        return if (mag > 0.0001f) Vec3(x / mag, y / mag, z / mag) else Vec3(0f,0f,0f)
    }
}

// --- Data Classes for State Management ---

// Represents the state of the image analysis
enum class AnalysisState {
    PENDING, SUCCESS, FAILURE
}

// Holds the result from the Tetra3 Python script
data class Tetra3AnalysisResult(
    val analysisState: AnalysisState,
    val solved: Boolean = false,
    val raDeg: Double? = null,
    val decDeg: Double? = null,
    val rollDeg: Double? = null,
    val fovDeg: Double? = null,
    val errorMessage: String? = null
)

// Holds the result from the LOP computation
data class LineOfPositionData(
    val interceptNm: Double? = null,
    val azimuthDeg: Double? = null,
    val observedAltitudeDeg: Double? = null,
    val computedAltitudeDeg: Double? = null,
    val errorMessage: String? = null
)

// A comprehensive data class to hold all information about a captured image
data class ImageData(
    val id: Long = System.currentTimeMillis(),
    val uri: Uri,
    val name: String,
    val timestamp: String,
    val measuredHeight: Double?,
    val tetra3Result: Tetra3AnalysisResult = Tetra3AnalysisResult(analysisState = AnalysisState.PENDING),
    val lopData: LineOfPositionData? = null
)


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private val currentPhoneAltitudeDeg = mutableStateOf<Double?>(null)
    private val rotationMatrix = FloatArray(9)

    // A map to keep track of background processing jobs for each image
    val analysisJobs = mutableMapOf<Long, Job>()


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                setContent {
                    Basic_neosextantTheme {
                        AppNavigator(this) // Pass sensor listener
                    }
                }
            } else {
                // Handle permission denial
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OSMDroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        setupSensors() // Setup sensors

        if (allPermissionsGranted()) {
            setContent {
                Basic_neosextantTheme {
                    AppNavigator(this) // Pass sensor listener
                }
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVectorSensor == null) {
            Log.e("SensorSetup", "Sensor.TYPE_ROTATION_VECTOR not available!")
        }
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, it.values)
                val cameraPointingVector = Vec3(-rotationMatrix[2], -rotationMatrix[5], -rotationMatrix[8]).normalized()
                val camDirX = cameraPointingVector.x
                val camDirY = cameraPointingVector.y
                val camDirZ = cameraPointingVector.z

                val horizontalMagnitude = sqrt(camDirX * camDirX + camDirY * camDirY)
                val rawPitchReading = Math.toDegrees(atan2(camDirZ, horizontalMagnitude).toDouble())
                currentPhoneAltitudeDeg.value = rawPitchReading
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Can be used to handle changes in sensor accuracy if needed
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel all ongoing jobs when the activity is destroyed
        analysisJobs.values.forEach { it.cancel() }
        analysisJobs.clear()
    }


    // Public method to get the current pitch
    fun getCurrentPitch(): Double? {
        return currentPhoneAltitudeDeg.value
    }

    // Calibration Persistence
    fun saveCalibrationOffset(offset: Double) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putFloat("pitch_offset", offset.toFloat())
            apply()
        }
    }

    fun getCalibrationOffset(): Double {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getFloat("pitch_offset", 0.0f).toDouble()
    }
}

@Composable
fun AppNavigator(mainActivity: MainActivity) { // Accept MainActivity instance
    val navController = rememberNavController()
    var latitude by remember { mutableStateOf("49.49") }
    var longitude by remember { mutableStateOf("0.11") }
    var altitude by remember { mutableStateOf("20") }
    var shipSpeed by remember { mutableStateOf("") }
    var shipHeading by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("") }
    var pressure by remember { mutableStateOf("") }

    // Lifted state for captured images and computed position
    val capturedImages = remember { mutableStateListOf<ImageData>() }
    var navigatedToMap by remember { mutableStateOf(false) }
    var computedLatitude by remember { mutableStateOf<Double?>(null) }
    var computedLongitude by remember { mutableStateOf<Double?>(null) }


    NavHost(navController = navController, startDestination = "camera") {
        composable("camera") {
            CameraView(
                navController = navController,
                mainActivity = mainActivity,
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                temperature = temperature,
                pressure = pressure,
                getCurrentPitch = mainActivity::getCurrentPitch, // Pass function reference
                capturedImages = capturedImages,
                onUpdateImage = { updatedImage ->
                    val index = capturedImages.indexOfFirst { it.id == updatedImage.id }
                    if (index != -1) {
                        capturedImages[index] = updatedImage
                    }
                },
                onAddImage = { newImage -> capturedImages.add(newImage) },
                onRemoveImage = { imageToRemove -> capturedImages.remove(imageToRemove) },
                navigatedToMap = navigatedToMap,
                onNavigatedToMapChange = { navigatedToMap = it },
                computedLatitude = computedLatitude,
                onComputedLatitudeChange = { computedLatitude = it },
                computedLongitude = computedLongitude,
                onComputedLongitudeChange = { computedLongitude = it }
            )
        }
        composable("settings") {
            SettingsScreen(
                initialLatitude = latitude,
                onLatitudeChange = { latitude = it },
                initialLongitude = longitude,
                onLongitudeChange = { longitude = it },
                initialAltitude = altitude,
                onAltitudeChange = { altitude = it },
                shipSpeed = shipSpeed,
                onShipSpeedChange = { shipSpeed = it },
                shipHeading = shipHeading,
                onShipHeadingChange = { shipHeading = it },
                temperature = temperature,
                onTemperatureChange = { temperature = it },
                pressure = pressure,
                onPressureChange = { pressure = it },
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCalibration = { navController.navigate("calibration") }
            )
        }
        composable("calibration") {
            CalibrationScreen(
                onNavigateBack = { navController.popBackStack() },
                getCurrentPitch = mainActivity::getCurrentPitch,
                onSaveCalibration = mainActivity::saveCalibrationOffset,
                currentOffset = mainActivity.getCalibrationOffset()
            )
        }
        composable("map/{lop1Azimuth}/{lop1Intercept}/{lop2Azimuth}/{lop2Intercept}/{lop3Azimuth}/{lop3Intercept}/{computedLatitude}/{computedLongitude}") { backStackEntry ->
            val lop1Azimuth = backStackEntry.arguments?.getString("lop1Azimuth")?.toFloatOrNull() ?: 0f
            val lop1Intercept = backStackEntry.arguments?.getString("lop1Intercept")?.toFloatOrNull() ?: 0f
            val lop2Azimuth = backStackEntry.arguments?.getString("lop2Azimuth")?.toFloatOrNull() ?: 0f
            val lop2Intercept = backStackEntry.arguments?.getString("lop2Intercept")?.toFloatOrNull() ?: 0f
            val lop3Azimuth = backStackEntry.arguments?.getString("lop3Azimuth")?.toFloatOrNull() ?: 0f
            val lop3Intercept = backStackEntry.arguments?.getString("lop3Intercept")?.toFloatOrNull() ?: 0f
            val computedLatitudeNav = backStackEntry.arguments?.getString("computedLatitude")?.toDoubleOrNull() ?: 0.0
            val computedLongitudeNav = backStackEntry.arguments?.getString("computedLongitude")?.toDoubleOrNull() ?: 0.0

            MapScreen(
                navController = navController,
                estimatedLatitude = latitude,
                estimatedLongitude = longitude,
                lop1Azimuth = lop1Azimuth,
                lop1Intercept = lop1Intercept,
                lop2Azimuth = lop2Azimuth,
                lop2Intercept = lop2Intercept,
                lop3Azimuth = lop3Azimuth,
                lop3Intercept = lop3Intercept,
                computedLatitude = computedLatitudeNav,
                computedLongitude = computedLongitudeNav
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalCamera2Interop::class)
@Composable
fun CameraView(
    navController: NavController,
    mainActivity: MainActivity,
    latitude: String,
    longitude: String,
    altitude: String,
    temperature: String,
    pressure: String,
    getCurrentPitch: () -> Double?,
    capturedImages: List<ImageData>,
    onAddImage: (ImageData) -> Unit,
    onUpdateImage: (ImageData) -> Unit,
    onRemoveImage: (ImageData) -> Unit,
    navigatedToMap: Boolean,
    onNavigatedToMapChange: (Boolean) -> Unit,
    computedLatitude: Double?,
    onComputedLatitudeChange: (Double?) -> Unit,
    computedLongitude: Double?,
    onComputedLongitudeChange: (Double?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }
    var isNightModeAvailable by remember { mutableStateOf(false) }
    
    // Configure ImageCapture with manual settings
    // Configure ImageCapture
    // If Night Mode is NOT available, we use manual exposure (200ms) as fallback.
    // If Night Mode IS available, we use default settings (letting the extension handle it).
    val imageCapture = remember(isNightModeAvailable) {
        val builder = ImageCapture.Builder()
        
        if (!isNightModeAvailable) {
            val extender = Camera2Interop.Extender(builder)
            // Manual Exposure: ISO 1600, 200ms
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, 1600)
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 200_000_000L) // 200ms in nanoseconds
        }
        
        builder.build()
    }
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    var isTakingPicture by remember { mutableStateOf(false) }
    var selectedImageInfo by remember { mutableStateOf<ImageData?>(null) }
    var extensionsManager by remember { mutableStateOf<ExtensionsManager?>(null) }
    var activeCameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }


    // Navigate to map when 3 images are captured and solved
    LaunchedEffect(capturedImages.size, capturedImages.map { it.lopData }) {
        val solvedImagesWithLop = capturedImages.filter { it.lopData?.interceptNm != null }
        if (solvedImagesWithLop.size == 3 && !navigatedToMap) {
            onNavigatedToMapChange(true)
            val py = Python.getInstance()
            val pythonScript = py.getModule("celestial_navigator")

            // Create JSON strings for LOP data for each image
            val lopJsonStrings = solvedImagesWithLop.mapNotNull { imageData ->
                imageData.lopData?.let { lop ->
                    JSONObject().apply {
                        put("intercept_nm", lop.interceptNm)
                        put("azimuth_deg", lop.azimuthDeg)
                        put("observed_altitude_deg", lop.observedAltitudeDeg)
                        put("computed_altitude_deg", lop.computedAltitudeDeg)
                        put("error", lop.errorMessage)
                    }.toString()
                }
            }

            if (lopJsonStrings.size == 3) {
                val lopCenterResultJsonStr = pythonScript.callAttr(
                    "lop_center_compute",
                    lopJsonStrings[0],
                    lopJsonStrings[1],
                    lopJsonStrings[2],
                    latitude.toDoubleOrNull() ?: 0.0,
                    longitude.toDoubleOrNull() ?: 0.0
                ).toString()

                val lopCenterJson = org.json.JSONObject(lopCenterResultJsonStr)
                val finalLatitude = lopCenterJson.getDouble("fixed_latitude")
                val finalLongitude = lopCenterJson.getDouble("fixed_longitude")

                onComputedLatitudeChange(finalLatitude)
                onComputedLongitudeChange(finalLongitude)

                val lop1Json = org.json.JSONObject(lopJsonStrings[0])
                val lop2Json = org.json.JSONObject(lopJsonStrings[1])
                val lop3Json = org.json.JSONObject(lopJsonStrings[2])

                val lop1Azimuth = lop1Json.getDouble("azimuth_deg").toFloat()
                val lop1Intercept = lop1Json.getDouble("intercept_nm").toFloat()
                val lop2Azimuth = lop2Json.getDouble("azimuth_deg").toFloat()
                val lop2Intercept = lop2Json.getDouble("intercept_nm").toFloat()
                val lop3Azimuth = lop3Json.getDouble("azimuth_deg").toFloat()
                val lop3Intercept = lop3Json.getDouble("intercept_nm").toFloat()

                navController.navigate("map/$lop1Azimuth/$lop1Intercept/$lop2Azimuth/$lop2Intercept/$lop3Azimuth/$lop3Intercept/$finalLatitude/$finalLongitude")
            }
        }
    }


    LaunchedEffect(Unit) {
        val cameraProvider = cameraProviderFuture.get()
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

    LaunchedEffect(cameraProviderFuture, activeCameraSelector) {
        val cameraProvider = cameraProviderFuture.get()
        
        // Configure Preview with default settings
        // Configure Preview with high resolution
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
                        val job = imageInfo?.let { mainActivity.analysisJobs[it.id] }
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
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // *** FIX 2: Replace AnimatedVisibility with a simple 'if' block ***
                if (selectedImageInfo != null) {
                    ImageMetadataCard(
                        imageInfo = selectedImageInfo!!,
                        onRemoveClick = {
                            mainActivity.analysisJobs.remove(selectedImageInfo!!.id)?.cancel()
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
                    val solvedImagesWithLop = capturedImages.filter { it.lopData?.interceptNm != null }
                    if (solvedImagesWithLop.size == 3 && computedLatitude != null && computedLongitude != null) {
                        try {
                            val lop1 = solvedImagesWithLop[0].lopData!!
                            val lop2 = solvedImagesWithLop[1].lopData!!
                            val lop3 = solvedImagesWithLop[2].lopData!!

                            navController.navigate("map/${lop1.azimuthDeg}/${lop1.interceptNm}/${lop2.azimuthDeg}/${lop2.interceptNm}/${lop3.azimuthDeg}/${lop3.interceptNm}/$computedLatitude/$computedLongitude")
                        } catch (e: Exception) {
                            Log.e("MapNavigation", "Failed to get LOP data for map navigation", e)
                        }
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
                IconButton(onClick = {
                    if (!isTakingPicture && capturedImages.size < 3) {
                        isTakingPicture = true
                        // Apply calibration offset to the measured height
                        val rawPitch = getCurrentPitch()
                        val offset = mainActivity.getCalibrationOffset()
                        val measuredHeight = if (rawPitch != null) rawPitch + offset else null
                        
                        takePhoto(
                            context = context,
                            imageCapture = imageCapture,
                            onImageCaptured = { uri, path ->
                                val imageName = File(path).name
                                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
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

                                            if (isSolved) {
                                                tetra3Result = Tetra3AnalysisResult(
                                                    analysisState = AnalysisState.SUCCESS,
                                                    solved = true,
                                                    raDeg = imageResultJson.optDouble("ra_deg"),
                                                    decDeg = imageResultJson.optDouble("dec_deg"),
                                                    rollDeg = imageResultJson.optDouble("roll_deg"),
                                                    fovDeg = imageResultJson.optDouble("fov_deg")
                                                )
                                                var updatedImage = newImageInfo.copy(tetra3Result = tetra3Result)

                                                // Switch back to the main thread to update UI
                                                withContext(Dispatchers.Main) {
                                                    onUpdateImage(updatedImage)
                                                    selectedImageInfo = updatedImage
                                                }

                                                // --- LOP Calculation ---
                                                val localDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                                val localTimeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                                                val timezoneStr = TimeZone.getDefault().id

                                                val lopResultJsonStr = pythonScript.callAttr(
                                                    "lop_compute",
                                                    tetra3Result.raDeg,
                                                    tetra3Result.decDeg,
                                                    latitude.toDoubleOrNull() ?: 0.0,
                                                    longitude.toDoubleOrNull() ?: 0.0,
                                                    altitude.toDoubleOrNull() ?: 0.0,
                                                    pressure.toDoubleOrNull() ?: 1013.25,
                                                    temperature.toDoubleOrNull() ?: 15.0,
                                                    measuredHeight ?: 0.0,
                                                    localDateStr,
                                                    localTimeStr,
                                                    timezoneStr
                                                ).toString()

                                                val lopJson = JSONObject(lopResultJsonStr)
                                                val lopData = LineOfPositionData(
                                                    interceptNm = lopJson.optDouble("intercept_nm"),
                                                    azimuthDeg = lopJson.optDouble("azimuth_deg"),
                                                    observedAltitudeDeg = lopJson.optDouble("observed_altitude_deg"),
                                                    computedAltitudeDeg = lopJson.optDouble("computed_altitude_deg"),
                                                    errorMessage = lopJson.optString("error").takeIf { it.isNotEmpty() && it != "null" }
                                                )
                                                finalImageData = updatedImage.copy(lopData = lopData)
                                            } else {
                                                val errorMessage = imageResultJson.optString("error_message", "Unknown error")
                                                tetra3Result = Tetra3AnalysisResult(analysisState = AnalysisState.FAILURE, solved = false, errorMessage = errorMessage)
                                                finalImageData = newImageInfo.copy(tetra3Result = tetra3Result)
                                            }
                                            // Switch back to the main thread for the final UI update
                                            withContext(Dispatchers.Main) {
                                                onUpdateImage(finalImageData)
                                                selectedImageInfo = finalImageData
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
                                    mainActivity.analysisJobs[newImageInfo.id] = job
                                }
                            },
                            onError = {
                                isTakingPicture = false
                            }
                        )
                    }
                }, modifier = Modifier.size(80.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.CameraAlt,
                        contentDescription = "Take picture",
                        modifier = Modifier.size(60.dp)
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialLatitude: String,
    onLatitudeChange: (String) -> Unit,
    initialLongitude: String,
    onLongitudeChange: (String) -> Unit,
    initialAltitude: String,
    onAltitudeChange: (String) -> Unit,
    shipSpeed: String,
    onShipSpeedChange: (String) -> Unit,
    shipHeading: String,
    onShipHeadingChange: (String) -> Unit,
    temperature: String,
    onTemperatureChange: (String) -> Unit,
    pressure: String,
    onPressureChange: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToCalibration: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Estimated Position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = initialLatitude, onValueChange = onLatitudeChange, label = { Text("Estimated Latitude (e.g., 49.49)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = initialLongitude, onValueChange = onLongitudeChange, label = { Text("Estimated Longitude (e.g., 0.11)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), singleLine = true)
            
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToCalibration, modifier = Modifier.fillMaxWidth()) {
                Text("Calibrate Horizon")
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = initialAltitude, onValueChange = onAltitudeChange, label = { Text("Estimated Altitude (meters, e.g., 20)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(), singleLine = true)

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Vessel Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = shipSpeed, onValueChange = onShipSpeedChange, label = { Text("Ship's Speed (knots)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = shipHeading, onValueChange = onShipHeadingChange, label = { Text("Ship's Heading (degrees true)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(), singleLine = true)

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Weather Conditions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = temperature, onValueChange = onTemperatureChange, label = { Text("Temperature (°C)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = pressure, onValueChange = onPressureChange, label = { Text("Pressure (hPa)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
    }
}


@Composable
fun ImageSlotView(
    modifier: Modifier = Modifier,
    imageInfo: ImageData?,
    isSelected: Boolean,
    isProcessing: Boolean,
    onClick: (ImageData) -> Unit
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
            .clickable(enabled = imageInfo != null) {
                imageInfo?.let { onClick(it) }
            },
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
                    contentDescription = "Captured image",
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
                    Text("Empty")
                }
            }
        }
    }
}

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

            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

            when (imageInfo.tetra3Result.analysisState) {
                AnalysisState.PENDING -> MetadataRow("Status:", "Processing...")
                AnalysisState.FAILURE -> MetadataRow("Status:", "Failed: ${imageInfo.tetra3Result.errorMessage}")
                AnalysisState.SUCCESS -> {
                    if (imageInfo.tetra3Result.solved) {
                        MetadataRow("Status:", "Solved")
                        imageInfo.tetra3Result.raDeg?.let { MetadataRow("RA:", String.format(Locale.US, "%.4f°", it)) }
                        imageInfo.tetra3Result.decDeg?.let { MetadataRow("Dec:", String.format(Locale.US, "%.4f°", it)) }

                        // LOP Data
                        imageInfo.lopData?.let { lop ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("LOP Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            if (lop.errorMessage != null) {
                                MetadataRow("Error:", lop.errorMessage)
                            } else {
                                lop.interceptNm?.let { MetadataRow("Intercept:", String.format(Locale.US, "%.2f NM", it)) }
                                lop.azimuthDeg?.let { MetadataRow("Azimuth:", String.format(Locale.US, "%.2f°", it)) }
                            }
                        } ?: MetadataRow("LOP Status:", "Calculating...")
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