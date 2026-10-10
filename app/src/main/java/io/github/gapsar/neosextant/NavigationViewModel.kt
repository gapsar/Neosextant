package io.github.gapsar.neosextant

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.gapsar.neosextant.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ViewModel that preserves navigation state across configuration changes
 * (rotation, dark mode toggle, language change, etc.).
 *
 * Holds: captured images, computed position, solver mode, estimated position,
 * settings fields, tutorial state, and analysis job tracking.
 */
class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("neosextant_prefs", Context.MODE_PRIVATE)

    // --- Settings (persisted) ---
    var latitude = mutableStateOf(prefs.getString("est_latitude", "0") ?: "0")
    var longitude = mutableStateOf(prefs.getString("est_longitude", "0") ?: "0")
    var altitude = mutableStateOf(prefs.getString("est_altitude", "20") ?: "20")
    var shipSpeed = mutableStateOf(prefs.getString("ship_speed", "") ?: "")
    var shipHeading = mutableStateOf(prefs.getString("ship_heading", "") ?: "")
    var temperature = mutableStateOf(prefs.getString("temperature", "") ?: "")
    var pressure = mutableStateOf(prefs.getString("pressure", "") ?: "")

    var solverMode = mutableStateOf(
        try {
            SolverMode.valueOf(prefs.getString("solver_mode", SolverMode.ITERATIVE.name) ?: SolverMode.ITERATIVE.name)
        } catch (_: Exception) { SolverMode.ITERATIVE }
    )

    var isRedTintMode = mutableStateOf(prefs.getBoolean("is_red_tint_mode", false))

    var exposureTimeMs = mutableStateOf(prefs.getInt("exposure_time_ms", 250))
    var iso = mutableStateOf(prefs.getInt("iso", 3200))


    // --- Session state (survives config change, lost on process death) ---
    val capturedImages = mutableStateListOf<ImageData>()
    var navigatedToMap = mutableStateOf(false)
    var computedLatitude = mutableStateOf<Double?>(null)
    var computedLongitude = mutableStateOf<Double?>(null)
    var computedPrecision = mutableStateOf<Double?>(null)
    val individualFixes = mutableStateListOf<Pair<Double, Double>>()
    var lastSolvedCount = mutableIntStateOf(0)
    var viewerImageInfo = mutableStateOf<ImageData?>(null)
    var historicalEntry = mutableStateOf<PositionEntry?>(null)

    // Tutorial state
    var showOverlay = mutableStateOf(false)
    var tutorialStep = mutableIntStateOf(1)

    // M-01: Thread-safe analysis job tracker (moved from MainActivity)
    val analysisJobs = ConcurrentHashMap<Long, Job>()

    /**
     * The position-fix computation for the current session. It runs in
     * viewModelScope rather than the camera screen's scope, so leaving the
     * screen mid-solve (settings, help, rotation) no longer throws the fix away.
     */
    class FixSolveState {
        val isSolving = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        /** The solve in flight; a newer solve cancels it so results can't land out of order. */
        var job: Job? = null
    }
    val fixSolve = FixSolveState()

    /**
     * The star-calibration session. It lives here, not in the calibration
     * screen, because an affine session is 24+ bursts across many attitudes:
     * leaving the screen (settings, rotation) used to discard every pair.
     * Cleared by the screen's Reset button or when the process dies.
     */
    class StarCalibrationSession {
        val latDeg = mutableStateOf("")
        val latMin = mutableStateOf("")
        val latDir = mutableStateOf("N")
        val lonDeg = mutableStateOf("")
        val lonMin = mutableStateOf("")
        val lonDir = mutableStateOf("E")
        /** Legacy pitch/roll samples, averaged once REQUIRED_ONESHOT_SAMPLES are in. */
        val oneshotPitchOffsets = mutableStateListOf<Double>()
        val oneshotRollOffsets = mutableStateListOf<Double>()
        /** Affine pairs, one JSON object per accepted burst: {meas, mag, exp}. */
        val affinePairs = mutableStateListOf<String>()
        val affineStatus = mutableStateOf("")
        /** Calibrated gravity of the first accepted burst. */
        var firstGravity: SensorCalibrator.Vec3? = null
        /**
         * True once a burst was taken at a clearly different attitude. From then
         * on the session only feeds the affine fit: pitch/roll offsets fitted at
         * one attitude are wrong at others, and saving them made the legacy
         * 1-Shot path follow whichever attitude happened to come last.
         */
        val multiAttitude = mutableStateOf(false)
        /** Tags every line of the shot log, so one session can be refitted offline. */
        var sessionId = System.currentTimeMillis()

        fun reset() {
            oneshotPitchOffsets.clear()
            oneshotRollOffsets.clear()
            affinePairs.clear()
            affineStatus.value = ""
            firstGravity = null
            multiAttitude.value = false
            sessionId = System.currentTimeMillis()
        }
    }
    val starCalibration = StarCalibrationSession()

    // --- Debug Sensor Logger ---
    var debugRawAccel = mutableStateOf("X: 0.00, Y: 0.00, Z: 0.00")
    var debugRawGyro = mutableStateOf("X: 0.00, Y: 0.00, Z: 0.00")
    var debugKalmanCal = mutableStateOf("X: 0.00, Y: 0.00, Z: 0.00")
    var debugKalmanRaw = mutableStateOf("X: 0.00, Y: 0.00, Z: 0.00")
    var debugAndroidGrav = mutableStateOf("X: 0.00, Y: 0.00, Z: 0.00")
    var isDebugRecording = mutableStateOf(false)
    var debugLogFilePath = mutableStateOf("")

    // --- Wave Modeling ---
    var isWaveRecording = mutableStateOf(false)
    var waveRecordProgress = mutableStateOf(0f)
    var waveModelResult = mutableStateOf<String?>(null)

    // --- Persistence helpers ---
    private var saveJob: Job? = null

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500) // Debounce saves
            prefs.edit()
                .putString("est_latitude", latitude.value)
                .putString("est_longitude", longitude.value)
                .putString("est_altitude", altitude.value)
                .putString("ship_speed", shipSpeed.value)
                .putString("ship_heading", shipHeading.value)
                .putString("temperature", temperature.value)
                .putString("pressure", pressure.value)
                .putString("solver_mode", solverMode.value.name)
                .putBoolean("is_red_tint_mode", isRedTintMode.value)
                .putInt("exposure_time_ms", exposureTimeMs.value)
                .putInt("iso", iso.value)
                .apply()
        }
    }

    fun saveLatitude(value: String) {
        latitude.value = value
        scheduleSave()
    }

    fun saveLongitude(value: String) {
        longitude.value = value
        scheduleSave()
    }

    fun saveAltitude(value: String) {
        altitude.value = value
        scheduleSave()
    }

    fun saveShipSpeed(value: String) {
        shipSpeed.value = value
        scheduleSave()
    }

    fun saveShipHeading(value: String) {
        shipHeading.value = value
        scheduleSave()
    }

    fun saveTemperature(value: String) {
        temperature.value = value
        scheduleSave()
    }

    fun savePressure(value: String) {
        pressure.value = value
        scheduleSave()
    }

    fun saveSolverMode(mode: SolverMode) {
        solverMode.value = mode
        scheduleSave()
    }

    fun saveRedTintMode(enabled: Boolean) {
        isRedTintMode.value = enabled
        scheduleSave()
    }

    fun saveExposureTimeMs(value: Int) {
        exposureTimeMs.value = value
        scheduleSave()
    }

    fun saveIso(value: Int) {
        iso.value = value
        scheduleSave()
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel all ongoing analysis jobs
        analysisJobs.values.forEach { it.cancel() }
        analysisJobs.clear()
    }
}
