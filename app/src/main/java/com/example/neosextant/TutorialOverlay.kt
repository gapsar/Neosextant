package com.example.neosextant

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
                    stepLabel = S.stepSettings,
                    narrationText = S.narrationSettings,
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = false,
                    usePointer = true,
                    highlightRect = targets[1]
                )
                2 -> NarrationOverlay(
                    stepLabel = S.stepSolver,
                    narrationText = S.narrationSolver,
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = true,
                    highlightRect = targets[2]
                )
                3 -> NarrationOverlay(
                    stepLabel = S.stepCalHorizon,
                    narrationText = S.narrationCalHorizon,
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = true,
                    highlightRect = targets[3]
                )
                4 -> NarrationOverlay(
                    stepLabel = S.stepCalSensors,
                    narrationText = S.narrationCalSensors,
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = true,
                    highlightRect = targets[4]
                )
                5 -> NarrationOverlay(
                    stepLabel = S.stepPhotos,
                    narrationText = S.narrationPhotos,
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = true,
                    highlightRect = targets[5]
                )
                6 -> NarrationOverlay(
                    stepLabel = S.stepResults,
                    narrationText = S.narrationResults,
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true,
                    usePointer = false
                )
                7 -> NarrationOverlay(
                    stepLabel = S.stepMapIterative,
                    narrationText = S.narrationMapIterative,
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true
                )
                8 -> NarrationOverlay(
                    stepLabel = S.stepMapLop,
                    narrationText = S.narrationMapLop,
                    onNext = nextStep,
                    onSkip = endTour,
                    positionAtTop = true
                )
            }
        }
    }
}
