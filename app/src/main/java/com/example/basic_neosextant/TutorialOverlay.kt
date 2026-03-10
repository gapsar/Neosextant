package com.example.basic_neosextant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

val LocalTutorialTargets = compositionLocalOf<MutableMap<Int, Rect>> { error("No tutorial targets provided") }

fun Modifier.tutorialTarget(step: Int): Modifier = composed {
    val targets = LocalTutorialTargets.current
    onGloballyPositioned { coordinates ->
        targets[step] = coordinates.boundsInRoot()
    }
}

@Composable
fun TutorialOverlay(
    navController: NavController,
    currentStep: Int,
    onStepChange: (Int) -> Unit,
    onComplete: () -> Unit
) {
    var showOverlay by remember { mutableStateOf(true) }
    var internalNextStep by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidth = config.screenWidthDp.dp
    val screenHeight = config.screenHeightDp.dp
    val targets = LocalTutorialTargets.current

    val nextStep: () -> Unit = {
        showOverlay = false
        internalNextStep = currentStep + 1
    }

    val endTour: () -> Unit = {
        onComplete()
    }

    LaunchedEffect(internalNextStep, showOverlay) {
        if (!showOverlay && internalNextStep != null) {
            delay(300) // wait for out animation
            onStepChange(internalNextStep!!)
            internalNextStep = null
            showOverlay = true
        }
    }

    // Programmatic routing based on step
    LaunchedEffect(currentStep, showOverlay) {
        if (showOverlay) {
            when (currentStep) {
                1, 2 -> {
                    if (navController.currentDestination?.route != "settings") {
                        navController.navigate("settings") { launchSingleTop = true }
                    }
                }
                3, 4 -> {
                    if (navController.currentDestination?.route != "calibration") {
                        navController.navigate("calibration") { launchSingleTop = true }
                    }
                }
                5, 6 -> {
                    if (navController.currentDestination?.route != "camera") {
                        navController.navigate("camera") { popUpTo("camera") { inclusive = false } }
                    }
                }
                7, 8 -> {
                    if (navController.currentDestination?.route != "map") {
                        navController.navigate("map") { launchSingleTop = true }
                    }
                }
                9 -> {
                    onComplete()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 6 },
            exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 6 }
        ) {
            when (currentStep) {
                1 -> NarrationOverlay(
                    stepLabel = "SETTINGS PAGE",
                    narrationText = "Welcome to the real app! Here in Settings, you enter your vessel's Speed and Heading if you are moving. " +
                            "If you are fixed on land, just input Temperature and Pressure to compute atmospheric refraction.",
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = false,
                    usePointer = true,
                    highlightRect = targets[1]
                )
                2 -> NarrationOverlay(
                    stepLabel = "SOLVER MODE",
                    narrationText = "Down here is the Solver Mode toggle. By default, it's on 'Iterative' to automatically triangulate your location. " +
                            "If you select 'LOP', you'll see the classic celestial navigation intersection lines on the map.",
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = true,
                    highlightRect = targets[2]
                )
                3 -> NarrationOverlay(
                    stepLabel = "CALIBRATION — HORIZON",
                    narrationText = "Now onto the calibration window! First the easy one: the horizon calibration. " +
                            "This reduces alignment error between the camera and acceleration sensor. " +
                            "Input your eye height, align the horizon with the red line, and click 'Set Horizon'.",
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = true,
                    highlightRect = targets[3]
                )
                4 -> NarrationOverlay(
                    stepLabel = "CALIBRATION — SENSORS",
                    narrationText = "The second calibration is inherent to phone IMUs — it is called Sphere Fitting. " +
                            "Click 'Calibrate Sensors', then follow the instructions. Put your phone on each side " +
                            "and hold it stable. It will vibrate when it reads a clean measurement.",
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = true,
                    highlightRect = targets[4]
                )
                5 -> NarrationOverlay(
                    stepLabel = "TAKING PHOTOS",
                    narrationText = "Welcome to the sky view. To navigate, just point your phone at the stars. " +
                            "Take a picture using the bottom button. Note that your phone MUST be as still as possible " +
                            "(use a tripod if you can).",
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = true,
                    highlightRect = targets[5]
                )
                6 -> NarrationOverlay(
                    stepLabel = "PHOTO RESULTS",
                    narrationText = "Once taken, you'll see a thumbnail appear in the lifted panel. Processing takes a few seconds. " +
                            "You need exactly three successfully solved images to triangulate your position.",
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = false
                )
                7 -> NarrationOverlay(
                    stepLabel = "MAP PAGE (ITERATIVE)",
                    narrationText = "After 3 images are captured and solved, you'll be brought to the Map! " +
                            "The Iterative Process mathematically converges your latitude and longitude, starting from your Estimated Position. " +
                            "It uses least-squares to find the precise fix. Boom! Your location is pinpointed.",
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true
                )
                8 -> NarrationOverlay(
                    stepLabel = "MAP PAGE (LOP)",
                    narrationText = "If you switch to LOP (Line of Position) mode in Settings, the Map shows " +
                            "the classic celestial navigation intersection. You will see 3 distinct colored lines " +
                            "crossing over your Estimated Position, with detailed intercept math available!",
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true
                )
            }
        }
    }
}
