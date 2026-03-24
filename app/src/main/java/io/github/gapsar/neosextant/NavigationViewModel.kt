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
    var latitude = mutableStateOf(prefs.getString("est_latitude", "49.49") ?: "49.49")
    var longitude = mutableStateOf(prefs.getString("est_longitude", "0.11") ?: "0.11")
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


    // --- Session state (survives config change, lost on process death) ---
    val capturedImages = mutableStateListOf<ImageData>()
    var navigatedToMap = mutableStateOf(false)
    var computedLatitude = mutableStateOf<Double?>(null)
    var computedLongitude = mutableStateOf<Double?>(null)
    var viewerImageInfo = mutableStateOf<ImageData?>(null)

    // Tutorial state
    var showOverlay = mutableStateOf(false)
    var tutorialStep = mutableIntStateOf(1)

    // M-01: Thread-safe analysis job tracker (moved from MainActivity)
    val analysisJobs = ConcurrentHashMap<Long, Job>()

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

    override fun onCleared() {
        super.onCleared()
        // Cancel all ongoing analysis jobs
        analysisJobs.values.forEach { it.cancel() }
        analysisJobs.clear()
    }
}
