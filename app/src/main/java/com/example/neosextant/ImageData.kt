package com.example.neosextant

import android.net.Uri
import androidx.compose.runtime.Immutable // For better compose performance with state

// Add this enum
enum class ImageSourceType(val displayName: String) {
    CAMERA("Caméra"),
    STORAGE("Stockage")
}

@Immutable // Mark as immutable if fields are final
data class SensorAndOrientationData(
    val timestamp: Long,
    val accelerometer: List<Float>?,
    val gyroscope: List<Float>?,
    val magnetometer: List<Float>?,
    val phoneAzimuthDeg: Double?, // Still potentially useful for general orientation info
    val phonePitchDeg: Double?,  // This will be the calibrated pitch used for LOP Ho
    val phoneRollDeg: Double?    // Still potentially useful for general orientation info
    // calculatedCameraHvDeg and calculatedCameraZvDeg removed
)

@Immutable
data class Tetra3AnalysisResult(
    val analysisState: AnalysisState = AnalysisState.PENDING,
    val solved: Boolean = false,
    val raDeg: Double? = null,
    val decDeg: Double? = null,
    val rollDeg: Double? = null,
    val fovDeg: Double? = null,
    val errorArcsec: Double? = null,
    val datetimeSolved: String? = null,
    val errorMessage: String? = null,
    val rawSolutionStr: String? = null
)

// Enum to track analysis status
enum class AnalysisState {
    PENDING,
    SUCCESS,
    FAILURE
}


@Immutable
data class LineOfPositionData(
    val interceptNm: Double? = null, // Added default null
    val azimuthDeg: Double? = null, // Added default null
    val estimatedHvDeg: Double? = null, // Added default null
    val poleAnglePDeg: Double? = null, // Added default null
    val localHourAngleAHagDeg: Double? = null, // Added default null
    val observedCameraHvDeg: Double? = null, // Added default null
    val errorMessage: String? = null // Already had default null
)

@Immutable
data class ImageData(
    val uri: Uri,
    val sensorAndOrientationData: SensorAndOrientationData,
    val id: Long = System.currentTimeMillis(),
    val tetra3Result: Tetra3AnalysisResult = Tetra3AnalysisResult(analysisState = AnalysisState.PENDING),
    val lopData: LineOfPositionData? = null,
    val sourceType: ImageSourceType
)

@Immutable
data class CalculatedPosition(
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    val spreadNm: Double?,
    val errorMessage: String? = null,
    val lopsForDebug: List<Map<String, Any?>>? = null
)