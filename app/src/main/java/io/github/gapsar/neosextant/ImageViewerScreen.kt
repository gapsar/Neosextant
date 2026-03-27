package io.github.gapsar.neosextant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import io.github.gapsar.neosextant.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    imageData: ImageData,
    onNavigateBack: () -> Unit
) {
    var showCentroids by remember { mutableStateOf(false) }

    // State for pinch-to-zoom
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // We need to know the rendered image size to scale the centroids correctly.
    // However, for simplicity, we can draw the centroids on a canvas that matches
    // the image's intrinsic size, then scale the whole box.
    // Or simpler: Coil's AsyncImage might not easily give us the exact pixel coordinates
    // mapping if it uses ContentScale.Fit.
    // Since astrometry images are exactly the camera resolution, we can just fetch the Bitmap
    // or use a BoxWithConstraints to calculate scaling.

    // To make it simple, we use a Box with graphicsLayer for zooming the whole view.

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(S.showStars, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = showCentroids,
                            onCheckedChange = { showCentroids = it }
                        )
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
                var intrinsicSize by remember { mutableStateOf(IntSize.Zero) }
                val context = LocalContext.current

                LaunchedEffect(imageData.uri) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            context.contentResolver.openInputStream(imageData.uri)?.use { stream ->
                                android.graphics.BitmapFactory.decodeStream(stream, null, options)
                                intrinsicSize = IntSize(options.outWidth, options.outHeight)
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
                        .build(),
                    contentDescription = S.fullScreenImage,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                if (showCentroids && intrinsicSize != IntSize.Zero && imageData.tetra3Result.centroids.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // The canvas size is the Box size (which fits the screen).
                        // The image is scaled to fit this Box using ContentScale.Fit.
                        // We need to calculate the actual rendered rect of the image.
                        val canvasRatio = size.width / size.height
                        val imageRatio = intrinsicSize.width.toFloat() / intrinsicSize.height.toFloat()

                        var renderWidth = size.width
                        var renderHeight = size.height
                        var offsetX = 0f
                        var offsetY = 0f

                        if (imageRatio > canvasRatio) {
                            // Image is wider than canvas (constrained by width)
                            renderHeight = size.width / imageRatio
                            offsetY = (size.height - renderHeight) / 2f
                        } else {
                            // Image is taller than canvas (constrained by height)
                            renderWidth = size.height * imageRatio
                            offsetX = (size.width - renderWidth) / 2f
                        }

                        // Centroids are stored as raw (y, x) from Python — in the original
                        // sensor coordinate system (before EXIF rotation).
                        //
                        // Coil auto-applies EXIF rotation when displaying, so:
                        // - intrinsicSize reflects the RAW sensor dimensions (before rotation)
                        // - The rendered image on screen is the rotated version
                        //
                        // If the original sensor image is landscape (w > h), Coil rotates it
                        // 90° CW to display as portrait. We need to apply the same rotation
                        // to centroids.
                        // If the image is already portrait (e.g. compressed history images
                        // that were pre-rotated), we do a direct mapping.

                        val sensorIsLandscape = intrinsicSize.width > intrinsicSize.height

                        for ((rawY, rawX) in imageData.tetra3Result.centroids) {
                            val screenX: Float
                            val screenY: Float

                            if (sensorIsLandscape) {
                                // Sensor image is landscape, displayed as portrait after 90° CW rotation.
                                // After 90° CW rotation: displayX = rawY, displayY = sensorWidth - rawX
                                // Rendered dims: width maps to sensorHeight, height maps to sensorWidth
                                val scaleX = renderWidth / intrinsicSize.height.toFloat()
                                val scaleY = renderHeight / intrinsicSize.width.toFloat()
                                screenX = offsetX + rawY.toFloat() * scaleX
                                screenY = offsetY + (intrinsicSize.width - rawX).toFloat() * scaleY
                            } else {
                                // Image is already portrait — direct mapping
                                val scaleX = renderWidth / intrinsicSize.width.toFloat()
                                val scaleY = renderHeight / intrinsicSize.height.toFloat()
                                screenX = offsetX + rawX.toFloat() * scaleX
                                screenY = offsetY + rawY.toFloat() * scaleY
                            }

                            drawCircle(
                                color = Color.Red,
                                radius = 20f / scale, // Keep visual size constant regardless of zoom
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
