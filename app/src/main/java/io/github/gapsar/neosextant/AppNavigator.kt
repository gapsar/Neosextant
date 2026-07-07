package io.github.gapsar.neosextant

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.gapsar.neosextant.model.*
import io.github.gapsar.neosextant.ui.components.ImageSlotView
import io.github.gapsar.neosextant.ui.components.ImageMetadataCard
import kotlinx.coroutines.Job
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter

val RedTintMatrix = androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
    0.21f, 0.72f, 0.07f, 0f, 0f,
    0f,    0f,    0f,    0f, 0f,
    0f,    0f,    0f,    0f, 0f,
    0f,    0f,    0f,    1f, 0f
))

@Composable
fun AppNavigator(
    // Pitch
    getCurrentPitch: () -> Double?,
    getRawPitch: () -> Double?,
    getRawRoll: () -> Double?,
    startPitchAveraging: () -> Unit,
    stopPitchAveraging: () -> SensorCalibrator.Vec3?,
    // Calibration
    saveCalibrationOffset: (Double, Double) -> Unit,
    getCalibrationOffset: () -> Double,
    getRollOffset: () -> Double,
    saveOneshotCalibrationOffset: (Double, Double) -> Unit,
    getOneshotCalibrationOffset: () -> Double,
    getOneshotRollOffset: () -> Double,
    sensorCalibrator: SensorCalibrator,
    sensorPipeline: SensorPipeline,
    rawAccelState: State<SensorCalibrator.Vec3>,
    // Camera
    supportsManualExposure: Boolean,
    markCalibrationUsed: () -> Unit,
    // Tutorial
    markTutorialCompleted: () -> Unit,
    showTutorial: Boolean = false,
    // Locale
    hasChosenLanguage: Boolean = true,
    // Debug Logging
    startDebugRecording: () -> Unit,
    stopDebugRecording: () -> Unit,
    startWaveModeling: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val vm: NavigationViewModel = viewModel()

    // Locale state
    var currentLocale by remember { mutableStateOf(LocaleManager.getLocale(context)) }

    // C-01: All state now lives in ViewModel, survives config changes
    var latitude by vm.latitude
    var longitude by vm.longitude
    var altitude by vm.altitude
    var shipSpeed by vm.shipSpeed
    var shipHeading by vm.shipHeading
    var temperature by vm.temperature
    var pressure by vm.pressure
    var solverMode by vm.solverMode
    val capturedImages = vm.capturedImages
    var navigatedToMap by vm.navigatedToMap
    var computedLatitude by vm.computedLatitude
    var computedLongitude by vm.computedLongitude
    var computedPrecision by vm.computedPrecision
    var lastSolvedCount by vm.lastSolvedCount
    var viewerImageInfo by vm.viewerImageInfo
    var showOverlay by vm.showOverlay
    var tutorialStep by vm.tutorialStep
    
    // Red Tint state
    val isRedTintMode by vm.isRedTintMode

    val startDest = when {
        !hasChosenLanguage -> "languageSelection"
        showTutorial -> "tutorial"
        else -> "camera"
    }

    val historyRepository = remember { HistoryRepository(context) }

    val mockImage = remember {
        ImageData(
            id = 1L,
            uri = android.net.Uri.EMPTY,
            name = "Sirius_capture.jpg",
            timestamp = "2024-01-01T12:00:00",
            measuredHeight = 15.0,
            tetra3Result = Tetra3AnalysisResult(
                analysisState = AnalysisState.SUCCESS,
                solved = true,
                raDeg = 101.287,
                decDeg = -16.716,
                rollDeg = 0.0,
                fovDeg = 30.0,
                centroids = listOf(Pair(500.0, 500.0))
            )
        )
    }
    val mockLopImages = remember {
        List(3) { i ->
            mockImage.copy(
                id = 10L + i,
                name = "Star_$i.jpg",
                lopData = LineOfPositionData(
                    interceptNm = 2.0 * i,
                    azimuthDeg = 120.0 * i,
                    observedAltitudeDeg = 45.0 + i,
                    computedAltitudeDeg = 44.91 + i
                )
            )
        }
    }

    val tutorialTargets = remember { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTutorialTargets provides tutorialTargets,
        LocalAppLocale provides currentLocale
    ) {
        val rootModifier = if (isRedTintMode) {
            Modifier.fillMaxSize().graphicsLayer {
                colorFilter = ColorFilter.colorMatrix(RedTintMatrix)
            }
        } else {
            Modifier.fillMaxSize()
        }
        
        Box(modifier = rootModifier) {
            NavHost(navController = navController, startDestination = startDest) {
            composable("languageSelection") {
                LanguageSelectionScreen(
                    onLanguageSelected = { locale ->
                        LocaleManager.setLocale(context, locale)
                        currentLocale = locale
                        val nextRoute = if (showTutorial) "tutorial" else "camera"
                        navController.navigate(nextRoute) {
                            popUpTo("languageSelection") { inclusive = true }
                        }
                    }
                )
            }
            composable("tutorial") {
                android.util.Log.e("Tutorial", "Composing tutorial route")
                TutorialScreen(
                    onTutorialComplete = { startInteractive ->
                        markTutorialCompleted()
                        navController.navigate("camera") {
                            popUpTo("tutorial") { inclusive = true }
                        }
                        if (startInteractive) {
                            showOverlay = true
                            tutorialStep = 1
                        }
                    }
                )
            }
        composable("camera") {
            android.util.Log.e("Tutorial", "Composing camera route")
            val isTutorialResults = showOverlay && tutorialStep == 8
            val displayImages = if (isTutorialResults) listOf(mockImage) else capturedImages

            CameraView(
                navController = navController,
                historyRepository = historyRepository,
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                temperature = temperature,
                pressure = pressure,
                solverMode = solverMode,
                getCurrentPitch = getCurrentPitch,
                capturedImages = displayImages,
                forceSheetExpand = isTutorialResults,
                onUpdateImage = { updatedImage ->
                    val index = capturedImages.indexOfFirst { it.id == updatedImage.id }
                    if (index != -1) {
                        capturedImages[index] = updatedImage
                    }
                },
                onAddImage = { newImage -> capturedImages.add(newImage) },
                onRemoveImage = { imageToRemove ->
                    // M-16: Delete the image file when removing
                    try {
                        val filePath = imageToRemove.uri.path
                        if (filePath != null) {
                            val file = File(filePath)
                            if (file.exists()) file.delete()
                        }
                    } catch (e: Exception) {
                        Log.w("AppNavigator", "Failed to delete image file", e)
                    }
                    capturedImages.remove(imageToRemove)
                    vm.lastSolvedCount.intValue = 0
                },
                onRemoveAllImages = {
                    // Delete all active image files, but history copies are safe
                    capturedImages.forEach { imageToRemove ->
                        try {
                            val filePath = imageToRemove.uri.path
                            if (filePath != null) {
                                val file = File(filePath)
                                if (file.exists()) file.delete()
                            }
                        } catch (e: Exception) {
                            Log.w("AppNavigator", "Failed to delete image file", e)
                        }
                    }
                    capturedImages.clear()
                    vm.lastSolvedCount.intValue = 0
                },
                onImageLongClick = { image ->
                    viewerImageInfo = image
                    navController.navigate("imageViewer")
                },
                navigatedToMap = navigatedToMap,
                onNavigatedToMapChange = { navigatedToMap = it },
                computedLatitude = computedLatitude,
                onComputedLatitudeChange = { computedLatitude = it },
                computedLongitude = computedLongitude,
                onComputedLongitudeChange = { computedLongitude = it },
                computedPrecision = computedPrecision,
                onComputedPrecisionChange = { computedPrecision = it },
                lastSolvedCount = lastSolvedCount,
                onLastSolvedCountChange = { lastSolvedCount = it },
                onIndividualFixesChange = {
                    vm.individualFixes.clear()
                    vm.individualFixes.addAll(it)
                },
                supportsManualExposure = supportsManualExposure,
                startPitchAveraging = startPitchAveraging,
                stopPitchAveraging = stopPitchAveraging,
                markCalibrationUsed = markCalibrationUsed,
                analysisJobs = vm.analysisJobs,
                isRedTintMode = isRedTintMode,
                sensorPipeline = sensorPipeline,
                getCalibrationOffset = getCalibrationOffset,
                getRollOffset = getRollOffset,
                getOneshotCalibrationOffset = getOneshotCalibrationOffset,
                getOneshotRollOffset = getOneshotRollOffset,
                iso = vm.iso.value,
                exposureTimeMs = vm.exposureTimeMs.value
            )
        }
        composable("settings") {
            SettingsScreen(
                initialLatitude = latitude,
                onLatitudeChange = { vm.saveLatitude(it) },
                initialLongitude = longitude,
                onLongitudeChange = { vm.saveLongitude(it) },
                initialAltitude = altitude,
                onAltitudeChange = { vm.saveAltitude(it) },
                shipSpeed = shipSpeed,
                onShipSpeedChange = { vm.saveShipSpeed(it) },
                shipHeading = shipHeading,
                onShipHeadingChange = { vm.saveShipHeading(it) },
                temperature = temperature,
                onTemperatureChange = { vm.saveTemperature(it) },
                pressure = pressure,
                onPressureChange = { vm.savePressure(it) },
                solverMode = solverMode,
                onSolverModeChange = { vm.saveSolverMode(it) },
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCalibration = { navController.navigate("calibration") },
                onNavigateToDebugSensors = { navController.navigate("debugSensors") },
                onNavigateToHistory = { navController.navigate("history") },
                onReplayTutorial = {
                    navController.navigate("tutorial") {
                        popUpTo("camera") { inclusive = false }
                    }
                },
                onLocaleChange = { locale ->
                    LocaleManager.setLocale(context, locale)
                    currentLocale = locale
                },
                isRedTintMode = isRedTintMode,
                onRedTintModeChange = { vm.saveRedTintMode(it) },
                onResetSensorCal = { sensorCalibrator.resetCalibration() },
                onResetHorizon = { saveCalibrationOffset(0.0, 0.0) },
                onResetOneshot = { saveOneshotCalibrationOffset(0.0, 0.0) }
            )
        }
        composable("calibration") {
            CalibrationScreen(
                onNavigateBack = { navController.popBackStack() },
                getRawPitch = getRawPitch,
                getRawRoll = getRawRoll,
                onSaveCalibration = saveCalibrationOffset,
                currentPitchOffset = getCalibrationOffset(),
                currentRollOffset = getRollOffset(),
                sensorCalibrator = sensorCalibrator,
                sensorPipeline = sensorPipeline,
                rawAccelState = rawAccelState,
                solverMode = solverMode,
                startPitchAveraging = startPitchAveraging,
                stopPitchAveraging = stopPitchAveraging,
                onSaveOneshotCalibration = saveOneshotCalibrationOffset,
                currentOneshotPitchOffset = getOneshotCalibrationOffset(),
                currentOneshotRollOffset = getOneshotRollOffset(),
                iso = vm.iso.value,
                exposureTimeMs = vm.exposureTimeMs.value,
                supportsManualExposure = supportsManualExposure
            )
        }
        composable("debugSensors") {
            DebugSensorScreen(
                rawAccel = vm.debugRawAccel.value,
                rawGyro = vm.debugRawGyro.value,
                kalmanCal = vm.debugKalmanCal.value,
                kalmanRaw = vm.debugKalmanRaw.value,
                androidGrav = vm.debugAndroidGrav.value,
                isRecording = vm.isDebugRecording.value,
                logFilePath = vm.debugLogFilePath.value,
                isWaveRecording = vm.isWaveRecording.value,
                waveRecordProgress = vm.waveRecordProgress.value,
                waveModelResult = vm.waveModelResult.value,
                iso = vm.iso.value,
                onIsoChange = { vm.saveIso(it) },
                exposureTimeMs = vm.exposureTimeMs.value,
                onExposureTimeChange = { vm.saveExposureTimeMs(it) },
                onStartRecording = startDebugRecording,
                onStopRecording = stopDebugRecording,
                onStartWaveModeling = startWaveModeling,
                onOpenTrainingCapture = { navController.navigate("trainingCapture") },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("trainingCapture") {
            TrainingCaptureScreen(
                iso = vm.iso.value,
                exposureTimeMs = vm.exposureTimeMs.value,
                supportsManualExposure = supportsManualExposure,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("help") {
            HelpScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("history") {
            HistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                historyRepository = historyRepository,
                onViewOnMap = { entry ->
                    vm.historicalEntry.value = entry
                    navController.navigate("historyMap")
                }
            )
        }
        composable("historyMap") {
            val entry = vm.historicalEntry.value
            if (entry != null) {
                val images = remember(entry) { HistoryRepository.deserializeImages(entry.imagesJson) }
                MapScreen(
                    navController = navController,
                    estimatedLatitude = entry.estimatedLatitude.toString(),
                    estimatedLongitude = entry.estimatedLongitude.toString(),
                    capturedImages = images,
                    computedLatitude = entry.latitude,
                    computedLongitude = entry.longitude,
                    onImageClick = { image ->
                        viewerImageInfo = image
                        navController.navigate("imageViewer")
                    }
                )
            }
        }
        composable("imageViewer") {
            viewerImageInfo?.let { image ->
                ImageViewerScreen(
                    imageData = image,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
        composable("map") {
            val isTutorialMap = showOverlay && (tutorialStep == 9 || tutorialStep == 10)
            val displayImages = if (showOverlay && tutorialStep == 10) mockLopImages else if (isTutorialMap) emptyList() else capturedImages.toList()
            val displayLat = if (isTutorialMap) 49.49 else computedLatitude ?: 0.0
            val displayLon = if (isTutorialMap) 0.11 else computedLongitude ?: 0.0

            MapScreen(
                navController = navController,
                estimatedLatitude = latitude,
                estimatedLongitude = longitude,
                capturedImages = displayImages,
                computedLatitude = displayLat,
                computedLongitude = displayLon,
                individualFixes = if (isTutorialMap) emptyList() else vm.individualFixes.toList(),
                onImageClick = { image ->
                    viewerImageInfo = image
                    navController.navigate("imageViewer")
                }
            )
        }
    }

    // Global Interactive Tutorial Overlay
    if (showOverlay) {
        TutorialOverlay(
            navController = navController,
            currentStep = tutorialStep,
            onStepChange = { tutorialStep = it },
            onComplete = {
                showOverlay = false
                markTutorialCompleted()
                if (navController.currentDestination?.route != "camera") {
                    navController.navigate("camera") {
                        popUpTo(0) { inclusive = false }
                    }
                }
            }
        )
    }
        }
    }
}
