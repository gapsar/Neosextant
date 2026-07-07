package io.github.gapsar.neosextant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import io.github.gapsar.neosextant.model.*

// Color palette for the star/constellation overlay
private val StarDotColor = Color(0xFF00E5FF)       // Cyan for star dots
private val StarNameColor = Color(0xFFFFD740)       // Amber/gold for named stars
private val UnnamedStarColor = Color(0xFF80DEEA)    // Light cyan for unnamed star dots
private val ConstellationColor = Color(0xFFB0BEC5)  // Blue-grey for constellation abbreviation
private val TextShadowColor = Color(0xCC000000)     // Semi-transparent black for shadow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    imageData: ImageData,
    onNavigateBack: () -> Unit
) {
    // Default overlay to ON when there are matched stars or centroids
    val hasMatchedStars = imageData.tetra3Result.matchedStars.isNotEmpty()
    val hasCentroids = imageData.tetra3Result.centroids.isNotEmpty()
    var showOverlay by remember { mutableStateOf(hasMatchedStars || hasCentroids) }

    // State for pinch-to-zoom
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val textMeasurer = rememberTextMeasurer()

    // overlayLabel for the toggle switch
    val overlayLabel = S.showStars

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(imageData.name) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.Close, contentDescription = S.close)
                    }
                },
                actions = {
                    if (hasMatchedStars || hasCentroids) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(overlayLabel, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = showOverlay,
                                onCheckedChange = { showOverlay = it }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 10f)
                        val extraWidth = (scale - 1) * size.width
                        val extraHeight = (scale - 1) * size.height
                        val maxX = extraWidth / 2
                        val maxY = extraHeight / 2

                        offset = Offset(
                            x = (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                            y = (offset.y + pan.y * scale).coerceIn(-maxY, maxY)
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            ) {
                var displaySize by remember { mutableStateOf(IntSize.Zero) }
                val context = LocalContext.current

                LaunchedEffect(imageData.uri) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val filePath = imageData.uri.path
                            android.util.Log.d("ImageViewer", "Loading bounds for: $filePath")
                            if (filePath != null) {
                                val file = java.io.File(filePath)
                                if (!file.exists()) {
                                    android.util.Log.e("ImageViewer", "File does not exist: $filePath")
                                    return@withContext
                                }
                                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeFile(filePath, options)
                                val rawW = options.outWidth
                                val rawH = options.outHeight
                                android.util.Log.d("ImageViewer", "Raw dimensions: ${rawW}x${rawH}")

                                if (rawW <= 0 || rawH <= 0) {
                                    android.util.Log.e("ImageViewer", "Invalid image dimensions: ${rawW}x${rawH}")
                                    return@withContext
                                }

                                // Check EXIF orientation to determine if width/height are swapped in display
                                val needsSwap = try {
                                    val exif = androidx.exifinterface.media.ExifInterface(filePath)
                                    val orientation = exif.getAttributeInt(
                                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                                    )
                                    android.util.Log.d("ImageViewer", "EXIF orientation: $orientation")
                                    orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                                    orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
                                } catch (e: Exception) {
                                    rawW > rawH // Fallback heuristic
                                }

                                displaySize = if (needsSwap) {
                                    IntSize(rawH, rawW) // Swap to get display dimensions
                                } else {
                                    IntSize(rawW, rawH)
                                }
                                android.util.Log.d("ImageViewer", "Display size: $displaySize, matched stars: ${imageData.tetra3Result.matchedStars.size}, centroids: ${imageData.tetra3Result.centroids.size}")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ImageViewer", "Failed to decode bounds", e)
                        }
                    }
                }

                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageData.uri)
                        .crossfade(true)
                        .memoryCacheKey(imageData.uri.toString())
                        .diskCachePolicy(coil.request.CachePolicy.DISABLED)
                        .build(),
                    contentDescription = S.fullScreenImage,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) {
                            android.util.Log.e("ImageViewer", "Failed to load image: ${imageData.uri}", state.result.throwable)
                        }
                    }
                )

                // --- Constellation / Star Name Overlay ---
                if (showOverlay && displaySize != IntSize.Zero) {
                    if (hasMatchedStars) {
                        // Draw matched stars with names and constellation labels
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val canvasRatio = size.width / size.height
                            val imageRatio = displaySize.width.toFloat() / displaySize.height.toFloat()

                            var renderWidth = size.width
                            var renderHeight = size.height
                            var offsetX = 0f
                            var offsetY = 0f

                            if (imageRatio > canvasRatio) {
                                renderHeight = size.width / imageRatio
                                offsetY = (size.height - renderHeight) / 2f
                            } else {
                                renderWidth = size.height * imageRatio
                                offsetX = (size.width - renderWidth) / 2f
                            }

                            val scaleXFactor = renderWidth / displaySize.width.toFloat()
                            val scaleYFactor = renderHeight / displaySize.height.toFloat()

                            for (star in imageData.tetra3Result.matchedStars) {
                                val screenX = offsetX + (star.x.toFloat() * scaleXFactor)
                                val screenY = offsetY + (star.y.toFloat() * scaleYFactor)

                                // Magnitude-scaled dot size: brighter stars (lower mag) = bigger dots
                                val mag = star.magnitude ?: 5.0
                                val dotRadius = ((6.0 - mag.coerceIn(0.0, 6.0)) * 3.0 + 4.0).toFloat() / scale

                                val isNamed = star.name != null
                                val dotColor = if (isNamed) StarDotColor else UnnamedStarColor

                                // Draw filled star dot
                                drawCircle(
                                    color = dotColor,
                                    radius = dotRadius,
                                    center = Offset(screenX, screenY)
                                )

                                // Draw a subtle ring around named stars
                                if (isNamed) {
                                    drawCircle(
                                        color = dotColor.copy(alpha = 0.4f),
                                        radius = dotRadius + 4f / scale,
                                        center = Offset(screenX, screenY),
                                        style = Stroke(width = 1.5f / scale)
                                    )
                                }

                                // Draw star name and constellation label
                                if (isNamed) {
                                    val labelParts = mutableListOf<String>()
                                    star.name?.let { labelParts.add(it) }
                                    star.constellation?.let { labelParts.add("($it)") }
                                    val label = labelParts.joinToString(" ")

                                    val fontSize = (12f / scale).coerceIn(6f, 14f)
                                    val nameStyle = TextStyle(
                                        color = StarNameColor,
                                        fontSize = fontSize.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val shadowStyle = nameStyle.copy(color = TextShadowColor)

                                    val textResult = textMeasurer.measure(label, nameStyle)
                                    val labelX = screenX + dotRadius + 6f / scale
                                    val labelY = screenY - textResult.size.height / 2f

                                    // Draw text shadow for readability
                                    val shadowOffset = 1.5f / scale
                                    drawText(
                                        textMeasurer = textMeasurer,
                                        text = label,
                                        style = shadowStyle,
                                        topLeft = Offset(labelX + shadowOffset, labelY + shadowOffset)
                                    )

                                    // Draw star name text
                                    drawText(
                                        textMeasurer = textMeasurer,
                                        text = label,
                                        style = nameStyle,
                                        topLeft = Offset(labelX, labelY)
                                    )
                                }
                            }
                        }
                    } else if (hasCentroids) {
                        // Fallback: draw centroid circles when no matched star data
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val canvasRatio = size.width / size.height
                            val imageRatio = displaySize.width.toFloat() / displaySize.height.toFloat()

                            var renderWidth = size.width
                            var renderHeight = size.height
                            var offsetX = 0f
                            var offsetY = 0f

                            if (imageRatio > canvasRatio) {
                                renderHeight = size.width / imageRatio
                                offsetY = (size.height - renderHeight) / 2f
                            } else {
                                renderWidth = size.height * imageRatio
                                offsetX = (size.width - renderWidth) / 2f
                            }

                            val scaleXFactor = renderWidth / displaySize.width.toFloat()
                            val scaleYFactor = renderHeight / displaySize.height.toFloat()

                            for ((cy, cx) in imageData.tetra3Result.centroids) {
                                val screenX = offsetX + (cx.toFloat() * scaleXFactor)
                                val screenY = offsetY + (cy.toFloat() * scaleYFactor)

                                drawCircle(
                                    color = Color.Red,
                                    radius = 20f / scale,
                                    center = Offset(screenX, screenY),
                                    style = Stroke(width = 4f / scale)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
