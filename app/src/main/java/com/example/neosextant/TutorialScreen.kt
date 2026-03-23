package com.example.neosextant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.ui.text.font.Font
import com.example.neosextant.R
import com.example.neosextant.ui.components.MetadataRow

// ─── Space-themed font for the crawl ───
// Using News Cycle Bold for authentic Star Wars/Newsreel aesthetic
private val crawlFontFamily = FontFamily(Font(R.font.news_cycle_bold))

// ─── Data for the twinkling star field background ───

private data class StarData(
    val x: Float,
    val y: Float,
    val radius: Float,
    val baseAlpha: Float,
    val twinkleSpeed: Float,
    val twinklePhase: Float
)

private fun generateStars(count: Int, seed: Long = 42L): List<StarData> {
    val rng = Random(seed)
    return List(count) {
        StarData(
            x = rng.nextFloat(),
            y = rng.nextFloat(),
            radius = rng.nextFloat() * 2.5f + 0.5f,
            baseAlpha = rng.nextFloat() * 0.5f + 0.5f,
            twinkleSpeed = rng.nextFloat() * 3f + 1f,
            twinklePhase = rng.nextFloat() * 6.28f
        )
    }
}

// ─── Star field background composable ───

@Composable
private fun StarryBackground(modifier: Modifier = Modifier) {
    val stars = remember { generateStars(250) }
    val infiniteTransition = rememberInfiniteTransition(label = "starTwinkle")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF05050F),
                    Color(0xFF0A0A1A),
                    Color(0xFF0D0D25),
                    Color(0xFF08081A),
                    Color(0xFF050510)
                )
            )
        )

        for (star in stars) {
            val alpha = (star.baseAlpha *
                    (0.5f + 0.5f * sin(time * star.twinkleSpeed + star.twinklePhase)))
                .coerceIn(0.05f, 1f)

            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = star.radius * (size.minDimension / 400f),
                center = Offset(star.x * size.width, star.y * size.height)
            )
        }
    }
}

// ─── Main Tutorial Screen (Intro Only) ───

@Composable
fun TutorialScreen(
    onTutorialComplete: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }
    android.util.Log.d("Tutorial", "TutorialScreen composing, currentStep=$currentStep")

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentStep) {
            0 -> StarWarsCrawlStep(onFinished = { currentStep = 1 })
            1 -> TransitionStep(onNext = onTutorialComplete, onSkip = onTutorialComplete)
        }
    }
}

// ─── Step 0: Star Wars Crawl ───

@Composable
private fun StarWarsCrawlStep(onFinished: () -> Unit) {
    // structured text blocks for better alignment control
    data class CrawlLine(val text: String, val isTitle: Boolean)

    val crawlLines = listOf(
        CrawlLine(S.crawlWelcome, true),
        CrawlLine(S.crawlIntro, false),
        CrawlLine(S.crawlAstronav, true),
        CrawlLine(S.crawlNoSextant, false),
        CrawlLine(S.crawlPosition, true)
    )

    // Calculate density-aware measurements
    val density = LocalDensity.current

    // Animation state
    // We want a slow drift upwards.
    val animationDuration = 60000 // 60 seconds

    val scrollState = rememberScrollState()
    val infiniteTransition = rememberInfiniteTransition(label = "crawlScroll")

    val scrollProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scroll"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        StarryBackground(modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 3D Perspective Transformation
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationX = 25f // Tilt the top away
                        cameraDistance = 12f * density.density
                    }
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val screenHeightPx = constraints.maxHeight.toFloat()
                    val startY = screenHeightPx * 0.8f
                    val endY = -screenHeightPx * 2.5f // Move far up into the "distance"

                    val currentY = startY + (endY - startY) * scrollProgress

                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .graphicsLayer {
                                translationY = currentY
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    repeat(3) {
                        crawlLines.forEach { line ->
                            Text(
                                text = line.text,
                                color = Color(0xFFFFC400), // Iconic Star Wars Yellow
                                fontFamily = crawlFontFamily,
                                fontSize = if (line.isTitle) 32.sp else 22.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = if (line.isTitle) TextAlign.Center else TextAlign.Justify,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                lineHeight = 30.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(300.dp)) // Gap between loops
                    }
                    }
                }
            }
        }

        // Fades for "disappearing into infinity" effect at the top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF05050F), // Background color
                            Color(0xFF05050F).copy(alpha = 0.8f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Skip button area
        Box(modifier = Modifier.fillMaxSize().clickable { onFinished() })

        Text(
            text = S.tapToSkip,
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
}

// ─── Step 1: Transition Screen ───

@Composable
private fun TransitionStep(onNext: () -> Unit, onSkip: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(800)
        visible = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        StarryBackground(modifier = Modifier.fillMaxSize())

        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(1500)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(32.dp)
            ) {
                Text(
                    text = S.tutorialTransition,
                    color = Color.White,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 30.sp
                )

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF415F91)),
                    modifier = Modifier.fillMaxWidth(0.6f)
                ) {
                    Text(S.letsGo, fontSize = 18.sp)
                }
            }
        }

        TextButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
        ) {
            Text(S.skipTutorial, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
    }
}

// ─── Shared Narration Overlay (positioned at top or bottom) ───

@Composable
fun NarrationOverlay(
    narrationText: String,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    stepLabel: String = "",
    positionAtTop: Boolean = false,
    usePointer: Boolean = false,
    highlightRect: Rect? = null
) {
    val alignment = if (positionAtTop) Alignment.TopCenter else Alignment.BottomCenter
    val gradient = if (positionAtTop) {
        Brush.verticalGradient(listOf(Color.Black, Color.Black.copy(alpha = 0.9f), Color.Transparent))
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f), Color.Black))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Subtle overlay — just enough to read text, but highlighting the app behind it
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f)))

        if (usePointer && highlightRect != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Draw a rounded rectangle framing the highlighted element
                drawRoundRect(
                    color = Color.Yellow,
                    topLeft = highlightRect.topLeft,
                    size = highlightRect.size,
                    cornerRadius = CornerRadius(24f, 24f),
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }

        Column(
            modifier = Modifier
                .align(alignment)
                .fillMaxWidth()
                .background(gradient)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (positionAtTop) {
                Spacer(modifier = Modifier.height(32.dp)) // status bar offset
            }

            if (stepLabel.isNotEmpty()) {
                Text(
                    text = stepLabel,
                    color = Color(0xFFAAC7FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Text(
                text = narrationText,
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) {
                    Text(S.endTour, color = Color.White.copy(alpha = 0.5f))
                }

                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF415F91))
                ) {
                    Text(S.next)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            if (!positionAtTop) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
