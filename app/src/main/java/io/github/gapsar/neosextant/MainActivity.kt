package io.github.gapsar.neosextant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.gapsar.neosextant.model.*
import io.github.gapsar.neosextant.ui.theme.NeosextantTheme
import org.osmdroid.config.Configuration
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.sqrt


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var hasGyroscope: Boolean = false

    // Automation: Sensor Pipeline
    lateinit var sensorCalibrator: SensorCalibrator
    internal lateinit var sensorPipeline: SensorPipeline
    private lateinit var sensorFusion: AdaptiveSensorFusion
    private lateinit var rawSensorFusion: AdaptiveSensorFusion
    private lateinit var sensorDataLogger: SensorDataLogger
    private lateinit var vm: NavigationViewModel

    // State
    private val currentPhoneAltitudeDeg = mutableStateOf<Double?>(null)
    private val currentRawPhoneAltitudeDeg = mutableStateOf<Double?>(null)
    private val currentRawPhoneRollDeg = mutableStateOf<Double?>(null)
    val currentRawAccel = mutableStateOf(SensorCalibrator.Vec3(0f, 0f, 0f))

    // Gyroscope-gated rolling buffer for ship-motion-compensated averaging.
    // Continuously collects (altitude, gravity, gyroMag) tuples so that when
    // the shutter fires, we can compute a stability-weighted average that
    // favours readings from the quietest moments over the last ~3 seconds.
    private data class StabilityWeightedSample(
        val gravity: SensorCalibrator.Vec3,
        val gyroMagnitude: Float
    )
    private val rollingBuffer = java.util.ArrayDeque<StabilityWeightedSample>(ROLLING_BUFFER_SIZE)
    @Volatile private var currentGyroMagnitude: Float = 0f

    // Buffers for debugging/logging
    private var lastRawAccel = FloatArray(3)
    private var lastRawGyro = FloatArray(3)
    private var lastAndroidGrav = FloatArray(3)
    private var lastKalmanCal = FloatArray(3)
    private var lastKalmanRaw = FloatArray(3)
    private var lastUiUpdateTime = 0L

    // Wave Modeling
    private val waveModelBuffer = mutableListOf<FloatArray>()
    private var waveModelingJob: kotlinx.coroutines.Job? = null
    private val emaKalmanWaveCorrected = FloatArray(3)
    private val emaAndroidWaveCorrected = FloatArray(3)
    private var emaInitialized = false

    companion object {
        /** ~3 seconds at SENSOR_DELAY_GAME (~50 Hz). */
        private const val ROLLING_BUFFER_SIZE = 150
        /** Weighting steepness: 0.5 rad/s → weight ≈ 0.14, 1.0 rad/s → weight ≈ 0.0003. */
        private const val GYRO_WEIGHT_K = 8.0
    }

    // Permission for Notifications
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // Log result or handle denial
        }

    // Legacy averaging flag (kept for compatibility; the rolling buffer does the real work)
    @Volatile private var isAveragingPitch = false

    // H-04: Cached calibration offset to avoid SharedPreferences reads on sensor thread
    @Volatile var cachedCalibrationOffset: Double = 0.0
    @Volatile var cachedOneshotPitchOffset: Double = 0.0
    @Volatile var cachedOneshotRollOffset: Double = 0.0



    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                val showTutorial = shouldShowTutorial()
                val hasLang = LocaleManager.hasChosenLanguage(this)
                android.util.Log.e("Tutorial", "Camera granted, showTutorial=$showTutorial")
                setContent {
                    NeosextantTheme {
                        AppNavigator(
                            getCurrentPitch = ::getCurrentPitch,
                            getRawPitch = ::getRawPitch,
                            getRawRoll = ::getRawRoll,
                            startPitchAveraging = ::startPitchAveraging,
                            stopPitchAveraging = ::stopPitchAveraging,
                            saveCalibrationOffset = ::saveCalibrationOffset,
                            getCalibrationOffset = ::getCalibrationOffset,
                            getRollOffset = ::getRollOffset,
                            saveOneshotCalibrationOffset = ::saveOneshotCalibrationOffset,
                            getOneshotCalibrationOffset = ::getOneshotCalibrationOffset,
                            getOneshotRollOffset = ::getOneshotRollOffset,
                            sensorCalibrator = sensorCalibrator,
                            sensorPipeline = sensorPipeline,
                            rawAccelState = currentRawAccel,
                            supportsManualExposure = supportsManualExposure(),
                            markCalibrationUsed = sensorCalibrator::markCalibrationUsed,
                            markTutorialCompleted = ::markTutorialCompleted,
                            showTutorial = showTutorial,
                            hasChosenLanguage = hasLang,
                            startDebugRecording = ::startDebugRecording,
                            stopDebugRecording = ::stopDebugRecording,
                            startWaveModeling = ::startWaveModeling
                        )
                    }
                }
                // Check Calibration Status & Notify AFTER content is set
                checkCalibrationAndNotify()
            } else {
                // Handle permission denial
                android.util.Log.e("Tutorial", "Camera permission DENIED by user")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.e("Tutorial", "=== onCreate START ===")
        vm = androidx.lifecycle.ViewModelProvider(this)[NavigationViewModel::class.java]
        // OSMDroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        // S-03: Cap tile cache at 50 MB to prevent unbounded storage growth
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 50L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 40L * 1024 * 1024

        // S-04: Delete orphaned .jpg files from app storage root on startup
        // These are full-resolution captures from previous sessions that were never cleaned up
        cleanupOrphanedImages()
        
        // Ensure Chaquopy temp dir exists to prevent offline startup crash
        val chaquopyTmpDir = java.io.File(cacheDir, "chaquopy/tmp")
        if (!chaquopyTmpDir.exists()) {
            chaquopyTmpDir.mkdirs()
        }
        // Set TMPDIR so Python's tempfile module finds this directory
        try {
            android.system.Os.setenv("TMPDIR", chaquopyTmpDir.absolutePath, true)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to set TMPDIR env var", e)
        }
        
        if (!com.chaquo.python.Python.isStarted()) {
            com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this))
        }

        // Opportunistically synchronize internal clock with absolute time
        TimeSynchronizer.sync(this)

        // Initialize Automation Classes
        sensorCalibrator = SensorCalibrator(this)
        sensorPipeline = SensorPipeline(sensorCalibrator)
        sensorFusion = AdaptiveSensorFusion()
        rawSensorFusion = AdaptiveSensorFusion()
        sensorDataLogger = SensorDataLogger(this)
        val prefs = getPreferences(Context.MODE_PRIVATE)
        cachedCalibrationOffset = prefs.getFloat("pitch_offset", 0.0f).toDouble()
        cachedRollOffset = prefs.getFloat("roll_offset", 0.0f).toDouble() // H-04: Cache offset on startup
        cachedOneshotPitchOffset = prefs.getFloat("oneshot_pitch_offset", 0.0f).toDouble()
        cachedOneshotRollOffset = prefs.getFloat("oneshot_roll_offset", 0.0f).toDouble()

        setupSensors() // Setup sensors

        // Schedule Calibration Reminder (every 10 days)
        val calibrationRequest = PeriodicWorkRequestBuilder<CalibrationReminderWorker>(10, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "CalibrationReminder",
            ExistingPeriodicWorkPolicy.KEEP,
            calibrationRequest
        )

        if (allPermissionsGranted()) {
            val showTutorial = shouldShowTutorial()
            val hasLang = LocaleManager.hasChosenLanguage(this)
            android.util.Log.e("Tutorial", "showTutorial=$showTutorial")
            setContent {
                NeosextantTheme {
                    AppNavigator(
                        getCurrentPitch = ::getCurrentPitch,
                        getRawPitch = ::getRawPitch,
                        getRawRoll = ::getRawRoll,
                        startPitchAveraging = ::startPitchAveraging,
                        stopPitchAveraging = ::stopPitchAveraging,
                        saveCalibrationOffset = ::saveCalibrationOffset,
                        getCalibrationOffset = ::getCalibrationOffset,
                        getRollOffset = ::getRollOffset,
                        saveOneshotCalibrationOffset = ::saveOneshotCalibrationOffset,
                        getOneshotCalibrationOffset = ::getOneshotCalibrationOffset,
                        getOneshotRollOffset = ::getOneshotRollOffset,
                        sensorCalibrator = sensorCalibrator,
                        sensorPipeline = sensorPipeline,
                        rawAccelState = currentRawAccel,
                        supportsManualExposure = supportsManualExposure(),
                        markCalibrationUsed = sensorCalibrator::markCalibrationUsed,
                        markTutorialCompleted = ::markTutorialCompleted,
                        showTutorial = showTutorial,
                        hasChosenLanguage = hasLang,
                        startDebugRecording = ::startDebugRecording,
                        stopDebugRecording = ::stopDebugRecording,
                        startWaveModeling = ::startWaveModeling
                    )
                }
            }
            // Check Calibration Status & Notify AFTER content is set
            // (avoids race condition with camera permission request)
            checkCalibrationAndNotify()
        } else {
            android.util.Log.e("Tutorial", "Camera NOT granted, requesting")
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        hasGyroscope = gyroscope != null

        if (hasGyroscope) {
            Log.d("SensorSetup", "Gyroscope available — using adaptive complementary filter")
        } else {
            Log.w("SensorSetup", "No gyroscope — falling back to EMA-filtered accelerometer")
        }
        if (accelerometer == null) {
            Log.e("SensorSetup", "No accelerometer sensor available!")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun checkCalibrationAndNotify() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                 requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
             }
        }

        if (sensorCalibrator.checkCalibrationStatus() == SensorCalibrator.CalibrationStatus.NEEDS_CALIBRATION) {
             Log.w("Calibration", "Device needs sensor calibration!")
             // Send real system notification
             val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
             val locale = LocaleManager.getLocale(this)
             if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                  val channel = android.app.NotificationChannel(
                      CalibrationReminderWorker.CHANNEL_ID,
                      S.notifChannelName(locale),
                      android.app.NotificationManager.IMPORTANCE_DEFAULT
                  ).apply { description = S.notifChannelDesc(locale) }
                 notificationManager.createNotificationChannel(channel)
             }
             val intent = android.content.Intent(this, MainActivity::class.java).apply {
                 flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
             }
             val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
              val notification = androidx.core.app.NotificationCompat.Builder(this, CalibrationReminderWorker.CHANNEL_ID)
                  .setSmallIcon(android.R.drawable.ic_popup_sync)
                  .setContentTitle(S.notifTitle(locale))
                  .setContentText(S.notifText(locale))
                 .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                 .setContentIntent(pendingIntent)
                 .setAutoCancel(true)
                 .build()
             try {
                 notificationManager.notify(CalibrationReminderWorker.NOTIFICATION_ID, notification)
             } catch (e: SecurityException) {
                 Log.e("Calibration", "Cannot post notification: permission denied")
             }
        }
    }

    override fun onResume() {
        super.onResume()
        TimeSynchronizer.sync(this)
        sensorFusion.reset() // Fresh state on resume to avoid stale gyro predictions
        rawSensorFusion.reset()
        emaInitialized = false
        
        val rate = if (vm.isDebugRecording.value) SensorManager.SENSOR_DELAY_FASTEST else SensorManager.SENSOR_DELAY_GAME
        accelerometer?.also { sensorManager.registerListener(this, it, rate) }
        gyroscope?.also { sensorManager.registerListener(this, it, rate) }
        gravitySensor?.also { sensorManager.registerListener(this, it, rate) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    lastRawGyro[0] = it.values[0]
                    lastRawGyro[1] = it.values[1]
                    lastRawGyro[2] = it.values[2]
                    // Feed gyro data to fusion filter for gravity prediction
                    sensorFusion.onGyroscopeChanged(
                        it.values[0], it.values[1], it.values[2],
                        it.timestamp
                    )
                    rawSensorFusion.onGyroscopeChanged(
                        it.values[0], it.values[1], it.values[2],
                        it.timestamp
                    )
                    // Track instantaneous angular velocity magnitude for
                    // the stability-weighted rolling buffer.
                    currentGyroMagnitude = sqrt(
                        it.values[0] * it.values[0] +
                        it.values[1] * it.values[1] +
                        it.values[2] * it.values[2]
                    )
                }
                Sensor.TYPE_GRAVITY -> {
                    lastAndroidGrav[0] = it.values[0]
                    lastAndroidGrav[1] = it.values[1]
                    lastAndroidGrav[2] = it.values[2]
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    lastRawAccel[0] = it.values[0]
                    lastRawAccel[1] = it.values[1]
                    lastRawAccel[2] = it.values[2]
                    
                    val raw = SensorCalibrator.Vec3(it.values[0], it.values[1], it.values[2])
                    currentRawAccel.value = raw

                    // Step 1: Apply sphere-fit calibration (bias + scale correction)
                    val calibrated = sensorCalibrator.applyCalibration(raw)

                    // Step 2: Adaptive complementary filter → fused gravity unit vector
                    val fusedGravity = if (hasGyroscope) {
                        sensorFusion.onAccelerometerChanged(calibrated)
                    } else {
                        sensorFusion.onAccelerometerChangedNoGyro(calibrated)
                    }

                    val rawFusedGravity = if (hasGyroscope) {
                        rawSensorFusion.onAccelerometerChanged(raw)
                    } else {
                        rawSensorFusion.onAccelerometerChangedNoGyro(raw)
                    }

                    lastKalmanCal[0] = fusedGravity.x
                    lastKalmanCal[1] = fusedGravity.y
                    lastKalmanCal[2] = fusedGravity.z

                    lastKalmanRaw[0] = rawFusedGravity.x
                    lastKalmanRaw[1] = rawFusedGravity.y
                    lastKalmanRaw[2] = rawFusedGravity.z

                    // Step 3: Raw pitch (without horizon calibration offset)
                    val cameraVector = SensorCalibrator.Vec3(0f, 0f, -1f)
                    val rawDot = fusedGravity.x * cameraVector.x +
                                 fusedGravity.y * cameraVector.y +
                                 fusedGravity.z * cameraVector.z
                    val rawThetaDeg = Math.toDegrees(Math.acos(rawDot.coerceIn(-1f, 1f).toDouble()))
                    val rawAltitude = 90.0 - rawThetaDeg
                    currentRawPhoneAltitudeDeg.value = rawAltitude

                    val rawRollDeg = Math.toDegrees(Math.atan2(-fusedGravity.x.toDouble(), fusedGravity.y.toDouble()))
                    currentRawPhoneRollDeg.value = rawRollDeg

                    val offset = cachedCalibrationOffset
                    val rollOffset = cachedRollOffset
                    val offsetGravity = sensorPipeline.applyOffsets(fusedGravity, offset, rollOffset)
                    val calDot = offsetGravity.x * cameraVector.x +
                                 offsetGravity.y * cameraVector.y +
                                 offsetGravity.z * cameraVector.z
                    val calThetaDeg = Math.toDegrees(Math.acos(calDot.coerceIn(-1f, 1f).toDouble()))
                    val altitude = 90.0 - calThetaDeg

                    currentPhoneAltitudeDeg.value = altitude

                    // Always maintain the rolling buffer (even outside averaging)
                    // so that 3 seconds of history is available the instant the
                    // shutter fires.
                    synchronized(rollingBuffer) {
                        if (rollingBuffer.size >= ROLLING_BUFFER_SIZE) rollingBuffer.poll()
                        rollingBuffer.add(
                            StabilityWeightedSample(fusedGravity, currentGyroMagnitude)
                        )
                    }

                    // Apply EMA (Exponential Moving Average) for Real-Time Wave Correction
                    // tau = 15 seconds. dt ~ 0.02s at 50Hz. alpha = dt/tau = 0.00133
                    val alpha = 0.00133f
                    if (!emaInitialized) {
                        emaKalmanWaveCorrected[0] = lastKalmanCal[0]
                        emaKalmanWaveCorrected[1] = lastKalmanCal[1]
                        emaKalmanWaveCorrected[2] = lastKalmanCal[2]
                        emaAndroidWaveCorrected[0] = lastAndroidGrav[0]
                        emaAndroidWaveCorrected[1] = lastAndroidGrav[1]
                        emaAndroidWaveCorrected[2] = lastAndroidGrav[2]
                        emaInitialized = true
                    } else {
                        for (i in 0..2) {
                            emaKalmanWaveCorrected[i] = emaKalmanWaveCorrected[i] + alpha * (lastKalmanCal[i] - emaKalmanWaveCorrected[i])
                            emaAndroidWaveCorrected[i] = emaAndroidWaveCorrected[i] + alpha * (lastAndroidGrav[i] - emaAndroidWaveCorrected[i])
                        }
                    }

                    // LOGGING & UI UPDATE
                    val currentTs = System.currentTimeMillis()
                    if (sensorDataLogger.isRecording()) {
                        sensorDataLogger.logData(
                            timestamp = currentTs,
                            rawAccel = lastRawAccel,
                            rawGyro = lastRawGyro,
                            kalmanCal = lastKalmanCal,
                            kalmanRaw = lastKalmanRaw,
                            androidGrav = lastAndroidGrav,
                            kalmanWaveCorrected = emaKalmanWaveCorrected,
                            androidWaveCorrected = emaAndroidWaveCorrected
                        )
                    }
                    
                    if (vm.isWaveRecording.value) {
                        waveModelBuffer.add(lastKalmanCal.clone())
                    }

                    // Decimate UI updates to ~10 Hz (every 100ms)
                    if (currentTs - lastUiUpdateTime > 100) {
                        lastUiUpdateTime = currentTs
                        vm.debugRawAccel.value = String.format(java.util.Locale.US, "X: %+.4f, Y: %+.4f, Z: %+.4f", lastRawAccel[0], lastRawAccel[1], lastRawAccel[2])
                        vm.debugRawGyro.value = String.format(java.util.Locale.US, "X: %+.4f, Y: %+.4f, Z: %+.4f", lastRawGyro[0], lastRawGyro[1], lastRawGyro[2])
                        vm.debugKalmanCal.value = String.format(java.util.Locale.US, "X: %+.4f, Y: %+.4f, Z: %+.4f", lastKalmanCal[0], lastKalmanCal[1], lastKalmanCal[2])
                        vm.debugKalmanRaw.value = String.format(java.util.Locale.US, "X: %+.4f, Y: %+.4f, Z: %+.4f", lastKalmanRaw[0], lastKalmanRaw[1], lastKalmanRaw[2])
                        vm.debugAndroidGrav.value = String.format(java.util.Locale.US, "X: %+.4f, Y: %+.4f, Z: %+.4f", lastAndroidGrav[0], lastAndroidGrav[1], lastAndroidGrav[2])
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Can be used to handle changes in sensor accuracy if needed
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // Public method to get the current pitch
    fun getCurrentPitch(): Double? {
        return currentPhoneAltitudeDeg.value
    }

    // Public method to get the raw, uncalibrated pitch
    fun getRawPitch(): Double? {
        return currentRawPhoneAltitudeDeg.value
    }

    fun getRawRoll(): Double? {
        return currentRawPhoneRollDeg.value
    }

    fun startPitchAveraging() {
        isAveragingPitch = true
        Log.d("MainActivity", "Started pitch averaging (rolling buffer active)")
    }

    /**
     * Stops pitch averaging and returns the gyroscope-stability-weighted average
     * altitude and gravity vector from the rolling buffer.
     *
     * Each sample is weighted by  w = exp(-k × gyroMag²)  so readings taken
     * during stable moments (low angular velocity) dominate, and readings
     * during ship sway / hand tremor are suppressed.
     */
    fun stopPitchAveraging(): SensorCalibrator.Vec3? {
        isAveragingPitch = false

        val snapshot: List<StabilityWeightedSample>
        synchronized(rollingBuffer) {
            val allSamples = rollingBuffer.toList()
            // Use only the most recent ~500ms (25 samples at 50Hz) to match
            // the camera exposure window. The full 3-second buffer includes
            // readings from before the user settled on their target, which
            // dilute the gravity measurement with stale orientations.
            val GRAVITY_WINDOW_SAMPLES = 25
            snapshot = if (allSamples.size > GRAVITY_WINDOW_SAMPLES) {
                allSamples.takeLast(GRAVITY_WINDOW_SAMPLES)
            } else {
                allSamples
            }
        }

        if (snapshot.isEmpty()) {
            Log.d("MainActivity", "Stopped pitch averaging. Rolling buffer empty.")
            return null
        }

        var totalWeight = 0.0
        var weightedGx = 0.0
        var weightedGy = 0.0
        var weightedGz = 0.0

        for (sample in snapshot) {
            val w = exp(-GYRO_WEIGHT_K * sample.gyroMagnitude * sample.gyroMagnitude)
            totalWeight += w
            weightedGx += w * sample.gravity.x
            weightedGy += w * sample.gravity.y
            weightedGz += w * sample.gravity.z
        }

        if (totalWeight < 1e-9) {
            Log.w("MainActivity", "All rolling-buffer samples had near-zero weight (constant motion)")
            // Fall back to simple mean
            val avgG = SensorCalibrator.Vec3(
                snapshot.map { it.gravity.x.toDouble() }.average().toFloat(),
                snapshot.map { it.gravity.y.toDouble() }.average().toFloat(),
                snapshot.map { it.gravity.z.toDouble() }.average().toFloat()
            )
            Log.d("MainActivity", "Stopped pitch averaging. Fallback mean from ${snapshot.size} samples.")
            return avgG
        }

        val avgGravity = SensorCalibrator.Vec3(
            (weightedGx / totalWeight).toFloat(),
            (weightedGy / totalWeight).toFloat(),
            (weightedGz / totalWeight).toFloat()
        )

        Log.d("MainActivity", "Stopped pitch averaging. Gyro-gated avg from ${snapshot.size} samples, totalWeight=%.2f".format(totalWeight))
        return avgGravity
    }

    // Calibration Persistence
    private var cachedRollOffset: Double = 0.0

    fun saveCalibrationOffset(pitchOffset: Double, rollOffset: Double) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putFloat("pitch_offset", pitchOffset.toFloat())
            putFloat("roll_offset", rollOffset.toFloat())
            apply()
        }
        cachedCalibrationOffset = pitchOffset
        cachedRollOffset = rollOffset
    }

    fun getCalibrationOffset(): Double {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getFloat("pitch_offset", 0.0f).toDouble()
    }

    fun getRollOffset(): Double {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getFloat("roll_offset", 0.0f).toDouble()
    }

    fun saveOneshotCalibrationOffset(pitchOffset: Double, rollOffset: Double) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putFloat("oneshot_pitch_offset", pitchOffset.toFloat())
            putFloat("oneshot_roll_offset", rollOffset.toFloat())
            apply()
        }
        cachedOneshotPitchOffset = pitchOffset
        cachedOneshotRollOffset = rollOffset
    }

    fun getOneshotCalibrationOffset(): Double {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getFloat("oneshot_pitch_offset", 0.0f).toDouble()
    }

    fun getOneshotRollOffset(): Double {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getFloat("oneshot_roll_offset", 0.0f).toDouble()
    }

    // H-09: Check if the camera supports manual exposure control
    fun supportsManualExposure(): Boolean {
        return try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return false
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val modes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            modes?.contains(CaptureRequest.CONTROL_AE_MODE_OFF) == true
        } catch (e: Exception) {
            Log.w("MainActivity", "Cannot query manual exposure capability", e)
            false
        }
    }

    // Tutorial first-launch detection
    fun shouldShowTutorial(): Boolean {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return !sharedPref.getBoolean("tutorial_completed", false)
    }

    fun markTutorialCompleted() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putBoolean("tutorial_completed", true)
            apply()
        }
    }

    /**
     * S-04: Deletes orphaned .jpg files from the app's external files root.
     * These are full-resolution captures from previous sessions that were
     * never cleaned up (e.g., the app was killed before history save).
     * Files in subdirectories (history_images/, osmdroid/) are left untouched.
     */
    private fun cleanupOrphanedImages() {
        try {
            val appDir = getExternalFilesDir(null) ?: return
            val orphans = appDir.listFiles { file ->
                file.isFile && file.name.endsWith(".jpg", ignoreCase = true)
            }
            if (orphans != null && orphans.isNotEmpty()) {
                var totalBytes = 0L
                orphans.forEach { file ->
                    totalBytes += file.length()
                    file.delete()
                }
                val totalMB = totalBytes / (1024.0 * 1024.0)
                Log.d("StorageCleanup", "Deleted ${orphans.size} orphaned images (${String.format("%.1f", totalMB)} MB)")
            }
        } catch (e: Exception) {
            Log.w("StorageCleanup", "Failed to clean up orphaned images", e)
        }
    }

    fun startDebugRecording() {
        setSensorDelayRate(SensorManager.SENSOR_DELAY_FASTEST)
        val file = sensorDataLogger.startRecording()
        if (file != null) {
            vm.isDebugRecording.value = true
            vm.debugLogFilePath.value = file.absolutePath
        }
    }

    fun stopDebugRecording() {
        sensorDataLogger.stopRecording()
        vm.isDebugRecording.value = false
        setSensorDelayRate(SensorManager.SENSOR_DELAY_GAME)
    }

    fun startWaveModeling() {
        if (vm.isWaveRecording.value) return
        waveModelBuffer.clear()
        vm.isWaveRecording.value = true
        vm.waveRecordProgress.value = 0f
        vm.waveModelResult.value = null
        
        waveModelingJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val totalTime = 30_000L
            val interval = 100L
            var elapsed = 0L
            while (elapsed < totalTime) {
                kotlinx.coroutines.delay(interval)
                elapsed += interval
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    vm.waveRecordProgress.value = elapsed.toFloat() / totalTime.toFloat()
                }
            }
            
            val bufferCopy = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                vm.isWaveRecording.value = false
                waveModelBuffer.toList()
            }
            
            // Process the buffer
            val jsonArrayBuilder = StringBuilder("[")
            for (i in bufferCopy.indices) {
                val vec = bufferCopy[i]
                jsonArrayBuilder.append("[${vec[0]}, ${vec[1]}, ${vec[2]}]")
                if (i < bufferCopy.size - 1) jsonArrayBuilder.append(",")
            }
            jsonArrayBuilder.append("]")
            
            try {
                if (!com.chaquo.python.Python.isStarted()) {
                    com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this@MainActivity))
                }
                val py = com.chaquo.python.Python.getInstance()
                val module = py.getModule("celestial_navigator")
                val resultJson = module.callAttr("build_wave_model", jsonArrayBuilder.toString()).toString()
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    vm.waveModelResult.value = resultJson
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    vm.waveModelResult.value = "{\"success\": false, \"error\": \"${e.message}\"}"
                }
            }
        }
    }

    private fun setSensorDelayRate(delay: Int) {
        sensorManager.unregisterListener(this)
        accelerometer?.also { sensorManager.registerListener(this, it, delay) }
        gyroscope?.also { sensorManager.registerListener(this, it, delay) }
        gravitySensor?.also { sensorManager.registerListener(this, it, delay) }
    }
}
