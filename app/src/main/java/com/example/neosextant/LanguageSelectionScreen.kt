package com.example.neosextant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun LanguageSelectionScreen(
    onLanguageSelected: (AppLocale) -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(400); visible = true }

    // Simple star field
    val stars = remember {
        val rng = Random(99)
        List(150) {
            Triple(rng.nextFloat(), rng.nextFloat(), rng.nextFloat() * 2f + 0.5f)
        }
    }
    val infiniteTransition = rememberInfiniteTransition(label = "langStars")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 6.28f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "time"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Starry background
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF05050F), Color(0xFF0A0A1A), Color(0xFF0D0D25), Color(0xFF05050F))
                )
            )
            stars.forEach { (x, y, r) ->
                val alpha = (0.4f + 0.6f * sin(time * (r + 1f) + x * 10f)).coerceIn(0.1f, 1f)
                drawCircle(
                    color = Color.White.copy(alpha = alpha),
                    radius = r * (size.minDimension / 500f),
                    center = Offset(x * size.width, y * size.height)
                )
            }
        }

        // Content
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(1200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Title in all 3 languages  
                Text(
                    "Choose your language",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Choisissez votre langue",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Elige tu idioma",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(48.dp))

                // Language cards
                AppLocale.entries.forEach { locale ->
                    Card(
                        onClick = { onLanguageSelected(locale) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1A1A2E).copy(alpha = 0.9f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, Color.White.copy(alpha = 0.15f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                locale.flag,
                                fontSize = 28.sp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                locale.displayName,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
