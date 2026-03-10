package com.example.basic_neosextant.model

import android.net.Uri

// Solver mode enum
enum class SolverMode { LOP, ITERATIVE }

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
    val centroids: List<Pair<Double, Double>> = emptyList(),
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
