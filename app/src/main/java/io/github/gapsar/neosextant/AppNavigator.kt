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
    startPitchAveraging: () -> Unit,
    stopPitchAveraging: () -> Double?,
    // Calibration
    saveCalibrationOffset: (Double) -> Unit,
    getCalibrationOffset: () -> Double,
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
    hasChosenLanguage: Boolean = true
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
                    onTutorialComplete = {
                        markTutorialCompleted()
                        navController.navigate("camera") {
                            popUpTo("tutorial") { inclusive = true }
                        }
                        showOverlay = true
                        tutorialStep = 1
                    }
                )
            }
        composable("camera") {
            android.util.Log.e("Tutorial", "Composing camera route")
            val isTutorialResults = showOverlay && tutorialStep == 6
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
                supportsManualExposure = supportsManualExposure,
                startPitchAveraging = startPitchAveraging,
                stopPitchAveraging = stopPitchAveraging,
                markCalibrationUsed = markCalibrationUsed,
                analysisJobs = vm.analysisJobs,
                isRedTintMode = isRedTintMode
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
                onRedTintModeChange = { vm.saveRedTintMode(it) }
            )
        }
        composable("calibration") {
            CalibrationScreen(
                onNavigateBack = { navController.popBackStack() },
                getRawPitch = getRawPitch,
                onSaveCalibration = saveCalibrationOffset,
                currentOffset = getCalibrationOffset(),
                sensorCalibrator = sensorCalibrator,
                sensorPipeline = sensorPipeline,
                rawAccelState = rawAccelState
            )
        }
        composable("history") {
            HistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                historyRepository = historyRepository
            )
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
            val isTutorialMap = showOverlay && (tutorialStep == 7 || tutorialStep == 8)
            val displayImages = if (showOverlay && tutorialStep == 8) mockLopImages else if (isTutorialMap) emptyList() else capturedImages.toList()
            val displayLat = if (isTutorialMap) 49.49 else computedLatitude ?: 0.0
            val displayLon = if (isTutorialMap) 0.11 else computedLongitude ?: 0.0

            MapScreen(
                navController = navController,
                estimatedLatitude = latitude,
                estimatedLongitude = longitude,
                capturedImages = displayImages,
                computedLatitude = displayLat,
                computedLongitude = displayLon
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
