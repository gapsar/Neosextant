package io.github.gapsar.neosextant.model

import android.net.Uri
import io.github.gapsar.neosextant.SensorCalibrator

// Solver mode enum
enum class SolverMode { LOP, ITERATIVE, ONE_SHOT }

// Represents the state of the image analysis
enum class AnalysisState {
    PENDING, SUCCESS, FAILURE
}

// Holds information about a single matched catalogue star
data class MatchedStar(
    val name: String?,
    val constellation: String?,
    val hipId: Int,
    val y: Double,
    val x: Double,
    val magnitude: Double?
)

// Holds the result from the Tetra3 Python script
data class Tetra3AnalysisResult(
    val analysisState: AnalysisState,
    val solved: Boolean = false,
    val raDeg: Double? = null,
    val decDeg: Double? = null,
    val rollDeg: Double? = null,
    val fovDeg: Double? = null,
    val centroids: List<Pair<Double, Double>> = emptyList(),
    val matchedStars: List<MatchedStar> = emptyList(),
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

// Holds results for a single sub-image within a burst capture
data class BurstSubResult(
    val path: String,
    val uri: Uri,
    val gravityVector: SensorCalibrator.Vec3,
    val timestamp: String,
    val measuredHeight: Double?,
    val tetra3Result: Tetra3AnalysisResult = Tetra3AnalysisResult(analysisState = AnalysisState.PENDING)
)

// A comprehensive data class to hold all information about a captured image
data class ImageData(
    val id: Long = System.currentTimeMillis(),
    val uri: Uri,
    val name: String,
    val timestamp: String,
    val measuredHeight: Double?,
    val gravityVector: SensorCalibrator.Vec3? = null,
    val tetra3Result: Tetra3AnalysisResult = Tetra3AnalysisResult(analysisState = AnalysisState.PENDING),
    val lopData: LineOfPositionData? = null,
    val burstSubResults: List<BurstSubResult> = emptyList()
)
