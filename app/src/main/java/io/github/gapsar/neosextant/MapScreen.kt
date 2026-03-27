package io.github.gapsar.neosextant

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import io.github.gapsar.neosextant.model.*
import io.github.gapsar.neosextant.ui.components.ImageSlotView
import androidx.compose.foundation.layout.aspectRatio
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    navController: NavController,
    estimatedLatitude: String,
    estimatedLongitude: String,
    capturedImages: List<ImageData>,
    computedLatitude: Double,
    computedLongitude: Double,
    onImageClick: (ImageData) -> Unit = {}
) {
    val context = LocalContext.current

    val estimatedGeoPoint = GeoPoint(estimatedLatitude.toDoubleOrNull() ?: 0.0, estimatedLongitude.toDoubleOrNull() ?: 0.0)
    val computedGeoPoint = GeoPoint(computedLatitude, computedLongitude)

    val distance = calculateDistanceInNauticalMiles(estimatedGeoPoint, computedGeoPoint)

    val mapView = remember { MapView(context) }

    var showDetailsSheet by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(S.mapResult) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = S.back)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Map View
            Card(elevation = CardDefaults.cardElevation(4.dp)) {
                Box {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        factory = {
                            mapView.apply {
                                setMultiTouchControls(true)

                                // 1. Configure OSMDroid Cache Directory
                                val basePath = java.io.File(context.getExternalFilesDir(null), "osmdroid")
                                basePath.mkdirs()
                                org.osmdroid.config.Configuration.getInstance().osmdroidBasePath = basePath
                                org.osmdroid.config.Configuration.getInstance().osmdroidTileCache = basePath

                                // Copy basemap if needed
                                val basemapFile = java.io.File(basePath, "world_basemap.mbtiles")
                                if (!basemapFile.exists()) {
                                    try {
                                        context.assets.open("world_basemap.mbtiles").use { input ->
                                            java.io.FileOutputStream(basemapFile).use { output ->
                                                input.copyTo(output)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("MapScreen", "Failed to copy asset world_basemap.mbtiles", e)
                                    }
                                }

                                // 2. Check for offline archives (e.g. .zip, .sqlite) in the basePath
                                val archives = basePath.listFiles { file ->
                                    file.name.endsWith(".sqlite") || file.name.endsWith(".zip") || file.name.endsWith(".mbtiles")
                                }

                                if (!archives.isNullOrEmpty()) {
                                    // If offline files exist, use them via standard tile provider
                                    setUseDataConnection(false)
                                    val provider = org.osmdroid.tileprovider.MapTileProviderBasic(context)
                                    provider.setOfflineFirst(true)
                                    tileProvider = provider
                                } else {
                                    // Fallback to online map if no offline archive is bundled
                                    setTileSource(TileSourceFactory.MAPNIK)
                                }
                            }
                        },
                        update = { view ->
                            view.overlays.clear() // H-14: Clear stale overlays before adding new ones

                            // Center the map between estimated and computed positions
                            val centerLat = (estimatedGeoPoint.latitude + computedGeoPoint.latitude) / 2.0
                            val centerLon = (estimatedGeoPoint.longitude + computedGeoPoint.longitude) / 2.0
                            view.controller.setZoom(12.0)
                            view.controller.setCenter(GeoPoint(centerLat, centerLon))

                            // Dashed offset line between estimated and computed positions
                            val offsetLine = Polyline()
                            offsetLine.addPoint(estimatedGeoPoint)
                            offsetLine.addPoint(computedGeoPoint)
                            offsetLine.outlinePaint.apply {
                                color = Color.DKGRAY
                                strokeWidth = 4f
                                pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 15f), 0f)
                                style = android.graphics.Paint.Style.STROKE
                            }
                            view.overlays.add(offsetLine)

                            // Markers
                            addEstimatedMarker(view, context, estimatedGeoPoint)
                            addComputedMarker(view, context, computedGeoPoint)

                            // Draw the LOPs
                            capturedImages.forEachIndexed { index, imageData ->
                                val azimuth = imageData.lopData?.azimuthDeg?.toFloat() ?: return@forEachIndexed
                                val intercept = imageData.lopData.interceptNm?.toFloat() ?: return@forEachIndexed
                                val color = when(index) {
                                    0 -> Color.RED
                                    1 -> Color.GREEN
                                    else -> Color.BLUE
                                }
                                addLopLine(view, estimatedGeoPoint, azimuth, intercept, color)
                            }
                            view.invalidate()
                        }
                    )

                    // Zoom Out to World Button
                    IconButton(
                        onClick = {
                            mapView.controller.zoomTo(2.0)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), shape = RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = S.zoomOutToWorld,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Information Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(S.positionDetails, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow(S.computedPosition, "${"%.4f".format(computedGeoPoint.latitude)}, ${"%.4f".format(computedGeoPoint.longitude)}")
                    InfoRow(S.latLonOffset, "Lat: %.4f, Lon: %.4f".format(computedGeoPoint.latitude - estimatedGeoPoint.latitude, computedGeoPoint.longitude - estimatedGeoPoint.longitude))
                    InfoRow(S.distanceOffset, "%.2f NM".format(distance))

                    Spacer(modifier = Modifier.height(16.dp))

                    if (capturedImages.any { it.lopData != null }) {
                        androidx.compose.material3.Button(
                            onClick = { showDetailsSheet = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(S.viewDetailedCalc)
                        }
                    }
                }
            }

            if (capturedImages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0 until 3) {
                        val imageInfo = capturedImages.getOrNull(i)
                        ImageSlotView(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                            imageInfo = imageInfo,
                            isSelected = false,
                            isProcessing = false,
                            onClick = { info -> onImageClick(info) },
                            onLongClick = { info -> onImageClick(info) }
                        )
                    }
                }
            }
        }

        if (showDetailsSheet) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { showDetailsSheet = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(S.lopDetailedCalc, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                    capturedImages.forEachIndexed { index, imageData ->
                        val ra = imageData.tetra3Result.raDeg ?: 0.0
                        val dec = imageData.tetra3Result.decDeg ?: 0.0
                        val hc = imageData.lopData?.computedAltitudeDeg ?: 0.0
                        val ho = imageData.lopData?.observedAltitudeDeg ?: 0.0
                        val intercept = imageData.lopData?.interceptNm ?: 0.0
                        val azimuth = imageData.lopData?.azimuthDeg ?: 0.0

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(S.observation(index + 1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                InfoRow(S.rightAscension, "%.4f°".format(ra))
                                InfoRow(S.declinationLabel, "%.4f°".format(dec))
                                InfoRow(S.computedAlt, "%.4f°".format(hc))
                                InfoRow(S.observedAlt, "%.4f°".format(ho))
                                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                InfoRow(S.intercept, "%.2f NM".format(intercept))
                                InfoRow(S.azimuthLabel, "%.1f°".format(azimuth))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Estimated Position marker: green hollow circle with crosshairs.
 * Communicates "approximate / targeting" visually.
 */
private fun addEstimatedMarker(mapView: MapView, context: Context, geoPoint: GeoPoint) {
    val marker = Marker(mapView)
    marker.position = geoPoint
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    marker.title = S.get(LocaleManager.getLocale(context), "Estimated Position", "Position estimée", "Posición estimada")

    val size = 48
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    val radius = size / 2f - 4f

    // Hollow green circle
    val circlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(cx, cy, radius, circlePaint)

    // Crosshair lines
    val crossPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 2.5f
        style = android.graphics.Paint.Style.STROKE
    }
    val gap = 5f // gap in the center of the crosshairs
    // Horizontal
    canvas.drawLine(0f, cy, cx - gap, cy, crossPaint)
    canvas.drawLine(cx + gap, cy, size.toFloat(), cy, crossPaint)
    // Vertical
    canvas.drawLine(cx, 0f, cx, cy - gap, crossPaint)
    canvas.drawLine(cx, cy + gap, cx, size.toFloat(), crossPaint)

    marker.icon = android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    mapView.overlays.add(marker)
}

/**
 * Computed Position marker: solid red circle with a white center dot.
 * Communicates "confirmed fix" visually.
 */
private fun addComputedMarker(mapView: MapView, context: Context, geoPoint: GeoPoint) {
    val marker = Marker(mapView)
    marker.position = geoPoint
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    marker.title = S.get(LocaleManager.getLocale(context), "Computed Position", "Position calculée", "Posición calculada")

    val size = 48
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    // Outer solid red circle
    val outerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, size / 2f - 2f, outerPaint)

    // White center dot
    val centerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 6f, centerPaint)

    marker.icon = android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    mapView.overlays.add(marker)
}

private fun addLopLine(mapView: MapView, estimatedPosition: GeoPoint, azimuth: Float, intercept: Float, color: Int) {
    // Convert intercept from nautical miles to meters
    val interceptMeters = intercept * 1852.0

    // 1. Find the point on the line of position closest to the estimated position.
    // This point is displaced from the estimated position by the intercept distance along the azimuth.
    // Handle negative intercepts by flipping the azimuth 180 degrees
    val correctedAzimuth = if (interceptMeters < 0) (azimuth.toDouble() + 180.0) % 360.0 else azimuth.toDouble()
    val lopCenterPoint = estimatedPosition.destinationPoint(kotlin.math.abs(interceptMeters), correctedAzimuth)

    // 2. Define the line of position. It's a straight line perpendicular to the azimuth.
    // We create a very long line by finding two points far away from the center point.
    val lineLengthMeters = 500_000.0 // 500 km, long enough to span the screen
    val p1 = lopCenterPoint.destinationPoint(lineLengthMeters, azimuth.toDouble() - 90.0)
    val p2 = lopCenterPoint.destinationPoint(lineLengthMeters, azimuth.toDouble() + 90.0)

    // 3. Create and add the polyline to the map
    val lopLine = Polyline()
    lopLine.addPoint(p1)
    lopLine.addPoint(p2)
    lopLine.outlinePaint.color = color
    lopLine.outlinePaint.strokeWidth = 5f

    mapView.overlays.add(lopLine)
    mapView.invalidate()
}


@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}

fun calculateDistanceInNauticalMiles(point1: GeoPoint, point2: GeoPoint): Double {
    val earthRadiusNauticalMiles = 3440.065
    val lat1Rad = Math.toRadians(point1.latitude)
    val lon1Rad = Math.toRadians(point1.longitude)
    val lat2Rad = Math.toRadians(point2.latitude)
    val lon2Rad = Math.toRadians(point2.longitude)

    val dLon = lon2Rad - lon1Rad
    val dLat = lat2Rad - lat1Rad

    val a = sin(dLat / 2).pow(2.0) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2.0)
    val c = 2 * asin(kotlin.math.sqrt(a))
    return earthRadiusNauticalMiles * c
}
