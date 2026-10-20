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

    /**
     * The interval the camera was actually integrating photons, on the
     * SystemClock.elapsedRealtimeNanos clock — the same base the rolling buffer
     * is stamped with, so the two can be intersected directly.
     */
    data class ExposureWindow(val startNs: Long, val endNs: Long) {
        val midpointNs: Long get() = (startNs + endNs) / 2
    }

    // Gyroscope-gated rolling buffer for ship-motion-compensated averaging.
    // Continuously collects (gravity, gyroMag, timestamp) tuples so that when the
    // shutter fires we can weight the samples that actually overlap the exposure
    // by how still the phone was.
    private data class StabilityWeightedSample(
        val gravity: SensorCalibrator.Vec3,
        val gyroMagnitude: Float,
        /** elapsedRealtimeNanos at which this sample was produced. */
        val timestampNs: Long = 0L,
        // Raw-accelerometer gravity direction (Kalman-fused but NOT sphere-fit
        // corrected) and the raw |accel| magnitude at capture. Retained only so
        // the 1-Shot star calibration can fit a full affine model (A·a_raw + b);
        // the primary averaging path uses `gravity` exactly as before.
        val rawGravity: SensorCalibrator.Vec3 = SensorCalibrator.Vec3(0f, 0f, 0f),
        val rawMagnitude: Float = 0f
    )
    private val rollingBuffer = java.util.ArrayDeque<StabilityWeightedSample>(ROLLING_BUFFER_SIZE)
    @Volatile private var currentGyroMagnitude: Float = 0f

    // Buffers for debugging/logging
    private var lastRawAccel = FloatArray(3)
    private var lastRawGyro = FloatArray(3)
    private var lastGyroEventNs = 0L
    private var lastAndroidGrav = FloatArray(3)
    private var lastKalmanCal = FloatArray(3)
    private var lastKalmanRaw = FloatArray(3)
    private var lastUiUpdateTime = 0L

    // Wave Modeling
    // Rows of [time since the first sample (s), accel x, y, z]: the unfiltered
    // sphere-fit accelerometer, because the Kalman output hides part of the wave.
    private val waveModelBuffer = mutableListOf<DoubleArray>()
    private var waveModelStartNs = 0L
    private var waveModelingJob: kotlinx.coroutines.Job? = null
    private val emaKalmanWaveCorrected = FloatArray(3)
    private val emaAndroidWaveCorrected = FloatArray(3)
    private var emaInitialized = false

    companion object {
        /** ~3 seconds at SENSOR_DELAY_GAME (~50 Hz). */
        private const val ROLLING_BUFFER_SIZE = 150

        /** Fallback window (~500 ms at 50 Hz) when no exposure window is known. */
        private const val GRAVITY_WINDOW_SAMPLES = 25

        /** Below this many in-exposure samples, widen about the midpoint instead. */
        private const val MIN_EXPOSURE_WINDOW_SAMPLES = 5
        /** Weighting steepness: 0.5 rad/s → weight ≈ 0.14, 1.0 rad/s → weight ≈ 0.0003. */
        private const val GYRO_WEIGHT_K = 8.0

        /** An event older than this on arrival is not on the elapsedRealtime base. */
        private const val MAX_SENSOR_EVENT_AGE_NS = 1_000_000_000L
    }

    // Permission for Notifications
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // Log result or handle denial
        }

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
                            snapshotRawGravity = ::snapshotRawGravityForCalibration,
                            getOneshotAffineUp = ::getOneshotAffineUp,
                            shotSensorRecord = ::shotSensorRecord,
                            sensorModelJson = ::sensorModelJson,
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
        // These are full-resolution captures from previous sessions that were never cleaned up.
        // Fresh launches only: on a recreation (rotation, dark mode, locale) the retained
        // ViewModel still references this session's captures, some still being analysed.
        if (savedInstanceState == null) {
            cleanupOrphanedImages()
        }
        
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
                        snapshotRawGravity = ::snapshotRawGravityForCalibration,
                        getOneshotAffineUp = ::getOneshotAffineUp,
                            shotSensorRecord = ::shotSensorRecord,
                            sensorModelJson = ::sensorModelJson,
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
                    lastGyroEventNs = sampleTimestampNs(it.timestamp)
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
                    val rawAccelMag = sqrt(
                        lastRawAccel[0] * lastRawAccel[0] +
                        lastRawAccel[1] * lastRawAccel[1] +
                        lastRawAccel[2] * lastRawAccel[2]
                    )
                    synchronized(rollingBuffer) {
                        if (rollingBuffer.size >= ROLLING_BUFFER_SIZE) rollingBuffer.poll()
                        rollingBuffer.add(
                            StabilityWeightedSample(
                                fusedGravity,
                                currentGyroMagnitude,
                                sampleTimestampNs(it.timestamp),
                                rawFusedGravity,
                                rawAccelMag
                            )
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
                            eventTimeNs = sampleTimestampNs(it.timestamp),
                            gyroEventTimeNs = lastGyroEventNs,
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
                        val tNs = sampleTimestampNs(it.timestamp)
                        if (waveModelBuffer.isEmpty()) waveModelStartNs = tNs
                        waveModelBuffer.add(doubleArrayOf(
                            (tNs - waveModelStartNs) / 1e9,
                            calibrated.x.toDouble(), calibrated.y.toDouble(), calibrated.z.toDouble()
                        ))
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
        // The rolling buffer is maintained unconditionally in onSensorChanged, so
        // there is nothing to switch on. Kept as the capture-path hook, and as the
        // place to put anything that must happen before the shutter.
        Log.d("MainActivity", "Pitch averaging window opened (rolling buffer is always active)")
    }

    /**
     * Stops pitch averaging and returns the gyroscope-stability-weighted average
     * altitude and gravity vector from the rolling buffer.
     *
     * Each sample is weighted by  w = exp(-k × gyroMag²)  so readings taken
     * during stable moments (low angular velocity) dominate, and readings
     * during ship sway / hand tremor are suppressed.
     */
    fun stopPitchAveraging(exposure: ExposureWindow? = null): SensorCalibrator.Vec3? {
        val snapshot = selectWindow(exposure) ?: return null
        val agg = aggregate(snapshot) ?: return null
        // Lag is how stale the old "most recent samples" window would have been:
        // the gap between the exposure midpoint and the moment this runs, which
        // is the JPEG encode + file write tail. Logged because it is the size of
        // the error this window selection removes, and it is device-specific.
        val lagMs = exposure?.let {
            (android.os.SystemClock.elapsedRealtimeNanos() - it.midpointNs) / 1e6
        }
        Log.d("MainActivity",
            "Gravity from ${snapshot.size} samples over ${describe(exposure)}, weight=%.2f%s"
                .format(agg.totalWeight,
                    lagMs?.let { ", exposure ended %.0f ms ago".format(it) } ?: ""))
        return agg.calibrated
    }

    /** Raw (non-sphere-fit) gravity snapshot used only by affine 1-Shot calibration. */
    data class RawGravitySnapshot(
        val direction: SensorCalibrator.Vec3, // unit vector, raw-accel Kalman-fused
        val magnitude: Double                 // weighted mean of raw |accel| (m/s²)
    )

    /**
     * Companion to [stopPitchAveraging] for the affine 1-Shot calibration only.
     * Same window, same gyro weighting, but over the RAW accelerometer
     * direction/magnitude. Read-only; safe to call right after
     * stopPitchAveraging() for the same shot.
     */
    fun snapshotRawGravityForCalibration(exposure: ExposureWindow? = null): RawGravitySnapshot? {
        val snapshot = selectWindow(exposure) ?: return null
        val agg = aggregate(snapshot) ?: return null
        val d = agg.raw
        val dMag = d.magnitude()
        if (dMag <= 1e-6f) return null
        return RawGravitySnapshot(
            SensorCalibrator.Vec3(d.x / dMag, d.y / dMag, d.z / dMag),
            agg.rawMagnitude
        )
    }

    /**
     * The sensor inputs of one shot, for the shot record: the same window and
     * weighting as [stopPitchAveraging], plus the unweighted raw mean and the
     * battery temperature. The caller fills in the gravity path.
     */
    fun shotSensorRecord(exposure: ExposureWindow? = null): ShotSensorRecord? {
        val snapshot = selectWindow(exposure) ?: return null
        val agg = aggregate(snapshot) ?: return null
        fun unit(v: SensorCalibrator.Vec3): SensorCalibrator.Vec3 {
            val m = v.magnitude()
            return if (m > 1e-6f) SensorCalibrator.Vec3(v.x / m, v.y / m, v.z / m) else v
        }
        val unweighted = SensorCalibrator.Vec3(
            snapshot.map { it.rawGravity.x.toDouble() }.average().toFloat(),
            snapshot.map { it.rawGravity.y.toDouble() }.average().toFloat(),
            snapshot.map { it.rawGravity.z.toDouble() }.average().toFloat()
        )
        return ShotSensorRecord(
            exposureStartNs = exposure?.startNs,
            exposureEndNs = exposure?.endNs,
            sampleCount = snapshot.size,
            gyroWeight = agg.totalWeight,
            rawDirection = unit(agg.raw),
            rawMagnitude = agg.rawMagnitude,
            rawDirectionUnweighted = unit(unweighted),
            calibratedGravity = agg.calibrated,
            batteryTempC = SensorDataLogger.readBatteryTemperatureC(this)
        )
    }

    /** The calibration in use now, as JSON, for the shot record. */
    fun sensorModelJson(): org.json.JSONObject {
        val affine = sensorCalibrator.getAffineParams()
        val sphere = sensorCalibrator.getParams()
        fun vec(v: SensorCalibrator.Vec3) = org.json.JSONArray(
            listOf(v.x.toDouble(), v.y.toDouble(), v.z.toDouble()))
        return org.json.JSONObject()
            .put("affine_valid", affine.valid)
            .put("affine_a", org.json.JSONArray(affine.a.map { it.toDouble() }))
            .put("affine_b", vec(affine.b))
            .put("sphere_bias", vec(sphere.bias))
            .put("sphere_scale", vec(sphere.scale))
            .put("oneshot_pitch_offset_deg", getOneshotCalibrationOffset())
            .put("oneshot_roll_offset_deg", getOneshotRollOffset())
            .put("horizon_pitch_offset_deg", getCalibrationOffset())
            .put("horizon_roll_offset_deg", getRollOffset())
    }

    /**
     * Picks the rolling-buffer samples that actually overlap the exposure.
     *
     * Previously both callers took "the last 25 samples at the moment of the
     * call", but that call happens once the JPEG has been encoded and written —
     * several hundred ms AFTER the photons landed. The averaging window could
     * therefore miss the exposure entirely. For a hand-held phone drifting even
     * 0.5 deg/s that is a systematic attitude error in whichever direction the
     * user's arms are tiring, and 1 deg of attitude is 60 nmi of position.
     *
     * With a real shutter timestamp we select [start, end] instead. Samples are
     * timestamped on the same SystemClock.elapsedRealtimeNanos base as the
     * shutter callback. Falls back to the old "most recent" behaviour when no
     * window is available or the window caught too few samples (very short
     * exposures can span fewer than a couple of sensor ticks).
     */
    private fun selectWindow(exposure: ExposureWindow?): List<StabilityWeightedSample>? {
        val all: List<StabilityWeightedSample>
        synchronized(rollingBuffer) { all = rollingBuffer.toList() }
        if (all.isEmpty()) {
            Log.d("MainActivity", "Rolling buffer empty.")
            return null
        }
        if (exposure != null) {
            val inWindow = all.filter {
                it.timestampNs in exposure.startNs..exposure.endNs
            }
            if (inWindow.size >= MIN_EXPOSURE_WINDOW_SAMPLES) return inWindow
            // Too few in-window samples: widen symmetrically about the exposure
            // midpoint rather than silently reverting to a stale window.
            val mid = (exposure.startNs + exposure.endNs) / 2
            val widened = all.sortedBy { kotlin.math.abs(it.timestampNs - mid) }
                .take(GRAVITY_WINDOW_SAMPLES)
            if (widened.isNotEmpty()) {
                Log.d("MainActivity",
                    "Only ${inWindow.size} samples inside exposure; widened to ${widened.size} " +
                        "nearest the midpoint")
                return widened
            }
        }
        return if (all.size > GRAVITY_WINDOW_SAMPLES) all.takeLast(GRAVITY_WINDOW_SAMPLES) else all
    }

    private class Aggregate(
        val calibrated: SensorCalibrator.Vec3,
        val raw: SensorCalibrator.Vec3,
        val rawMagnitude: Double,
        val totalWeight: Double
    )

    /**
     * Gyro-stability-weighted mean over a set of samples: w = exp(-k*|omega|^2),
     * so readings from the quietest instants dominate and ship sway / hand
     * tremor is suppressed. Falls back to an unweighted mean under constant
     * motion, where every weight underflows.
     */
    private fun aggregate(samples: List<StabilityWeightedSample>): Aggregate? {
        if (samples.isEmpty()) return null
        var tw = 0.0
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        var rx = 0.0; var ry = 0.0; var rz = 0.0; var rm = 0.0
        for (s in samples) {
            val w = exp(-GYRO_WEIGHT_K * s.gyroMagnitude * s.gyroMagnitude)
            tw += w
            cx += w * s.gravity.x; cy += w * s.gravity.y; cz += w * s.gravity.z
            rx += w * s.rawGravity.x; ry += w * s.rawGravity.y; rz += w * s.rawGravity.z
            rm += w * s.rawMagnitude
        }
        if (tw < 1e-9) {
            Log.w("MainActivity", "All samples had near-zero gyro weight (constant motion)")
            return Aggregate(
                SensorCalibrator.Vec3(
                    samples.map { it.gravity.x.toDouble() }.average().toFloat(),
                    samples.map { it.gravity.y.toDouble() }.average().toFloat(),
                    samples.map { it.gravity.z.toDouble() }.average().toFloat()
                ),
                SensorCalibrator.Vec3(
                    samples.map { it.rawGravity.x.toDouble() }.average().toFloat(),
                    samples.map { it.rawGravity.y.toDouble() }.average().toFloat(),
                    samples.map { it.rawGravity.z.toDouble() }.average().toFloat()
                ),
                samples.map { it.rawMagnitude.toDouble() }.average(),
                0.0
            )
        }
        return Aggregate(
            SensorCalibrator.Vec3((cx / tw).toFloat(), (cy / tw).toFloat(), (cz / tw).toFloat()),
            SensorCalibrator.Vec3((rx / tw).toFloat(), (ry / tw).toFloat(), (rz / tw).toFloat()),
            rm / tw, tw
        )
    }

    @Volatile private var warnedSensorClock = false

    /**
     * When a sensor sample was actually taken, on the elapsedRealtimeNanos base
     * the camera's exposure window uses. Delivery trails the sample by tens of
     * ms, more when the HAL batches, which would shift the gravity average off
     * the exposure. A few HALs stamp events on another clock; for those the
     * arrival time is the best available.
     */
    private fun sampleTimestampNs(eventTimestampNs: Long): Long {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val age = now - eventTimestampNs
        if (age in 0..MAX_SENSOR_EVENT_AGE_NS) return eventTimestampNs
        if (!warnedSensorClock) {
            warnedSensorClock = true
            Log.w("MainActivity", "Sensor timestamps are not on the elapsedRealtime clock " +
                "(age ${age / 1_000_000} ms); using arrival time")
        }
        return now
    }

    private fun describe(e: ExposureWindow?): String =
        if (e == null) "most recent samples"
        else "exposure window (%.0f ms)".format((e.endNs - e.startNs) / 1e6)

    /**
     * Affine-corrected "up" for the current 1-Shot shot, or null if no validated
     * affine calibration exists or the result fails its runtime sanity checks
     * (caller then uses the legacy offset path).
     * Reconstructs the true raw acceleration a = mag · direction and applies
     * up = normalize(A · a + b). Call right after stopPitchAveraging().
     */
    fun getOneshotAffineUp(exposure: ExposureWindow? = null): SensorCalibrator.Vec3? {
        if (!sensorCalibrator.hasValidAffine()) return null
        val snap = snapshotRawGravityForCalibration(exposure) ?: return null
        val aRaw = SensorCalibrator.Vec3(
            (snap.direction.x * snap.magnitude).toFloat(),
            (snap.direction.y * snap.magnitude).toFloat(),
            (snap.direction.z * snap.magnitude).toFloat()
        )
        val affineUp = sensorCalibrator.applyAffine(aRaw) ?: return null

        // The affine model is applied to EVERY subsequent fix once adopted, at
        // attitudes the calibration session may never have covered. It should
        // improve on the legacy path by a degree or two, not disagree wildly —
        // a large gap means the model is broken, so fall back rather than
        // silently steer the fix tens of miles off.
        val disagreement = sensorCalibrator.affineVsLegacyDeg(aRaw)
        if (disagreement != null &&
            disagreement > SensorCalibrator.MAX_AFFINE_LEGACY_DISAGREEMENT_DEG) {
            Log.w("MainActivity",
                "Affine disagrees with legacy by %.1f° (> %.1f°); using legacy path"
                    .format(disagreement, SensorCalibrator.MAX_AFFINE_LEGACY_DISAGREEMENT_DEG))
            return null
        }
        return affineUp
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
                jsonArrayBuilder.append("[${vec[0]}, ${vec[1]}, ${vec[2]}, ${vec[3]}]")
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
