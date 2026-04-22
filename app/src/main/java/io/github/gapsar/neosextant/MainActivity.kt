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
import io.github.gapsar.neosextant.model.*
import io.github.gapsar.neosextant.ui.theme.NeosextantTheme
import org.osmdroid.config.Configuration
import java.util.concurrent.TimeUnit


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var hasGyroscope: Boolean = false

    // Automation: Sensor Pipeline
    lateinit var sensorCalibrator: SensorCalibrator
    internal lateinit var sensorPipeline: SensorPipeline
    private lateinit var sensorFusion: AdaptiveSensorFusion

    // State
    private val currentPhoneAltitudeDeg = mutableStateOf<Double?>(null)
    private val currentRawPhoneAltitudeDeg = mutableStateOf<Double?>(null)
    val currentRawAccel = mutableStateOf(SensorCalibrator.Vec3(0f, 0f, 0f))

    // Permission for Notifications
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // Log result or handle denial
        }

    // Averaging logic (synchronized for thread safety between sensor callback and coroutine)
    private val sensorReadings: MutableList<Pair<Double, SensorCalibrator.Vec3>> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile private var isAveragingPitch = false

    // H-04: Cached calibration offset to avoid SharedPreferences reads on sensor thread
    @Volatile var cachedCalibrationOffset: Double = 0.0



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
                            startPitchAveraging = ::startPitchAveraging,
                            stopPitchAveraging = ::stopPitchAveraging,
                            saveCalibrationOffset = ::saveCalibrationOffset,
                            getCalibrationOffset = ::getCalibrationOffset,
                            sensorCalibrator = sensorCalibrator,
                            sensorPipeline = sensorPipeline,
                            rawAccelState = currentRawAccel,
                            supportsManualExposure = supportsManualExposure(),
                            markCalibrationUsed = sensorCalibrator::markCalibrationUsed,
                            markTutorialCompleted = ::markTutorialCompleted,
                            showTutorial = showTutorial,
                            hasChosenLanguage = hasLang
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
        // OSMDroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
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
        cachedCalibrationOffset = getCalibrationOffset() // H-04: Cache offset on startup

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
                        startPitchAveraging = ::startPitchAveraging,
                        stopPitchAveraging = ::stopPitchAveraging,
                        saveCalibrationOffset = ::saveCalibrationOffset,
                        getCalibrationOffset = ::getCalibrationOffset,
                        sensorCalibrator = sensorCalibrator,
                        sensorPipeline = sensorPipeline,
                        rawAccelState = currentRawAccel,
                        supportsManualExposure = supportsManualExposure(),
                        markCalibrationUsed = sensorCalibrator::markCalibrationUsed,
                        markTutorialCompleted = ::markTutorialCompleted,
                        showTutorial = showTutorial,
                        hasChosenLanguage = hasLang
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
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    // Feed gyro data to fusion filter for gravity prediction
                    sensorFusion.onGyroscopeChanged(
                        it.values[0], it.values[1], it.values[2],
                        it.timestamp
                    )
                }
                Sensor.TYPE_ACCELEROMETER -> {
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

                    // Step 3: Raw pitch (without horizon calibration offset)
                    val cameraVector = SensorCalibrator.Vec3(0f, 0f, -1f)
                    val rawDot = fusedGravity.x * cameraVector.x +
                                 fusedGravity.y * cameraVector.y +
                                 fusedGravity.z * cameraVector.z
                    val rawThetaDeg = Math.toDegrees(Math.acos(rawDot.coerceIn(-1f, 1f).toDouble()))
                    val rawAltitude = 90.0 - rawThetaDeg
                    currentRawPhoneAltitudeDeg.value = rawAltitude

                    // Step 4: Calibrated pitch (with horizon offset applied)
                    val offset = cachedCalibrationOffset
                    val offsetGravity = sensorPipeline.applyPitchOffset(fusedGravity, offset)
                    val calDot = offsetGravity.x * cameraVector.x +
                                 offsetGravity.y * cameraVector.y +
                                 offsetGravity.z * cameraVector.z
                    val calThetaDeg = Math.toDegrees(Math.acos(calDot.coerceIn(-1f, 1f).toDouble()))
                    val altitude = 90.0 - calThetaDeg

                    currentPhoneAltitudeDeg.value = altitude

                    if (isAveragingPitch) {
                        synchronized(sensorReadings) { sensorReadings.add(Pair(altitude, offsetGravity)) }
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

    // H-03: Fix race condition — set flag inside synchronized block
    fun startPitchAveraging() {
        synchronized(sensorReadings) {
            sensorReadings.clear()
            isAveragingPitch = true
        }
        Log.d("MainActivity", "Started pitch averaging")
    }

    fun stopPitchAveraging(): Pair<Double?, SensorCalibrator.Vec3?> {
        isAveragingPitch = false
        var count = 0
        val result = synchronized(sensorReadings) {
            count = sensorReadings.size
            if (sensorReadings.isNotEmpty()) {
                val avgAlt = sensorReadings.map { it.first }.average()
                val avgX = sensorReadings.map { it.second.x }.average().toFloat()
                val avgY = sensorReadings.map { it.second.y }.average().toFloat()
                val avgZ = sensorReadings.map { it.second.z }.average().toFloat()
                Pair(avgAlt, SensorCalibrator.Vec3(avgX, avgY, avgZ))
            } else {
                Pair(currentPhoneAltitudeDeg.value, null)
            }
        }
        Log.d("MainActivity", "Stopped pitch averaging. Count: $count")
        return result
    }

    // Calibration Persistence
    fun saveCalibrationOffset(offset: Double) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putFloat("pitch_offset", offset.toFloat())
            apply()
        }
        cachedCalibrationOffset = offset // H-04: Update cache immediately
    }

    fun getCalibrationOffset(): Double {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getFloat("pitch_offset", 0.0f).toDouble()
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
}
