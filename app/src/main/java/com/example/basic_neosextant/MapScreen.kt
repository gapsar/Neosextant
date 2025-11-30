package com.example.basic_neosextant

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
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
    lop1Azimuth: Float,
    lop1Intercept: Float,
    lop2Azimuth: Float,
    lop2Intercept: Float,
    lop3Azimuth: Float,
    lop3Intercept: Float,
    computedLatitude: Double,
    computedLongitude: Double
) {
    val context = LocalContext.current

    val estimatedGeoPoint = GeoPoint(estimatedLatitude.toDoubleOrNull() ?: 0.0, estimatedLongitude.toDoubleOrNull() ?: 0.0)
    val computedGeoPoint = GeoPoint(computedLatitude, computedLongitude)

    val distance = calculateDistanceInNauticalMiles(estimatedGeoPoint, computedGeoPoint)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LOP Result") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    factory = {
                        MapView(it).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(12.0)
                            controller.setCenter(estimatedGeoPoint)
                            addMarker(this, context, estimatedGeoPoint, "Estimated Position")
                            addMarker(this, context, computedGeoPoint, "Computed Position")

                            // Draw the LOPs
                            addLopLine(this, estimatedGeoPoint, lop1Azimuth, lop1Intercept, Color.RED)
                            addLopLine(this, estimatedGeoPoint, lop2Azimuth, lop2Intercept, Color.GREEN)
                            addLopLine(this, estimatedGeoPoint, lop3Azimuth, lop3Intercept, Color.BLUE)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Information Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Position Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow("Computed Position:", "${"%.4f".format(computedGeoPoint.latitude)}, ${"%.4f".format(computedGeoPoint.longitude)}")
                    InfoRow("Lat/Lon Offset:", "Lat: %.4f, Lon: %.4f".format(computedGeoPoint.latitude - estimatedGeoPoint.latitude, computedGeoPoint.longitude - estimatedGeoPoint.longitude))
                    InfoRow("Distance Offset:", "%.2f NM".format(distance))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("LOP Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    InfoRow("LOP 1 Azimuth/Intercept:", "$lop1Azimuth / $lop1Intercept NM")
                    InfoRow("LOP 2 Azimuth/Intercept:", "$lop2Azimuth / $lop2Intercept NM")
                    InfoRow("LOP 3 Azimuth/Intercept:", "$lop3Azimuth / $lop3Intercept NM")
                }
            }
        }
    }
}

private fun addMarker(mapView: MapView, context: Context, geoPoint: GeoPoint, title: String) {
    val marker = Marker(mapView)
    marker.position = geoPoint
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    marker.title = title
    // You can customize the marker icon here
    // marker.icon = ContextCompat.getDrawable(context, R.drawable.your_marker_icon)
    mapView.overlays.add(marker)
    mapView.invalidate() // Redraw the map
}

private fun addLopLine(mapView: MapView, estimatedPosition: GeoPoint, azimuth: Float, intercept: Float, color: Int) {
    // Convert intercept from nautical miles to meters
    val interceptMeters = intercept * 1852.0

    // 1. Find the point on the line of position closest to the estimated position.
    // This point is displaced from the estimated position by the intercept distance along the azimuth.
    val lopCenterPoint = estimatedPosition.destinationPoint(interceptMeters, azimuth.toDouble())

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
    mapView.invalidate() // Redraw the map
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