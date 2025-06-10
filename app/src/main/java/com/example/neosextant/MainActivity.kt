package com.example.neosextant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.extensions.ExtensionMode
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny // For Normal Mode
import androidx.compose.material.icons.outlined.ImageNotSupported // For empty image slot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import com.example.neosextant.ui.theme.NeosextantTheme
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive // Import for coroutine cancellation check
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt


// TOP-LEVEL CONSTANTS FOR CALIBRATION
private const val SENSOR_SAMPLING_RATE_HZ = 50
private const val CALIBRATION_DURATION_SEC = 20
private const val SETTLING_DURATION_SEC = 5

// Vec3 class
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)
    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z
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

// Enum to manage calibration state
enum class CalibrationStep {
    Idle,
    Step1_Sky_Instruct,
    Step1_Sky_InProgress,
    Calibrating_Finalizing,
    Calibrated
}

// SharedPreferences Keys
const val SHARED_PREFS_NAME = "NeosextantPrefs"
const val KEY_PITCH_CALIBRATION_OFFSET = "pitch_calibration_offset"
const val KEY_EST_LAT = "estimatedLatitude"
const val KEY_EST_LON = "estimatedLongitude"
const val KEY_EST_ALT = "estimatedAltitude"
const val KEY_SHIP_SPEED = "shipSpeed"
const val KEY_SHIP_HEADING = "shipHeading"

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null

    // Sensor data states
    private val currentAccelerometerData = mutableStateOf<List<Float>?>(null)
    private val currentGyroscopeData = mutableStateOf<List<Float>?>(null)
    private val currentMagnetometerData = mutableStateOf<List<Float>?>(null)

    // Orientation states (raw from sensor, corrected for declination)
    private val currentPhoneTrueAzimuthDeg = mutableStateOf<Double?>(null)
    private val currentPhoneAltitudeDeg = mutableStateOf<Double?>(null) // Raw pitch
    private val currentPhoneRollDeg = mutableStateOf<Double?>(null)

    private val rotationMatrix = FloatArray(9)
    private var geoField: GeomagneticField? = null
    private var magneticDeclination by mutableStateOf(0f)

    // User settings states
    var estimatedLatitudeState by mutableStateOf("49.49")
    var estimatedLongitudeState by mutableStateOf("0.11")
    private var estimatedAltitudeState by mutableStateOf("20")
    private var shipSpeedState by mutableStateOf("")
    private var shipHeadingState by mutableStateOf("")
    private var pitchCalibrationOffsetStringState by mutableStateOf("0.0")

    private var toastContext: Context? = null
    val analysisJobs = mutableMapOf<Long, Job>() // Map to track ongoing analysis jobs

    // Calibration State Variables
    private var calibrationStepState by mutableStateOf(CalibrationStep.Idle)
    private var calibrationTimerValueState by mutableStateOf(0)
    private val collectedPitchValuesList = mutableListOf<Float>()
    private var calibrationUIMessageState by mutableStateOf("")
    private var calibrationTimerJob: Job? = null
    private var sensorAccuracyStatusState by mutableStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toastContext = applicationContext
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupSensors()

        // Load preferences
        val prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        estimatedLatitudeState = prefs.getString(KEY_EST_LAT, "49.49") ?: "49.49"
        estimatedLongitudeState = prefs.getString(KEY_EST_LON, "0.11") ?: "0.11"
        estimatedAltitudeState = prefs.getString(KEY_EST_ALT, "20") ?: "20"
        shipSpeedState = prefs.getString(KEY_SHIP_SPEED, "") ?: ""
        shipHeadingState = prefs.getString(KEY_SHIP_HEADING, "") ?: ""
        pitchCalibrationOffsetStringState = prefs.getFloat(KEY_PITCH_CALIBRATION_OFFSET, 0.0f).toString()

        updateGeomagneticField(estimatedLatitudeState, estimatedLongitudeState, estimatedAltitudeState)

        // Initialize Python if not already started
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        setContent {
            var showSettingsScreen by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()

            // LaunchedEffects for saving preferences
            LaunchedEffect(estimatedLatitudeState, estimatedLongitudeState, estimatedAltitudeState) {
                updateGeomagneticField(estimatedLatitudeState, estimatedLongitudeState, estimatedAltitudeState)
                with(prefs.edit()) {
                    putString(KEY_EST_LAT, estimatedLatitudeState)
                    putString(KEY_EST_LON, estimatedLongitudeState)
                    putString(KEY_EST_ALT, estimatedAltitudeState)
                    apply()
                }
            }
            LaunchedEffect(shipSpeedState, shipHeadingState) {
                with(prefs.edit()) {
                    putString(KEY_SHIP_SPEED, shipSpeedState)
                    putString(KEY_SHIP_HEADING, shipHeadingState)
                    apply()
                }
            }
            LaunchedEffect(pitchCalibrationOffsetStringState) {
                with(prefs.edit()) {
                    putFloat(KEY_PITCH_CALIBRATION_OFFSET, pitchCalibrationOffsetStringState.toFloatOrNull() ?: 0.0f)
                    apply()
                }
            }

            NeosextantTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (showSettingsScreen) {
                        SettingsScreen(
                            initialLatitude = estimatedLatitudeState,
                            onLatitudeChange = { estimatedLatitudeState = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                            initialLongitude = estimatedLongitudeState,
                            onLongitudeChange = { estimatedLongitudeState = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                            initialAltitude = estimatedAltitudeState,
                            onAltitudeChange = { estimatedAltitudeState = it.filter { c -> c.isDigit() || c == '.' } },
                            currentDeclination = magneticDeclination,
                            shipSpeed = shipSpeedState,
                            onShipSpeedChange = { shipSpeedState = it.filter { c -> c.isDigit() || c == '.' } },
                            shipHeading = shipHeadingState,
                            onShipHeadingChange = { shipHeadingState = it.filter { c -> c.isDigit() || c == '.' } },
                            currentPitchOffsetString = pitchCalibrationOffsetStringState,
                            onNavigateBack = { showSettingsScreen = false },
                            calibrationStep = calibrationStepState,
                            onCalibrationStepChange = { newStep ->
                                calibrationStepState = newStep
                                if (newStep == CalibrationStep.Step1_Sky_Instruct) {
                                    calibrationUIMessageState = "Point phone screen UP (towards the sky/zenith). Hold very still. Press Start."
                                }
                            },
                            calibrationTimerValue = calibrationTimerValueState,
                            calibrationMessage = calibrationUIMessageState,
                            startCalibrationPhase = { step ->
                                startCalibrationPhase(step, coroutineScope)
                            },
                            sensorAccuracyStatus = sensorAccuracyStatusState,
                            rawPhonePitch = currentPhoneAltitudeDeg.value ?: 0.0,
                            calibrationDurationSec = CALIBRATION_DURATION_SEC,
                            settlingDurationSec = SETTLING_DURATION_SEC
                        )
                    } else {
                        val currentPitchOffset = pitchCalibrationOffsetStringState.toFloatOrNull() ?: 0.0f
                        CameraApp(
                            getSensorAndOrientationData = {
                                val rawPhonePitchDeg = currentPhoneAltitudeDeg.value
                                val correctedPhonePitchDeg = rawPhonePitchDeg?.plus(currentPitchOffset)
                                SensorAndOrientationData(
                                    timestamp = System.currentTimeMillis(),
                                    accelerometer = currentAccelerometerData.value,
                                    gyroscope = currentGyroscopeData.value,
                                    magnetometer = currentMagnetometerData.value,
                                    phoneAzimuthDeg = currentPhoneTrueAzimuthDeg.value,
                                    phonePitchDeg = correctedPhonePitchDeg, // Corrected pitch
                                    phoneRollDeg = currentPhoneRollDeg.value
                                )
                            },
                            initialLatitude = estimatedLatitudeState,
                            onLatitudeChange = { estLat -> estimatedLatitudeState = estLat },
                            initialLongitude = estimatedLongitudeState,
                            onLongitudeChange = { estLon -> estimatedLongitudeState = estLon },
                            initialAltitude = estimatedAltitudeState,
                            onAltitudeChange = { estAlt -> estimatedAltitudeState = estAlt },
                            currentDeclination = magneticDeclination,
                            onOpenSettings = { showSettingsScreen = true }
                        )
                    }
                }
            }
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVectorSensor == null) {
            Log.e("SensorSetup", "Sensor.TYPE_ROTATION_VECTOR not available!")
            Toast.makeText(this, "Rotation Vector Sensor not available. Orientation may be inaccurate.", Toast.LENGTH_LONG).show()
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun updateGeomagneticField(latStr: String, lonStr: String, altStr: String) {
        val lat = latStr.toFloatOrNull()
        val lon = lonStr.toFloatOrNull()
        val alt = altStr.toFloatOrNull()

        if (lat != null && lon != null && alt != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
            geoField = GeomagneticField(lat, lon, alt, System.currentTimeMillis())
            magneticDeclination = geoField?.declination ?: 0f
            Log.i("GeomagneticField", "Updated: Lat=$lat, Lon=$lon, Alt=$alt, Declination=$magneticDeclination")
        } else {
            Log.w("GeomagneticField", "Invalid location input for GeomagneticField. Lat: $latStr, Lon: $lonStr, Alt: $altStr")
            geoField = null
            magneticDeclination = 0f
        }
    }

    private fun startCalibrationPhase(phase: CalibrationStep, scope: kotlinx.coroutines.CoroutineScope) {
        calibrationStepState = phase
        calibrationTimerJob?.cancel()

        if (phase == CalibrationStep.Step1_Sky_InProgress) {
            calibrationTimerValueState = CALIBRATION_DURATION_SEC
            collectedPitchValuesList.clear()
            calibrationUIMessageState = "Calibrating: Screen UP... Keep phone very still."
            calibrationTimerJob = scope.launch {
                while (calibrationTimerValueState > 0 && isActive) {
                    delay(1000)
                    calibrationTimerValueState--
                }
                if (!isActive) return@launch

                calibrationStepState = CalibrationStep.Calibrating_Finalizing
                calibrationUIMessageState = "Finalizing calibration..."
                delay(500)

                val samplesToDiscard = SETTLING_DURATION_SEC * SENSOR_SAMPLING_RATE_HZ
                val validSamples = if (collectedPitchValuesList.size > samplesToDiscard) {
                    collectedPitchValuesList.subList(samplesToDiscard, collectedPitchValuesList.size)
                } else {
                    collectedPitchValuesList
                }

                if (validSamples.isNotEmpty()) {
                    val averageRawPitchScreenUp = validSamples.average().toFloat()
                    val newOffset = -90.0f - averageRawPitchScreenUp
                    pitchCalibrationOffsetStringState = newOffset.format(2)

                    calibrationUIMessageState = String.format(
                        Locale.US,
                        "Calibration Complete!\nNew Pitch Offset: %.2f°\n(Raw Nadir Avg from last %d s: %.2f°)",
                        newOffset,
                        CALIBRATION_DURATION_SEC - SETTLING_DURATION_SEC,
                        averageRawPitchScreenUp
                    )
                    Log.i("Calibration", "New offset: $newOffset, AvgRawPitchScreenUp: $averageRawPitchScreenUp, Samples used: ${validSamples.size}")
                } else {
                    calibrationUIMessageState = "Calibration failed: Not enough data collected after settling. Previous offset retained."
                    Log.w("Calibration", "Not enough valid samples. Collected: ${collectedPitchValuesList.size}, Discarded up to: $samplesToDiscard")
                }
                calibrationStepState = CalibrationStep.Calibrated
            }
        } else if (phase == CalibrationStep.Step1_Sky_Instruct) {
            calibrationUIMessageState = "Point phone screen UP (towards the sky/zenith). Hold very still. Press Start."
        }
    }


    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        calibrationTimerJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        analysisJobs.values.forEach { it.cancel() }
        analysisJobs.clear()
        toastContext = null
        calibrationTimerJob?.cancel()
        try {
            Python.getInstance().getModule("image_processor")?.callAttr("cleanup_resources")
            Python.getInstance().getModule("astro_navigator")?.callAttr("cleanup_resources")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error cleaning up Python resources: $e")
        }
    }

    @SuppressLint("NewApi")
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> currentAccelerometerData.value = it.values.toList()
                Sensor.TYPE_GYROSCOPE -> currentGyroscopeData.value = it.values.toList()
                Sensor.TYPE_MAGNETIC_FIELD -> currentMagnetometerData.value = it.values.toList()
            }

            if (it.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                sensorAccuracyStatusState = it.accuracy
                SensorManager.getRotationMatrixFromVector(rotationMatrix, it.values)
                val cameraPointingVector = Vec3(-rotationMatrix[2], -rotationMatrix[5], -rotationMatrix[8]).normalized()
                val camDirX = cameraPointingVector.x
                val camDirY = cameraPointingVector.y
                val camDirZ = cameraPointingVector.z
                var magAzRad = atan2(camDirX, camDirY)
                var magAzDeg = Math.toDegrees(magAzRad.toDouble()).toFloat()
                if (magAzDeg < 0) magAzDeg += 360f
                var trueAzDeg = magAzDeg + (geoField?.declination ?: 0f)
                if (trueAzDeg >= 360) trueAzDeg -= 360f
                if (trueAzDeg < 0) trueAzDeg += 360f
                currentPhoneTrueAzimuthDeg.value = trueAzDeg.toDouble()
                val horizontalMagnitude = sqrt(camDirX * camDirX + camDirY * camDirY)
                val rawPitchReading = Math.toDegrees(atan2(camDirZ, horizontalMagnitude).toDouble())
                currentPhoneAltitudeDeg.value = rawPitchReading
                if (calibrationStepState == CalibrationStep.Step1_Sky_InProgress) {
                    collectedPitchValuesList.add(rawPitchReading.toFloat())
                }
                val deviceYworld = Vec3(rotationMatrix[1], rotationMatrix[4], rotationMatrix[7]).normalized()
                var worldUpRef = Vec3(0f, 0f, 1f)
                if (abs(cameraPointingVector.dot(worldUpRef)) > 0.95f) {
                    worldUpRef = Vec3(0f, 1f, 0f)
                }
                val viewX = cameraPointingVector.cross(worldUpRef).normalized()
                val viewXFinal = if (viewX.magnitudeSquared() < 0.001f) {
                    val alternativeWorldUp = if (worldUpRef.z > 0.9f) Vec3(0f, 1f, 0f) else Vec3(0f, 0f, 1f)
                    cameraPointingVector.cross(alternativeWorldUp).normalized()
                } else { viewX }
                val viewY = viewXFinal.cross(cameraPointingVector).normalized()
                val devY_comp_viewX = deviceYworld.dot(viewXFinal)
                val devY_comp_viewY = deviceYworld.dot(viewY)
                currentPhoneRollDeg.value = Math.toDegrees(atan2(devY_comp_viewX, devY_comp_viewY).toDouble())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            sensorAccuracyStatusState = accuracy
        }
        val sensorName = sensor?.name ?: "Unknown Sensor"
        val accuracyStr = when(accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN ($accuracy)"
        }
        Log.i("SensorAccuracy", "Accuracy for $sensorName changed to: $accuracyStr")
    }

    fun takePhoto(
        context: Context,
        imageCapture: ImageCapture,
        sensorAndOrientationData: SensorAndOrientationData,
        coroutineScope: kotlinx.coroutines.CoroutineScope,
        onImageCaptured: (ImageData) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        val photoFile = createImageFile(context, "CAMERA_")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: Uri.fromFile(photoFile)
                    val initialImageData = ImageData(
                        uri = savedUri,
                        sensorAndOrientationData = sensorAndOrientationData,
                        sourceType = ImageSourceType.CAMERA,
                        tetra3Result = Tetra3AnalysisResult(analysisState = AnalysisState.PENDING)
                    )
                    onImageCaptured(initialImageData)

                    val job = coroutineScope.launch {
                        var analyzedImageData = analyzeImageForRaDec(context, initialImageData)
                        onImageCaptured(analyzedImageData)

                        if (analyzedImageData.tetra3Result.analysisState == AnalysisState.SUCCESS && analyzedImageData.tetra3Result.solved) {
                            Log.d("LOP_Flow", "RA/Dec solved, proceeding to LOP calculation for image ID: ${analyzedImageData.id}")
                            val imageDataWithLop = calculateLopForImage(context, analyzedImageData)
                            onImageCaptured(imageDataWithLop)
                            saveMetadataToFile(context, imageDataWithLop, photoFile)
                        } else if (analyzedImageData.tetra3Result.analysisState != AnalysisState.PENDING) {
                            saveMetadataToFile(context, analyzedImageData, photoFile)
                        }
                    }
                    analysisJobs[initialImageData.id] = job
                }
                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                    Log.e("CameraXApp", "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    suspend fun analyzeImageForRaDec(context: Context, imageData: ImageData): ImageData {
        Log.d("CameraXApp", "Starting RA/Dec analysis for image ID: ${imageData.id} (Source: ${imageData.sourceType})")
        val analysisResult: Tetra3AnalysisResult = withContext(Dispatchers.IO) {
            val imagePath = getPathFromUri(context, imageData.uri, true)
            var tempRes: Tetra3AnalysisResult? = null
            var kotlinMap: Map<String, Any?>? = null

            if (imagePath != null) {
                try {
                    val py = Python.getInstance()
                    val module = py.getModule("image_processor")
                    val resultObj: PyObject? = module.callAttr("solve_image_from_path", imagePath, 10000, null)

                    if (resultObj != null) {
                        kotlinMap = try {
                            resultObj.asMap()
                                .mapKeys { it.key?.toString() ?: "NULL_KEY_${System.nanoTime()}" }
                                .mapValues { it.value?.toJava(Any::class.java) }
                        } catch (e: Exception) {
                            Log.e("PythonSolveDebug", "[${imageData.id}] Error calling asMap() or converting values: $e")
                            null
                        }

                        if (kotlinMap != null) {
                            val solvedValue = kotlinMap["solved"]
                            val isSol = solvedValue is Number && solvedValue.toInt() == 1
                            tempRes = if (isSol) {
                                Tetra3AnalysisResult(
                                    analysisState = AnalysisState.SUCCESS, solved = true,
                                    raDeg = (kotlinMap["ra_deg"] as? Number)?.toDouble(),
                                    decDeg = (kotlinMap["dec_deg"] as? Number)?.toDouble(),
                                    rollDeg = (kotlinMap["roll_deg"] as? Number)?.toDouble(),
                                    fovDeg = (kotlinMap["fov_deg"] as? Number)?.toDouble(),
                                    errorArcsec = (kotlinMap["error_arcsec"] as? Number)?.toDouble(),
                                    rawSolutionStr = kotlinMap["raw_solution_str"]?.toString()
                                )
                            } else {
                                var errorMessage = kotlinMap["error_message"]?.toString()?.takeIf { it.isNotBlank() }
                                if (errorMessage == null) {
                                    val status = (kotlinMap["status"] as? Number)?.toInt()
                                    errorMessage = when(status) {
                                        2 -> "Tetra3: NO_MATCH"; 3 -> "Tetra3: TIMEOUT"
                                        4 -> "Tetra3: CANCELLED"; 5 -> "Tetra3: TOO_FEW_CENTROIDS"
                                        0 -> "Analysis failed (Solved=0 reported by Python)"
                                        else -> "Analysis failed (Unknown Python reason, status=$status)"
                                    }
                                }
                                Tetra3AnalysisResult(AnalysisState.FAILURE, solved = false, errorMessage = errorMessage, rawSolutionStr = kotlinMap["raw_solution_str"]?.toString())
                            }
                        } else {
                            tempRes = Tetra3AnalysisResult(AnalysisState.FAILURE, solved = false, errorMessage = "Failed to convert Python result to Kotlin Map")
                        }
                    } else {
                        tempRes = Tetra3AnalysisResult(AnalysisState.FAILURE, solved = false, errorMessage = "Python returned null object")
                    }
                } catch (e: Exception) {
                    val errorMsg = "Chaquopy/Kotlin Error: ${e.message} (Processing map: ${kotlinMap?.toString()?.take(100)})"
                    tempRes = Tetra3AnalysisResult(AnalysisState.FAILURE, solved = false, errorMessage = errorMsg)
                    Log.e("CameraXApp", "Python call or processing error for ${imageData.id}", e)
                } finally {
                    if (imageData.uri.scheme == "content" && imagePath != getPathFromUri(context, imageData.uri, false)) {
                        if(File(imagePath).exists()) File(imagePath).delete().also{ Log.d("CameraXApp", "Temp analysis file deleted: $imagePath - Success: $it") }
                    }
                }
            } else {
                tempRes = Tetra3AnalysisResult(AnalysisState.FAILURE, solved = false, errorMessage = "Image path not found for ${imageData.id}")
            }
            tempRes ?: Tetra3AnalysisResult(AnalysisState.FAILURE, solved = false, errorMessage = "Analysis failed unexpectedly")
        }
        Log.d("CameraXApp", "RA/Dec analysis finished for image ID: ${imageData.id}. Result state: ${analysisResult.analysisState}")
        return imageData.copy(tetra3Result = analysisResult)
    }

    suspend fun calculateLopForImage(context: Context, solvedImageData: ImageData): ImageData {
        Log.d("LOP_Flow", "Calculating LOP for image ID: ${solvedImageData.id}")
        if (solvedImageData.tetra3Result.raDeg == null ||
            solvedImageData.tetra3Result.decDeg == null ||
            solvedImageData.sensorAndOrientationData.phonePitchDeg == null) {
            Log.w("LOP_Flow", "Missing data for LOP calculation for image ID: ${solvedImageData.id}. RA/Dec/Pitch must be present.")
            return solvedImageData.copy(lopData = LineOfPositionData(
                errorMessage = "Missing RA/Dec or Pitch for LOP calculation."
            ))
        }

        val singleObsPy = listOf(
            mapOf(
                "utc" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(solvedImageData.sensorAndOrientationData.timestamp)),
                "ra_deg" to solvedImageData.tetra3Result.raDeg!!,
                "dec_deg" to solvedImageData.tetra3Result.decDeg!!,
                "phone_azimuth_deg" to solvedImageData.sensorAndOrientationData.phoneAzimuthDeg,
                "phone_pitch_deg" to solvedImageData.sensorAndOrientationData.phonePitchDeg,
                "phone_roll_deg" to solvedImageData.sensorAndOrientationData.phoneRollDeg
            )
        )

        val currentLat = estimatedLatitudeState
        val currentLon = estimatedLongitudeState
        val lonIsEastPositiveForSingleLop = true

        val lopResult: LineOfPositionData = withContext(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val navModule = py.getModule("astro_navigator")
                val resObj = navModule.callAttr("get_final_position", singleObsPy, currentLat, currentLon, lonIsEastPositiveForSingleLop)

                if (resObj != null) {
                    Log.d("LOP_Flow_Parse", "Python returned resObj for LOP. Attempting to parse.")
                    val map = resObj.asMap().mapKeys { it.key.toString() }
                    Log.d("LOP_Flow_Parse", "LOP: Converted to Kotlin map. Keys: ${map.keys.joinToString()}")

                    val pyError = map["error"]?.toString()?.takeIf { it.isNotBlank() && it.lowercase() != "none" }
                    if (pyError != null) {
                        Log.e("LOP_Flow", "LOP: Top-level Python error for ${solvedImageData.id}: $pyError")
                        return@withContext LineOfPositionData(errorMessage = "Python LOP Error: $pyError")
                    }

                    val processedObsPyObj = map["processed_observations_for_debug"]
                    val lopsDbgPyObjectList: List<PyObject>? = try {
                        if (processedObsPyObj != null) {
                            processedObsPyObj.asList() // Directly attempt to convert to List<PyObject>
                        } else {
                            Log.w("LOP_Flow_Parse", "LOP: processedObsPyObj from Python is null, cannot convert to list.")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e("LOP_Flow_Parse", "LOP: Error converting processedObsPyObj to List<PyObject>. It might not be a list type from Python. Error: $e")
                        null
                    }
                    Log.d("LOP_Flow_Parse", "LOP: lopsDbgPyObjectList (Kotlin List<PyObject>) is null: ${lopsDbgPyObjectList == null}, size: ${lopsDbgPyObjectList?.size}")

                    val lopsDbg = lopsDbgPyObjectList?.mapNotNull { pyObsMapObj ->
                        Log.d("LOP_Flow_Parse", "LOP: Processing an item from lopsDbgPyObjectList.")
                        try {
                            val pyObsMap = pyObsMapObj.asMap()
                            val kotlinObsMap = mutableMapOf<String, Any?>()
                            pyObsMap.forEach { (keyPyObj, valuePyObject) ->
                                val keyStr = keyPyObj?.toString()
                                if (keyStr != null) {
                                    val valuePyClassName = try {
                                        valuePyObject?.callAttr("__class__")?.callAttr("__name__")?.toString()
                                    } catch (e: Exception) {
                                        "ErrorGettingValuePyClass: ${e.message}".also { Log.w("LOP_Flow_Parse_Detail", "LOP: Could not get Python class name for valuePyObject: ${e.message}")}
                                        null
                                    }
                                    Log.d("LOP_Flow_Parse_Detail", "LOP: Item key: $keyStr, valuePyObject PyClass: $valuePyClassName, value: ${valuePyObject?.toString()?.take(100)}")
                                    if (keyStr == "lop_calculation_details" && valuePyObject != null && valuePyObject.toJava(Any::class.java) != null) {                                        kotlinObsMap[keyStr] = valuePyObject // Store as PyObject
                                    } else {
                                        kotlinObsMap[keyStr] = valuePyObject?.toJava(Any::class.java)
                                    }
                                } else {
                                    Log.w("LOP_Flow_Parse_Detail", "LOP: Encountered null key in pyObsMap.")
                                }
                            }
                            Log.d("LOP_Flow_Parse", "LOP: Successfully converted pyObsMapObj to kotlinObsMap with keys: ${kotlinObsMap.keys.joinToString()}")
                            kotlinObsMap as Map<String, Any?>
                        } catch (e: Exception) {
                            Log.e("LOP_Flow_Parse", "LOP: Error parsing LOP debug map item (pyObsMapObj to kotlinObsMap): $e \n PyObject item: ${pyObsMapObj?.toString()?.take(200)}")
                            null
                        }
                    }
                    Log.d("LOP_Flow_Parse", "LOP: Final lopsDbg (List<Map<String,Any?>>) is null: ${lopsDbg == null}, size: ${lopsDbg?.size}")


                    if (lopsDbg.isNullOrEmpty()) {
                        Log.e("LOP_Flow", "No LOP debug info LIST returned or PARSED from Python for ${solvedImageData.id}")
                        return@withContext LineOfPositionData(errorMessage = "No LOP debug info LIST from Python.")
                    }
                    val firstObsDebug = lopsDbg[0]
                    val lopDetailsRawValue = firstObsDebug["lop_calculation_details"]
                    val lopDetails: Map<String, Any?>? = when (lopDetailsRawValue) {
                        null -> {
                            Log.e("LOP_Flow", "LOP calculation_details (lopDetailsRawValue) is null for ${solvedImageData.id}.")
                            null
                        }
                        is PyObject -> { // Expecting this path now for lop_calculation_details
                            try {
                                lopDetailsRawValue.asMap().mapKeys { it.key.toString() }
                                    .mapValues { it.value?.toJava(Any::class.java) }
                            } catch (e: Exception) {
                                Log.e("LOP_Flow", "Error converting lop_calculation_details (PyObject) to Map for ${solvedImageData.id}: $e. Value: $lopDetailsRawValue")
                                null
                            }
                        }
                        is Map<*, *> -> { // Fallback if it somehow was already a Map
                            try {
                                @Suppress("UNCHECKED_CAST")
                                (lopDetailsRawValue as Map<Any?, Any?>).mapKeys { it.key.toString() }
                            } catch (e: Exception) {
                                Log.e("LOP_Flow", "Error casting/mapKeys on already-Map lop_calculation_details for ${solvedImageData.id}: $e. Value: $lopDetailsRawValue")
                                null
                            }
                        }
                        else -> {
                            Log.e("LOP_Flow", "LOP calculation_details has unexpected type: ${lopDetailsRawValue::class.java.name} for ${solvedImageData.id}. Value: $lopDetailsRawValue")
                            null
                        }
                    }

                    if (lopDetails == null) {
                        return@withContext LineOfPositionData(errorMessage = "LOP details parsing failed, missing, or wrong type for ${solvedImageData.id}.")
                    }

                    LineOfPositionData(
                        interceptNm = lopDetails["intercept_nm"]?.toString()?.toDoubleOrNull(),
                        azimuthDeg = lopDetails["azimuth_deg"]?.toString()?.toDoubleOrNull(),
                        estimatedHvDeg = lopDetails["estimated_hv_deg"]?.toString()?.toDoubleOrNull(),
                        poleAnglePDeg = lopDetails["pole_angle_p_deg"]?.toString()?.toDoubleOrNull(),
                        localHourAngleAHagDeg = lopDetails["local_hour_angle_ahag_deg"]?.toString()?.toDoubleOrNull(),
                        observedCameraHvDeg = firstObsDebug["observed_camera_hv_deg"]?.toString()?.toDoubleOrNull(),
                        errorMessage = lopDetails["error"]?.toString()?.takeIf { it.isNotBlank() && it.lowercase() != "none" }
                    )
                } else {
                    Log.e("LOP_Flow", "Python (get_final_position for LOP) returned null for ${solvedImageData.id}.")
                    LineOfPositionData(errorMessage = "Python returned null for LOP.")
                }
            } catch (e: Exception) {
                Log.e("LOP_Flow", "Kotlin/Chaquopy Error during LOP calculation for ${solvedImageData.id}", e)
                LineOfPositionData(errorMessage = "Kotlin/Chaquopy LOP Error: ${e.message}")
            }
        }
        Log.d("LOP_Flow", "LOP Calculation for image ID ${solvedImageData.id} finished. Intercept: ${lopResult.interceptNm}")
        return solvedImageData.copy(lopData = lopResult)
    }


    private fun createImageFile(context: Context, prefix: String): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = context.getExternalFilesDir(null)
        return File.createTempFile("${prefix}${timeStamp}_", ".jpg", storageDir)
            .apply { Log.d("CameraXApp", "Created image file at: $absolutePath") }
    }

    fun saveMetadataToFile(context: Context, imageData: ImageData, imageFile: File) {
        val metadataJson = JSONObject()
        try {
            metadataJson.put("sourceType", imageData.sourceType.name)
            metadataJson.put("imageUri", imageData.uri.toString())
            metadataJson.put("imageId", imageData.id)

            val sensorDataJson = JSONObject().apply {
                put("timestamp", imageData.sensorAndOrientationData.timestamp)
                imageData.sensorAndOrientationData.accelerometer?.let { put("accelerometer", it.joinToString(",")) }
                imageData.sensorAndOrientationData.gyroscope?.let { put("gyroscope", it.joinToString(",")) }
                imageData.sensorAndOrientationData.magnetometer?.let { put("magnetometer", it.joinToString(",")) }
                imageData.sensorAndOrientationData.phoneAzimuthDeg?.let { put("phoneAzimuthDeg", it) }
                imageData.sensorAndOrientationData.phonePitchDeg?.let { put("phonePitchDeg", it) }
                imageData.sensorAndOrientationData.phoneRollDeg?.let { put("phoneRollDeg", it) }
            }
            metadataJson.put("sensorAndOrientationData", sensorDataJson)

            val tetra3Json = JSONObject().apply {
                put("analysisState", imageData.tetra3Result.analysisState.name)
                put("solved", imageData.tetra3Result.solved)
                imageData.tetra3Result.raDeg?.let { put("raDeg", it) }
                imageData.tetra3Result.decDeg?.let { put("decDeg", it) }
                imageData.tetra3Result.rollDeg?.let { put("rollDeg", it) }
                imageData.tetra3Result.fovDeg?.let { put("fovDeg", it) }
                imageData.tetra3Result.errorArcsec?.let { put("errorArcsec", it) }
                imageData.tetra3Result.errorMessage?.let { put("errorMessage", it) }
                imageData.tetra3Result.rawSolutionStr?.let { put("rawSolutionStr", it) }
            }
            metadataJson.put("tetra3Result", tetra3Json)

            imageData.lopData?.let {
                val lopJson = JSONObject().apply {
                    it.interceptNm?.let { put("interceptNm", it) }
                    it.azimuthDeg?.let { put("azimuthDeg", it) }
                    it.estimatedHvDeg?.let { put("estimatedHvDeg", it) }
                    it.poleAnglePDeg?.let { put("poleAnglePDeg", it) }
                    it.localHourAngleAHagDeg?.let { put("localHourAngleAHagDeg", it) }
                    it.observedCameraHvDeg?.let { put("observedCameraHvDeg", it) }
                    it.errorMessage?.let { put("errorMessage", it) }
                }
                metadataJson.put("lopData", lopJson)
            }

            val imageFileName = imageFile.nameWithoutExtension
            val metadataFile = File(imageFile.parentFile, "$imageFileName.json")
            FileOutputStream(metadataFile).use { it.write(metadataJson.toString(4).toByteArray()) }
            Log.d("CameraXApp", "Metadata saved to: ${metadataFile.absolutePath}")

        } catch (e: JSONException) {
            Log.e("CameraXApp", "Error creating JSON for metadata for ${imageFile.name}", e)
        } catch (e: IOException) {
            Log.e("CameraXApp", "Error saving metadata file for ${imageFile.name}", e)
        }
    }
}

suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { future ->
        future.addListener({
            continuation.resume(future.get())
        }, ContextCompat.getMainExecutor(this))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraApp(
    getSensorAndOrientationData: () -> SensorAndOrientationData,
    initialLatitude: String,
    onLatitudeChange: (String) -> Unit,
    initialLongitude: String,
    onLongitudeChange: (String) -> Unit,
    initialAltitude: String,
    onAltitudeChange: (String) -> Unit,
    currentDeclination: Float,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainActivityInstance = context as? MainActivity

    var estimatedLatitudeString by remember { mutableStateOf(initialLatitude) }
    var estimatedLongitudeString by remember { mutableStateOf(initialLongitude) }
    var estimatedAltitudeString by remember { mutableStateOf(initialAltitude) }

    LaunchedEffect(initialLatitude) { estimatedLatitudeString = initialLatitude }
    LaunchedEffect(initialLongitude) { estimatedLongitudeString = initialLongitude }
    LaunchedEffect(initialAltitude) { estimatedAltitudeString = initialAltitude }


    val readStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hasReadStoragePermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, readStoragePermission) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasReadStoragePermission = granted }
    )

    val previewUseCase = remember { Preview.Builder().build() }
    val imageCaptureUseCase = remember { ImageCapture.Builder().build() }

    val capturedImages = remember { mutableStateListOf<ImageData>() }
    var selectedImageForMetadata by remember { mutableStateOf<ImageData?>(null) }
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    var fullScreenImageUri by remember { mutableStateOf<Uri?>(null) }

    var extensionsManager by remember { mutableStateOf<ExtensionsManager?>(null) }
    var isNightModeAvailable by remember { mutableStateOf(false) }
    var isNightModeEnabled by remember { mutableStateOf(false) }
    var activeCameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var cameraProviderHolder by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewSurfaceProvider by remember { mutableStateOf<Preview.SurfaceProvider?>(null) }

    var longitudeConventionIsEastPositive by remember { mutableStateOf(true) }
    var calculatedPositionResult by remember { mutableStateOf<CalculatedPosition?>(null) }
    var isCalculatingPosition by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            if (capturedImages.size < 3) {
                val currentSensorData = getSensorAndOrientationData()
                val initialImageData = ImageData(
                    uri = it, sensorAndOrientationData = currentSensorData,
                    sourceType = ImageSourceType.STORAGE,
                    tetra3Result = Tetra3AnalysisResult(analysisState = AnalysisState.PENDING)
                )
                capturedImages.add(initialImageData)
                selectedImageForMetadata = initialImageData
                scope.launch { scaffoldState.bottomSheetState.expand() }

                val job = scope.launch {
                    var analyzedImageData = mainActivityInstance?.analyzeImageForRaDec(context, initialImageData)
                    if (analyzedImageData != null) {
                        val indexInitial = capturedImages.indexOfFirst { img -> img.id == analyzedImageData.id }
                        if (indexInitial != -1) capturedImages[indexInitial] = analyzedImageData
                        selectedImageForMetadata = analyzedImageData

                        if (analyzedImageData.tetra3Result.analysisState == AnalysisState.SUCCESS && analyzedImageData.tetra3Result.solved) {
                            Log.d("LOP_Flow", "Image Picker: RA/Dec solved, proceeding to LOP for ${analyzedImageData.id}")
                            val imageDataWithLop = mainActivityInstance?.calculateLopForImage(context, analyzedImageData)
                            if (imageDataWithLop != null) {
                                val indexLop = capturedImages.indexOfFirst { img -> img.id == imageDataWithLop.id }
                                if (indexLop != -1) capturedImages[indexLop] = imageDataWithLop
                                selectedImageForMetadata = imageDataWithLop
                            } else {
                                Log.e("CameraApp", "Error: mainActivityInstance was null during LOP calculation for picked image.")
                                val errorLopData = analyzedImageData.copy(
                                    lopData = LineOfPositionData(
                                        errorMessage = "Internal error: Could not get LOP."
                                    )
                                )
                                val errorIndex = capturedImages.indexOfFirst { img -> img.id == errorLopData.id }
                                if(errorIndex != -1) capturedImages[errorIndex] = errorLopData
                                selectedImageForMetadata = errorLopData
                            }
                        }
                    }
                }
                mainActivityInstance?.analysisJobs?.set(initialImageData.id, job)
            } else {
                Toast.makeText(context, "Maximum 3 images allowed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val bindCameraUseCases: () -> Unit = {
        val provider = cameraProviderHolder
        val selector = activeCameraSelector
        val surfaceProvider = previewSurfaceProvider
        if (provider != null && surfaceProvider != null && hasCameraPermission) {
            scope.launch {
                try {
                    previewUseCase.setSurfaceProvider(surfaceProvider)
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, imageCaptureUseCase)
                } catch (exc: Exception) {
                    Log.e("CameraXApp", "Use case binding failed for $selector", exc)
                    if (selector != CameraSelector.DEFAULT_BACK_CAMERA) {
                        activeCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        isNightModeEnabled = false
                        provider.unbindAll()
                        try {
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, imageCaptureUseCase)
                        } catch (e: Exception) {
                            Log.e("CameraXApp", "Fallback camera binding also failed", e)
                        }
                    }
                    if(exc !is CameraUnavailableException && exc !is IllegalStateException) {
                        Toast.makeText(context, "Failed to bind camera: ${exc.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        if (!hasReadStoragePermission) storagePermissionLauncher.launch(readStoragePermission)
        cameraProviderHolder = context.getCameraProvider()
        cameraProviderHolder?.let { provider ->
            val future = ExtensionsManager.getInstanceAsync(context, provider)
            future.addListener({
                try {
                    extensionsManager = future.get()
                    isNightModeAvailable = extensionsManager?.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, ExtensionMode.NIGHT) ?: false
                } catch (e: Exception) { Log.e("CameraXApp", "Error getting ExtensionsManager", e) }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    LaunchedEffect(cameraProviderHolder, activeCameraSelector, previewSurfaceProvider, hasCameraPermission) {
        bindCameraUseCases()
    }

    LaunchedEffect(isNightModeEnabled, isNightModeAvailable, extensionsManager, cameraProviderHolder, hasCameraPermission) {
        if (cameraProviderHolder == null || extensionsManager == null || !hasCameraPermission) {
            if (isNightModeEnabled && !hasCameraPermission) isNightModeEnabled = false
            return@LaunchedEffect
        }
        val baseSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val targetSelector = if (isNightModeEnabled && isNightModeAvailable) {
            try {
                extensionsManager!!.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)
            } catch (e: Exception) {
                Log.e("CameraXApp", "Failed to get Night Mode selector", e)
                Toast.makeText(context, "Error enabling Night Mode.", Toast.LENGTH_SHORT).show()
                isNightModeEnabled = false
                baseSelector
            }
        } else {
            if (isNightModeEnabled && !isNightModeAvailable) {
                Toast.makeText(context, "Night mode not available on this device.", Toast.LENGTH_SHORT).show()
                isNightModeEnabled = false
            }
            baseSelector
        }
        if (targetSelector != activeCameraSelector) {
            activeCameraSelector = targetSelector
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 60.dp,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        sheetDragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .clickable(onClick = { scope.launch {
                        if (scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded) scaffoldState.bottomSheetState.partialExpand()
                        else scaffoldState.bottomSheetState.expand()
                    } }),
                contentAlignment = Alignment.Center
            ) { BottomSheetDefaults.DragHandle() }
        },
        sheetContent = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)
                    .heightIn(min = 200.dp, max = (LocalContext.current.resources.displayMetrics.heightPixels * 0.90).dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                AnimatedVisibility(visible = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded || capturedImages.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in 0 until 3) {
                            val imageData = capturedImages.getOrNull(i)
                            val job = imageData?.let { mainActivityInstance?.analysisJobs?.get(it.id) }
                            val isProcessing = job?.isActive == true ||
                                    (imageData?.tetra3Result?.analysisState == AnalysisState.PENDING) ||
                                    (imageData?.tetra3Result?.solved == true && imageData.lopData == null && job?.isCompleted == false && job?.isCancelled == false)


                            ImageSlotView(
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                imageData = imageData,
                                slotNumber = i + 1,
                                isSelected = imageData?.id == selectedImageForMetadata?.id,
                                isProcessing = isProcessing,
                                onClick = {
                                    if (i < capturedImages.size) {
                                        selectedImageForMetadata = capturedImages[i]
                                        scope.launch { if(scaffoldState.bottomSheetState.currentValue != SheetValue.Expanded) scaffoldState.bottomSheetState.expand() }
                                    }
                                },
                                onLongPress = { if (imageData != null) fullScreenImageUri = imageData.uri }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(visible = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded) {
                    Column {
                        AnimatedVisibility(visible = selectedImageForMetadata != null) {
                            selectedImageForMetadata?.let { imageData ->
                                Card(elevation = CardDefaults.cardElevation(2.dp), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom=16.dp)) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("Image Details (${imageData.sourceType.displayName})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                                        MetadataRow("Timestamp:", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(imageData.sensorAndOrientationData.timestamp)))
                                        val pitchUsedForLOP = imageData.sensorAndOrientationData.phonePitchDeg
                                        MetadataRow("Pitch (used for LOP):", pitchUsedForLOP?.format(2)?.plus("°") ?: "N/A")

                                        val result = imageData.tetra3Result
                                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                                        Text("Tetra3 Analysis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                                        when (result.analysisState) {
                                            AnalysisState.PENDING -> MetadataRow("Status:", "RA/Dec analysis pending...")
                                            AnalysisState.FAILURE -> {
                                                MetadataRow("Status:", "RA/Dec Analysis Failed")
                                                result.errorMessage?.let { MetadataRow("Error:", it) }
                                            }
                                            AnalysisState.SUCCESS -> {
                                                MetadataRow("Status:", if(result.solved) "RA/Dec Solved" else "RA/Dec Not Solved")
                                                result.raDeg?.let { MetadataRow("RA:", it.format(5) + "°") }
                                                result.decDeg?.let { MetadataRow("Dec:", it.format(5) + "°") }
                                                result.rollDeg?.let { MetadataRow("Roll (Img):", it.format(2) + "°") }
                                                result.fovDeg?.let { MetadataRow("FOV (Img):", it.format(2) + "°") }
                                                result.errorArcsec?.let { MetadataRow("Error (Img):", it.format(1) + " arcsec") }
                                                if(!result.solved && result.errorMessage == null) MetadataRow("Note:","Analysis process succeeded but no match found.")
                                            }
                                        }

                                        imageData.lopData?.let { lop ->
                                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                                            Text("LOP Calculation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                                            if (lop.errorMessage != null) { MetadataRow("LOP Error:", lop.errorMessage) }
                                            else {
                                                MetadataRow("Intercept:", lop.interceptNm?.format(1)?.plus(" NM") ?: "Pending/N/A")
                                                MetadataRow("Azimuth Est.:", lop.azimuthDeg?.format(1)?.plus("°") ?: "Pending/N/A")
                                                lop.observedCameraHvDeg?.let { MetadataRow("LOP Ho (from pitch):", it.format(2) + "°") }
                                                lop.estimatedHvDeg?.let { MetadataRow("Est. Hc (for LOP):", it.format(2) + "°") }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(onClick = {
                                            mainActivityInstance?.analysisJobs?.remove(selectedImageForMetadata?.id)?.cancel()
                                            capturedImages.remove(selectedImageForMetadata)
                                            selectedImageForMetadata = null
                                            Toast.makeText(context, "Image removed.", Toast.LENGTH_SHORT).show()
                                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                                            Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(8.dp)); Text("Remove Image")
                                        }
                                    }
                                }
                            }
                        }

                        val allThreeImagesHaveValidLop = capturedImages.size == 3 && capturedImages.all {
                            it.lopData != null && it.lopData.errorMessage == null && it.lopData.interceptNm != null && it.lopData.azimuthDeg != null
                        }
                        if (allThreeImagesHaveValidLop) {
                            Text("Position Calculation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                                RadioButton(selected = longitudeConventionIsEastPositive, onClick = { longitudeConventionIsEastPositive = true })
                                Text("Lon: East (+), West (-)", modifier = Modifier.clickable { longitudeConventionIsEastPositive = true }.padding(start=4.dp, end=16.dp))
                                RadioButton(selected = !longitudeConventionIsEastPositive, onClick = { longitudeConventionIsEastPositive = false })
                                Text("Lon: West (+), East (-)", modifier = Modifier.clickable { longitudeConventionIsEastPositive = false }.padding(start=4.dp))
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        isCalculatingPosition = true
                                        calculatedPositionResult = CalculatedPosition(null, null, null, "Calculating...", null)
                                        val estLatForFix = estimatedLatitudeString
                                        val estLonForFix = estimatedLongitudeString

                                        val obsPyForFix = capturedImages.mapNotNull { img ->
                                            if (img.tetra3Result.analysisState == AnalysisState.SUCCESS &&
                                                img.tetra3Result.solved &&
                                                img.sensorAndOrientationData.phonePitchDeg != null) {
                                                mapOf(
                                                    "utc" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(img.sensorAndOrientationData.timestamp)),
                                                    "ra_deg" to img.tetra3Result.raDeg!!,
                                                    "dec_deg" to img.tetra3Result.decDeg!!,
                                                    "phone_azimuth_deg" to img.sensorAndOrientationData.phoneAzimuthDeg,
                                                    "phone_pitch_deg" to img.sensorAndOrientationData.phonePitchDeg,
                                                    "phone_roll_deg" to img.sensorAndOrientationData.phoneRollDeg
                                                )
                                            } else { null }
                                        }

                                        if (obsPyForFix.size != 3) {
                                            calculatedPositionResult = CalculatedPosition(null, null, null, "Not all 3 images have valid data for fix.", null)
                                            isCalculatingPosition = false; return@launch
                                        }

                                        try {
                                            val py = Python.getInstance(); val navModule = py.getModule("astro_navigator")
                                            val resObj = navModule.callAttr("get_final_position", obsPyForFix, estLatForFix, estLonForFix, longitudeConventionIsEastPositive)
                                            if (resObj != null) {
                                                Log.d("AstroCalc_Parse", "3LOP: Python returned resObj. Attempting to parse.")
                                                val map = resObj.asMap().mapKeys { it.key.toString() }
                                                Log.d("AstroCalc_Parse", "3LOP: Converted to Kotlin map. Keys: ${map.keys.joinToString()}")

                                                val errPy = map["error"]?.toString()?.takeIf { it.isNotBlank() && it.lowercase() != "none" }
                                                val fLat = map["latitude_deg"]?.toString()?.toDoubleOrNull()
                                                val fLonW = map["longitude_deg"]?.toString()?.toDoubleOrNull()
                                                val spread = map["spread_nm"]?.toString()?.toDoubleOrNull()

                                                val processedObsPyObj = map["processed_observations_for_debug"]
                                                val pyClassName = try {
                                                    processedObsPyObj?.callAttr("__class__")?.callAttr("__name__")?.toString()
                                                } catch (e: Exception) {
                                                    "ErrorGettingPyClass: ${e.message}".also { Log.w("AstroCalc_Parse", "3LOP: Could not get Python class name for processedObsPyObj: ${e.message}") }
                                                    null
                                                }
                                                Log.d("AstroCalc_Parse", "3LOP: processedObsPyObj is null: ${processedObsPyObj == null}, PyClass: $pyClassName")

                                                val lopsDbgPyObjectList: List<PyObject>? = if (processedObsPyObj != null && pyClassName == "list") {
                                                    try {
                                                        processedObsPyObj.asList()
                                                    } catch (e: Exception) {
                                                        Log.e("AstroCalc_Parse", "3LOP: Error calling asList on processedObsPyObj. PyClass was '$pyClassName'. Error: $e");
                                                        null
                                                    }
                                                } else {
                                                    if (processedObsPyObj != null) {
                                                        Log.w("AstroCalc_Parse", "3LOP: processedObsPyObj was not a Python list, but a $pyClassName")
                                                    }
                                                    null
                                                }
                                                Log.d("AstroCalc_Parse", "3LOP: lopsDbgPyObjectList (Kotlin List<PyObject>) is null: ${lopsDbgPyObjectList == null}, size: ${lopsDbgPyObjectList?.size}")

                                                val lopsDbg = lopsDbgPyObjectList?.mapNotNull { pyObsMapObj ->
                                                    Log.d("AstroCalc_Parse", "3LOP: Processing an item from lopsDbgPyObjectList.")
                                                    try {
                                                        val pyObsMap = pyObsMapObj.asMap()
                                                        val kotlinObsMap = mutableMapOf<String, Any?>()
                                                        pyObsMap.forEach { (keyPyObj, valuePyObject) ->
                                                            val keyStr = keyPyObj?.toString()
                                                            if (keyStr != null) {
                                                                val valuePyClassName = try {
                                                                    valuePyObject?.callAttr("__class__")?.callAttr("__name__")?.toString()
                                                                } catch (e: Exception) {
                                                                    "ErrorGettingValuePyClass: ${e.message}".also { Log.w("AstroCalc_Parse_Detail", "3LOP: Could not get Python class name for valuePyObject: ${e.message}")}
                                                                    null
                                                                }
                                                                Log.d("AstroCalc_Parse_Detail", "3LOP: Item key: $keyStr, valuePyObject PyClass: $valuePyClassName, value: ${valuePyObject?.toString()?.take(100)}")
                                                                kotlinObsMap[keyStr] = valuePyObject?.toJava(Any::class.java)
                                                            } else {
                                                                Log.w("AstroCalc_Parse_Detail", "3LOP: Encountered null key in pyObsMap.")
                                                            }
                                                        }
                                                        Log.d("AstroCalc_Parse", "3LOP: Successfully converted pyObsMapObj to kotlinObsMap with keys: ${kotlinObsMap.keys.joinToString()}")
                                                        kotlinObsMap as Map<String, Any?>
                                                    } catch (e: Exception) {
                                                        Log.e("AstroCalc_Parse", "3LOP: Error parsing LOP debug map item: $e \n PyObject item: ${pyObsMapObj?.toString()?.take(200)}")
                                                        null
                                                    }
                                                }
                                                Log.d("AstroCalc_Parse", "3LOP: Final lopsDbg (List<Map<String,Any?>>) is null: ${lopsDbg == null}, size: ${lopsDbg?.size}")
                                                calculatedPositionResult = CalculatedPosition(fLat, fLonW, spread, errPy, lopsDbg)

                                                lopsDbg?.forEachIndexed { index, pyObsMap ->
                                                    if (index < capturedImages.size && pyObsMap != null) {
                                                        try {
                                                            val lopDetailsRaw = pyObsMap["lop_calculation_details"]
                                                            if (lopDetailsRaw != null) {
                                                                val lopDetails = (lopDetailsRaw as? Map<*,*>)?.mapKeys{it.key.toString()}
                                                                val updatedImg = capturedImages[index].copy(lopData = LineOfPositionData(
                                                                    interceptNm=lopDetails?.get("intercept_nm")?.toString()?.toDoubleOrNull(),
                                                                    azimuthDeg=lopDetails?.get("azimuth_deg")?.toString()?.toDoubleOrNull(),
                                                                    estimatedHvDeg = lopDetails?.get("estimated_hv_deg")?.toString()?.toDoubleOrNull(),
                                                                    poleAnglePDeg = lopDetails?.get("pole_angle_p_deg")?.toString()?.toDoubleOrNull(),
                                                                    localHourAngleAHagDeg = lopDetails?.get("local_hour_angle_ahag_deg")?.toString()?.toDoubleOrNull(),
                                                                    observedCameraHvDeg = pyObsMap["observed_camera_hv_deg"]?.toString()?.toDoubleOrNull(),
                                                                    errorMessage = lopDetails?.get("error")?.toString()?.takeIf { it.isNotBlank() && it.lowercase() != "none" }
                                                                ))
                                                                capturedImages[index] = updatedImg
                                                                if (updatedImg.sourceType == ImageSourceType.CAMERA && getPathFromUri(context, updatedImg.uri, false)?.let { File(it) }?.exists() == true) {
                                                                    mainActivityInstance?.saveMetadataToFile(context, updatedImg, File(getPathFromUri(context, updatedImg.uri, false)!!))
                                                                }
                                                            } else {
                                                                Log.e("AstroCalc", "3LOP: lop_calculation_details is null for image $index after fix. pyObsMap: $pyObsMap")
                                                            }
                                                        } catch (e: Exception) { Log.e("AstroCalc", "3LOP: Error updating LOP for image $index after fix", e)}
                                                    }
                                                }
                                                selectedImageForMetadata?.let { sel -> capturedImages.find { it.id == sel.id }?.let { selectedImageForMetadata = it } }
                                                Toast.makeText(context, if (errPy == null && fLat != null) "Position calculated!" else "Calculation error: ${errPy ?: "Missing data"}", Toast.LENGTH_LONG).show()
                                            } else { calculatedPositionResult = CalculatedPosition(null, null, null, "Python (get_final_position) returned null for fix.", null) }
                                        } catch (e: Exception) { Log.e("AstroCalc", "Python call error for get_final_position (fix)", e); calculatedPositionResult = CalculatedPosition(null, null, null, "Kotlin/Chaquopy Error: ${e.message}", null) }
                                        finally { isCalculatingPosition = false }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isCalculatingPosition && estimatedLatitudeString.isNotBlank() && estimatedLongitudeString.isNotBlank()
                            ) {
                                if (isCalculatingPosition) {
                                    CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text("Calculating Fix...")
                                } else {
                                    Text("Make Fix from 3 LOPs")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        calculatedPositionResult?.let { result ->
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Position Result", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (result.errorMessage != null) { Text("Error: ${result.errorMessage}", color = MaterialTheme.colorScheme.error) }
                                    else {
                                        val displayLon = if (longitudeConventionIsEastPositive && result.longitudeDeg != null) {
                                            -result.longitudeDeg
                                        } else {
                                            result.longitudeDeg
                                        }
                                        Text(String.format(Locale.US, "Latitude: %.5f°", result.latitudeDeg ?: Double.NaN))
                                        Text(String.format(Locale.US, "Longitude: %.5f° %s",
                                            normalizeAngle_plus_minus_180_double(displayLon ?: Double.NaN),
                                            if(longitudeConventionIsEastPositive) "(E+/W-)" else "(W+/E-)"
                                        ))
                                        result.spreadNm?.let { Text(String.format(Locale.US, "Spread: %.2f NM", it)) }
                                    }
                                }
                            }
                        }

                        if (capturedImages.size < 3) {
                            Button(onClick = {
                                if (hasReadStoragePermission) imagePickerLauncher.launch("image/*")
                                else storagePermissionLauncher.launch(readStoragePermission).also { Toast.makeText(context, "Storage permission required", Toast.LENGTH_SHORT).show() }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose Image from Storage")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (capturedImages.isNotEmpty()){
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                mainActivityInstance?.analysisJobs?.values?.forEach { it.cancel() }
                                mainActivityInstance?.analysisJobs?.clear()
                                capturedImages.clear(); selectedImageForMetadata = null; calculatedPositionResult = null
                                Toast.makeText(context, "All images removed.", Toast.LENGTH_LONG).show()
                            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                                Icon(Icons.Filled.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Remove All Images")
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    ) { scaffoldPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
            if (hasCameraPermission) {
                CameraPreviewView(Modifier.fillMaxSize()) { provider ->
                    if (previewSurfaceProvider != provider) previewSurfaceProvider = provider
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 16.dp, end = 16.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurface)
                }

                if (isNightModeAvailable) {
                    IconButton(
                        onClick = { isNightModeEnabled = !isNightModeEnabled },
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, start = 16.dp, end = 16.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(50))
                    ) {
                        Icon(
                            if (isNightModeEnabled) Icons.Filled.NightsStay else Icons.Filled.WbSunny,
                            "Toggle Night Mode",
                            tint = if (isNightModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                FloatingActionButton(
                    onClick = {
                        val activeJobCount = mainActivityInstance?.analysisJobs?.count { it.value.isActive } ?: 0
                        if (activeJobCount > 0) {
                            Toast.makeText(context, "Analysis in progress ($activeJobCount)...", Toast.LENGTH_SHORT).show()
                            return@FloatingActionButton
                        }
                        if (capturedImages.size < 3) {
                            val currentSensorData = getSensorAndOrientationData()
                            mainActivityInstance?.takePhoto(context, imageCaptureUseCase, currentSensorData, scope,
                                onImageCaptured = { imgData ->
                                    val index = capturedImages.indexOfFirst { it.id == imgData.id }
                                    if (index == -1) capturedImages.add(imgData)
                                    else capturedImages[index] = imgData
                                    selectedImageForMetadata = capturedImages.find { it.id == imgData.id } ?: capturedImages.lastOrNull()

                                    if (scaffoldState.bottomSheetState.currentValue != SheetValue.Expanded) {
                                        scope.launch { scaffoldState.bottomSheetState.expand() }
                                    }
                                },
                                onError = { exception ->
                                    Log.e("CameraXApp", "Photo capture error in FAB: ", exception)
                                    Toast.makeText(context, "Photo Error: ${exception.message}", Toast.LENGTH_LONG).show()
                                }
                            )
                        } else {
                            Toast.makeText(context, "Max 3 images.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Filled.CameraAlt, "Take Picture", tint = MaterialTheme.colorScheme.onPrimary) }

            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Camera Permission Required."); Spacer(Modifier.height(8.dp))
                    Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant Permission") }
                }
            }
        }
    }
    if (fullScreenImageUri != null) {
        FullScreenImageViewer(uri = fullScreenImageUri!!, onDismiss = { fullScreenImageUri = null })
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
    currentDeclination: Float,
    shipSpeed: String,
    onShipSpeedChange: (String) -> Unit,
    shipHeading: String,
    onShipHeadingChange: (String) -> Unit,
    currentPitchOffsetString: String,
    onNavigateBack: () -> Unit,
    calibrationStep: CalibrationStep,
    onCalibrationStepChange: (CalibrationStep) -> Unit,
    calibrationTimerValue: Int,
    calibrationMessage: String,
    startCalibrationPhase: (CalibrationStep) -> Unit,
    sensorAccuracyStatus: Int,
    rawPhonePitch: Double,
    calibrationDurationSec: Int,
    settlingDurationSec: Int
) {
    val currentPitchOffset = currentPitchOffsetString.toFloatOrNull() ?: 0f
    val calibratedPitchForDisplay = rawPhonePitch + currentPitchOffset

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Calibration") },
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
            OutlinedTextField(value = initialAltitude, onValueChange = onAltitudeChange, label = { Text("Estimated Altitude (meters, e.g., 20)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Calculated Magnetic Declination: ${currentDeclination.format(2)}°", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Vessel Information (Optional)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = shipSpeed, onValueChange = onShipSpeedChange, label = { Text("Ship's Speed (knots)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = shipHeading, onValueChange = onShipHeadingChange, label = { Text("Ship's Heading (degrees true)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(), singleLine = true)

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Sensor & Pitch Calibration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val accuracyColor = when (sensorAccuracyStatus) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> Color.Green.copy(alpha = 0.7f)
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Color(0xFFFFA500).copy(alpha = 0.7f)
                else -> Color.Red.copy(alpha = 0.7f)
            }
            val accuracyText = when (sensorAccuracyStatus) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
                else -> "Unreliable"
            }
            Text("Orientation Sensor Accuracy: $accuracyText", color = accuracyColor, fontWeight = FontWeight.Bold)
            if (sensorAccuracyStatus == SensorManager.SENSOR_STATUS_UNRELIABLE || sensorAccuracyStatus == SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                Text("Sensor accuracy is low. Move phone in a figure-eight pattern to improve.", color = Color.DarkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Current Calibrated Pitch (Camera): ${calibratedPitchForDisplay.format(1)}°", style = MaterialTheme.typography.bodyLarge)
            Text("Current Pitch Offset: ${currentPitchOffset.format(2)}°", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))

            when (calibrationStep) {
                CalibrationStep.Idle, CalibrationStep.Calibrated -> {
                    Button(onClick = { onCalibrationStepChange(CalibrationStep.Step1_Sky_Instruct) }) {
                        Text(if (calibrationStep == CalibrationStep.Calibrated) "Recalibrate Pitch" else "Start Pitch Calibration")
                    }
                    if (calibrationMessage.isNotEmpty()) {
                        Text(calibrationMessage, Modifier.padding(top = 8.dp), color = if (calibrationMessage.startsWith("Calibration failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                    }
                }
                CalibrationStep.Step1_Sky_Instruct -> {
                    Text(calibrationMessage.ifEmpty { "Point phone screen UP (towards the sky/zenith). Hold very still. Press Start." }, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
                    Button(onClick = { startCalibrationPhase(CalibrationStep.Step1_Sky_InProgress) }) { Text("Start Pointing Screen to Zenith (${calibrationDurationSec}s)") }
                }
                CalibrationStep.Step1_Sky_InProgress -> {
                    Text("$calibrationMessage\nTimer: $calibrationTimerValue s", textAlign = TextAlign.Center)
                    Text("Using data from last ${calibrationDurationSec - settlingDurationSec} seconds of this period.", fontSize = 10.sp, textAlign = TextAlign.Center)
                }
                CalibrationStep.Calibrating_Finalizing -> {
                    Text(calibrationMessage.ifEmpty { "Finalizing calibration..." }, textAlign = TextAlign.Center)
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun EmptyImageSlotView(modifier: Modifier = Modifier, slotNumber: Int) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)).clickable {},
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.Outlined.ImageNotSupported, "Empty Slot $slotNumber", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(4.dp))
            Text("Slot $slotNumber", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ImageSlotView(
    modifier: Modifier = Modifier,
    imageData: ImageData?,
    slotNumber: Int,
    isSelected: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val borderColor = when {
        isProcessing -> MaterialTheme.colorScheme.tertiary
        imageData?.tetra3Result?.analysisState == AnalysisState.SUCCESS && imageData.tetra3Result.solved -> {
            when {
                imageData.lopData?.errorMessage != null -> Color.Magenta
                imageData.lopData?.interceptNm != null -> Color.Cyan
                else -> Color.Yellow
            }
        }
        imageData?.tetra3Result?.analysisState == AnalysisState.FAILURE -> Color.Red
        imageData?.tetra3Result?.analysisState == AnalysisState.PENDING -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outline
    }
    val borderWidth = if (isSelected) 3.dp else 2.dp

    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .pointerInput(imageData) { detectTapGestures(onTap = { onClick() }, onLongPress = { if (imageData != null) onLongPress() }) }
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (imageData != null) {
            Image(painter = rememberAsyncImagePainter(model = imageData.uri), contentDescription = "Image $slotNumber (${imageData.sourceType.displayName})", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(
                text = imageData.sourceType.name.first().toString(), color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                fontSize = 10.sp
            )
            if (isProcessing) {
                CircularProgressIndicator(Modifier.size(32.dp).align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Outlined.ImageNotSupported, "Empty Slot $slotNumber", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(4.dp))
                Text("Slot $slotNumber", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium, lineHeight = 16.sp)
        Text(value, modifier = Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium, lineHeight = 16.sp)
    }
}

@Composable
fun CameraPreviewView(modifier: Modifier = Modifier, onSurfaceProviderReady: (Preview.SurfaceProvider) -> Unit) {
    AndroidView(
        factory = { context -> PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; onSurfaceProviderReady(surfaceProvider) } },
        modifier = modifier,
        update = { view -> onSurfaceProviderReady(view.surfaceProvider) }
    )
}

@Composable
fun FullScreenImageViewer(uri: Uri, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center
        ) {
            Image(painter = rememberAsyncImagePainter(model = uri), contentDescription = "Full screen image", modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 24.dp), contentScale = ContentScale.Fit)
        }
    }
}

fun Double.format(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)
fun Float.format(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)

// Helper function for normalizing longitude display in UI
fun normalizeAngle_plus_minus_180_double(degrees: Double): Double {
    var deg = degrees % 360.0
    if (deg > 180.0) {
        deg -= 360.0
    } else if (deg < -180.0) {
        deg += 360.0
    }
    return deg
}


fun getPathFromUri(context: Context, uri: Uri, forAnalysis: Boolean): String? {
    Log.d("getPathFromUri", "Path for URI: $uri, analysis: $forAnalysis")
    if ("file" == uri.scheme) {
        return uri.path?.also { Log.d("getPathFromUri", "File URI path: $it") }
    }
    if ("content" == uri.scheme && forAnalysis) {
        var tempFile: File? = null
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
            val originalName = uri.lastPathSegment?.substringAfterLast('/')?.replace("[^a-zA-Z0-9._-]".toRegex(), "_") ?: "image"
            val extension = ".jpg"
            val fileName = "analysis_temp_${timeStamp}_${originalName}$extension"

            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile = File(context.cacheDir, fileName)
                tempFile?.outputStream()?.use { output ->
                    input.copyTo(output)
                }
                Log.i("getPathFromUri", "Copied content URI to temp file for analysis: ${tempFile?.absolutePath}")
                return tempFile?.absolutePath
            } ?: run {
                Log.e("getPathFromUri", "Could not open InputStream for content URI: $uri")
                return null
            }
        } catch (e: Exception) {
            Log.e("getPathFromUri", "Error copying content URI '$uri' to temp file", e)
            tempFile?.delete()
            return null
        }
    }
    if ("content" == uri.scheme && !forAnalysis) {
        Log.d("getPathFromUri", "Content URI for display, not creating temp file: $uri")
        return null
    }

    Log.w("getPathFromUri", "Unsupported URI scheme or unable to get path: $uri")
    return null
}