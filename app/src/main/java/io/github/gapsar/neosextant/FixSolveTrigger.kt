package io.github.gapsar.neosextant

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.chaquo.python.Python
import io.github.gapsar.neosextant.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Number of captures ready for the fix in [solverMode]. A burst is ready when
 * every sub-shot is analysed and at least one solved.
 */
fun readyImageCount(images: List<ImageData>, solverMode: SolverMode): Int =
    images.count { img ->
        if (img.burstSubResults.isNotEmpty()) {
            val allProcessed = img.burstSubResults.all { it.tetra3Result.analysisState != AnalysisState.PENDING }
            val anySolved = img.burstSubResults.any { it.tetra3Result.solved }
            allProcessed && anySolved
        } else {
            if (solverMode == SolverMode.LOP) img.lopData != null
            else img.tetra3Result.solved
        }
    }

/**
 * Starts the position fix once enough captures are analysed, and opens the map
 * if the camera screen is showing when it lands.
 *
 * It is composed at the navigation root, not inside CameraView. The burst
 * analysis and the solve itself already ran in the ViewModel's scope, but the
 * trigger lived in the camera screen, so a burst that finished while the user
 * was in Settings was only solved once they came back.
 */
@Composable
fun FixSolveTrigger(
    navController: NavController,
    historyRepository: HistoryRepository,
    capturedImages: List<ImageData>,
    solverMode: SolverMode,
    latitude: String,
    longitude: String,
    altitude: String,
    temperature: String,
    pressure: String,
    navigatedToMap: Boolean,
    onNavigatedToMapChange: (Boolean) -> Unit,
    onComputedLatitudeChange: (Double?) -> Unit,
    onComputedLongitudeChange: (Double?) -> Unit,
    onComputedPrecisionChange: (Double?) -> Unit,
    lastSolvedCount: Int,
    onLastSolvedCountChange: (Int) -> Unit,
    onIndividualFixesChange: (List<Pair<Double, Double>>) -> Unit,
    onUpdateImage: (ImageData) -> Unit,
    processingScope: CoroutineScope,
    fixSolve: NavigationViewModel.FixSolveState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var solverError by fixSolve.error
    var isSolving by fixSolve.isSolving
    val readyCount by remember(capturedImages, solverMode) {
        derivedStateOf { readyImageCount(capturedImages, solverMode) }
    }

    // Navigate to map when 3 images are captured and fully processed for the current mode
    LaunchedEffect(capturedImages.size, readyCount) {
        // readyImages: images that have been fully processed (burst-aware)
        val readyImages = capturedImages.filter { img ->
            if (img.burstSubResults.isNotEmpty()) {
                val allProcessed = img.burstSubResults.all { it.tetra3Result.analysisState != AnalysisState.PENDING }
                val anySolved = img.burstSubResults.any { it.tetra3Result.solved }
                allProcessed && anySolved
            } else {
                img.tetra3Result.solved
            }
        }
        val isReady = if (solverMode == SolverMode.ITERATIVE) readyCount >= 3 else if (solverMode == SolverMode.ONE_SHOT) readyCount >= 1 else readyCount == 3
        
        if (isReady && readyCount != lastSolvedCount) {
            onLastSolvedCountChange(readyCount)
            val appContext = context.applicationContext

            // Runs in processingScope, not this effect: leaving the screen must
            // not cancel the fix, and a newer solve supersedes an older one.
            fixSolve.job?.cancel()
            val job = processingScope.launch(start = CoroutineStart.LAZY) {
                isSolving = true
                solverError = null
                try {
                    // Run heavy Python computation off the main thread
                    val result = withContext(Dispatchers.IO) {
                        ensureChaquopyTmpDir(appContext)
                        val py = Python.getInstance()
                        val pythonScript = py.getModule("celestial_navigator")

                        if (solverMode == SolverMode.ITERATIVE) {
                            // --- ITERATIVE SOLVER (burst-aware) ---
                            // For each slot, median the burst sub-results' ra/dec/alt
                            val (obsList, skippedNoAlt) = buildAltitudeObservations(readyImages)
                            if (skippedNoAlt > 0) {
                                Log.w("Solver", "$skippedNoAlt sight(s) without sensor altitude excluded from iterative solve")
                            }
                            if (obsList.length() < 2) {
                                org.json.JSONObject().apply { put("error", "No sensor altitude recorded for enough sights — retake photos") }
                            } else {
                                // H-06, H-07: Pass height, pressure, temperature to solver
                                val heightM = altitude.toDoubleOrNull() ?: 0.0
                                val pressureHpa = pressure.toDoubleOrNull() ?: 1013.25
                                val temperatureC = temperature.toDoubleOrNull() ?: 15.0

                                val solveResultJsonStr = pythonScript.callAttr(
                                    "solve_iterative",
                                    obsList.toString(),
                                    0.0, // Hardcoded initial latitude for iterative solver
                                    0.0, // Hardcoded initial longitude for iterative solver
                                    heightM,
                                    pressureHpa,
                                    temperatureC
                                ).toString()

                                org.json.JSONObject(solveResultJsonStr)
                            }
                        } else if (solverMode == SolverMode.ONE_SHOT) {
                            // --- 1-SHOT BURST MULTI SOLVER ---
                            // Build burst groups: each slot's solved burst sub-results form a group
                            val burstGroups = org.json.JSONArray()
                            var skippedNoGravity = 0
                            readyImages.forEach { img ->
                                val burstGroup = org.json.JSONArray()
                                if (img.burstSubResults.isNotEmpty()) {
                                    // measuredHeight == null marks a sub-shot whose gravity capture
                                    // failed (Vec3(0,0,0) placeholder) — never send those to the solver
                                    val validSubs = img.burstSubResults.filter { it.tetra3Result.solved && it.measuredHeight != null }
                                    skippedNoGravity += img.burstSubResults.count { it.tetra3Result.solved && it.measuredHeight == null }
                                    validSubs.forEach { sub ->
                                        val obs = org.json.JSONObject()
                                        obs.put("ra", sub.tetra3Result.raDeg ?: 0.0)
                                        obs.put("dec", sub.tetra3Result.decDeg ?: 0.0)
                                        obs.put("roll", sub.tetra3Result.rollDeg ?: 0.0)
                                        obs.put("gx", sub.gravityVector.x)
                                        obs.put("gy", sub.gravityVector.y)
                                        obs.put("gz", sub.gravityVector.z)
                                        obs.put("time_iso", sub.timestamp)
                                        burstGroup.put(obs)
                                    }
                                } else {
                                    // Non-burst fallback
                                    val g = img.gravityVector
                                    if (g == null) {
                                        skippedNoGravity++
                                    } else {
                                        val obs = org.json.JSONObject()
                                        obs.put("ra", img.tetra3Result.raDeg ?: 0.0)
                                        obs.put("dec", img.tetra3Result.decDeg ?: 0.0)
                                        obs.put("roll", img.tetra3Result.rollDeg ?: 0.0)
                                        obs.put("gx", g.x)
                                        obs.put("gy", g.y)
                                        obs.put("gz", g.z)
                                        obs.put("time_iso", img.timestamp)
                                        burstGroup.put(obs)
                                    }
                                }
                                if (burstGroup.length() > 0) {
                                    burstGroups.put(burstGroup)
                                }
                            }
                            if (skippedNoGravity > 0) {
                                Log.w("Solver", "$skippedNoGravity 1-Shot sub-shot(s) without gravity vector excluded from solve")
                            }
                            if (burstGroups.length() == 0) {
                                org.json.JSONObject().apply { put("error", "No gravity vector recorded for 1-Shot") }
                            } else {
                                // Pressure/temperature drive the refraction correction applied
                                // to the zenith at the boresight (same SOFA model the LOP and
                                // iterative paths get via astropy AltAz).
                                val pressureHpa = pressure.toDoubleOrNull() ?: 1013.25
                                val temperatureC = temperature.toDoubleOrNull() ?: 15.0

                                val solveResultJsonStr = pythonScript.callAttr(
                                    "solve_oneshot_burst_multi",
                                    burstGroups.toString(),
                                    pressureHpa,
                                    temperatureC
                                ).toString()
                                org.json.JSONObject(solveResultJsonStr)
                            }
                        } else {
                            // --- C-04: LOP SOLVER (iterative, burst-aware) ---
                            val (obsList, skippedNoAlt) = buildAltitudeObservations(readyImages)
                            if (skippedNoAlt > 0) {
                                Log.w("Solver", "$skippedNoAlt sight(s) without sensor altitude excluded from LOP solve")
                            }
                            if (obsList.length() < 2) {
                                org.json.JSONObject().apply { put("error", "No sensor altitude recorded for enough sights — retake photos") }
                            } else {
                                val heightM = altitude.toDoubleOrNull() ?: 0.0
                                val pressureHpa = pressure.toDoubleOrNull() ?: 1013.25
                                val temperatureC = temperature.toDoubleOrNull() ?: 15.0

                                val solveResultJsonStr = pythonScript.callAttr(
                                    "solve_lop_iterative",
                                    obsList.toString(),
                                    latitude.toDoubleOrNull() ?: 0.0,
                                    longitude.toDoubleOrNull() ?: 0.0,
                                    heightM,
                                    pressureHpa,
                                    temperatureC
                                ).toString()
                                org.json.JSONObject(solveResultJsonStr)
                            }
                        }
                    }

                    val errorStr = result.optString("error", "")
                    if (result.has("error") && errorStr.isNotEmpty() && errorStr != "null") {
                        solverError = errorStr
                        Log.e("Solver", "Python error: $solverError")
                        onNavigatedToMapChange(false)
                        return@launch
                    }

                    if (!result.has("fixed_latitude") || !result.has("fixed_longitude")) {
                        solverError = "Missing coordinates in solver result"
                        Log.e("Solver", "Python error: $solverError")
                        return@launch
                    }

                    val finalLatitude = result.getDouble("fixed_latitude")
                    val finalLongitude = result.getDouble("fixed_longitude")
                    // Prefer the residual/spread-based estimate. final_shift_nm is only a
                    // convergence metric: it tends to 0 as the iterative solver settles,
                    // regardless of how far off the answer is, so it is a last resort.
                    val shiftNm = result.optDouble("repeatability_nm",
                        result.optDouble("error_estimate_nm",
                            result.optDouble("final_shift_nm", Double.NaN)))
                    Log.d("Solver", "Fix: Lat=$finalLatitude, Lon=$finalLongitude, error=$shiftNm NM")

                    // Per-slot fixes for the map's multi-fix markers; absent (→ empty list)
                    // for Iterative/LOP results, which also clears fixes from a previous solve
                    val parsedIndividualFixes = mutableListOf<Pair<Double, Double>>()
                    result.optJSONArray("individual_fixes")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val fix = arr.optJSONObject(i) ?: continue
                            val la = fix.optDouble("lat", Double.NaN)
                            val lo = fix.optDouble("lon", Double.NaN)
                            if (!la.isNaN() && !lo.isNaN()) parsedIndividualFixes.add(Pair(la, lo))
                        }
                    }
                    onIndividualFixesChange(parsedIndividualFixes)

                    onComputedLatitudeChange(finalLatitude)
                    onComputedLongitudeChange(finalLongitude)
                    if (!shiftNm.isNaN()) {
                        onComputedPrecisionChange(shiftNm)
                    }
                
                    // M-17: Delayed Image Compression & Downscaling — copy to history dir
                    // Full-resolution decode, rotate, scale and re-encode: IO thread,
                    // never the main thread the fix is published on.
                    val historyDir = java.io.File(appContext.getExternalFilesDir(null), "history_images")
                    val finalImages = withContext(Dispatchers.IO) {
                        historyDir.mkdirs()
                        readyImages.mapNotNull { img ->
                            val path = img.uri.path ?: return@mapNotNull null
                            try {
                                val originalFile = java.io.File(path)
                                if (originalFile.exists() && originalFile.length() > 0) {
                                    val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                                    if (bitmap != null) {
                                        val rotationDegrees = try {
                                            val exif = androidx.exifinterface.media.ExifInterface(path)
                                            val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                            when (orientation) {
                                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                                else -> 0f
                                            }
                                        } catch (e: Exception) {
                                            // Fallback: if width > height, assume sensor native landscape needing 90° rotation
                                            if (bitmap.width > bitmap.height) 90f else 0f
                                        }
                                
                                        var finalBitmap = if (rotationDegrees != 0f) {
                                            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees) }
                                            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                        } else {
                                            bitmap
                                        }
                                
                                        // Scale down to max 1024
                                        val maxDimension = 1024
                                        var scaleRatio = 1.0
                                        if (finalBitmap.width > maxDimension || finalBitmap.height > maxDimension) {
                                            val ratio = Math.min(maxDimension.toFloat() / finalBitmap.width, maxDimension.toFloat() / finalBitmap.height)
                                            val newWidth = (finalBitmap.width * ratio).toInt()
                                            val newHeight = (finalBitmap.height * ratio).toInt()
                                            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(finalBitmap, newWidth, newHeight, true)
                                            if (finalBitmap != bitmap) finalBitmap.recycle()
                                            finalBitmap = scaledBitmap
                                            scaleRatio = ratio.toDouble()
                                        }
                                
                                        // Save compressed copy to history directory
                                        val historyFile = java.io.File(historyDir, img.name)
                                        val outputStream = java.io.FileOutputStream(historyFile)
                                        finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
                                        outputStream.flush()
                                        outputStream.close()
                                
                                        if (finalBitmap != bitmap) finalBitmap.recycle()
                                        bitmap.recycle()
                                        Log.d("ImageCompression", "Compressed and saved to history: ${img.name}")
                                
                                        var updatedTetra3Result = img.tetra3Result
                                        if (scaleRatio != 1.0) {
                                            val scaledCentroids = updatedTetra3Result.centroids.map { Pair(it.first * scaleRatio, it.second * scaleRatio) }
                                            val scaledStars = updatedTetra3Result.matchedStars.map { it.copy(x = it.x * scaleRatio, y = it.y * scaleRatio) }
                                            updatedTetra3Result = updatedTetra3Result.copy(centroids = scaledCentroids, matchedStars = scaledStars)
                                        }
                                
                                        // Return image with updated URI pointing to history copy
                                        return@mapNotNull img.copy(uri = Uri.fromFile(historyFile), tetra3Result = updatedTetra3Result)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CameraView", "Failed to compress image for history", e)
                            }
                            img
                        }
                    }

                    // Save to History Repository
                    val modeStr = if (solverMode == SolverMode.ITERATIVE) "ITERATIVE" else if (solverMode == SolverMode.ONE_SHOT) "ONE_SHOT" else "LOP"
                    val imagesJsonStr = HistoryRepository.serializeImages(finalImages)
                
                    withContext(Dispatchers.IO) {
                        historyRepository.saveEntry(
                            PositionEntry(
                                timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(TimeSynchronizer.getTrueTime()),
                                latitude = finalLatitude,
                                longitude = finalLongitude,
                                errorEstimateNm = if (shiftNm.isNaN()) null else shiftNm,
                                mode = modeStr,
                                estimatedLatitude = latitude.toDoubleOrNull() ?: 0.0,
                                estimatedLongitude = longitude.toDoubleOrNull() ?: 0.0,
                                imagesJson = imagesJsonStr
                            )
                        )
                    }

                    // S-01: Delete original full-resolution photos after history save
                    // The compressed copies in history_images/ are kept; originals (~10 MB each) are no longer needed.
                    // After deletion, update capturedImages to point to the surviving history copies
                    // so the ImageViewer and centroid overlay still work.
                    val historyCopies = withContext(Dispatchers.IO) {
                        readyImages.mapNotNull { img ->
                            try {
                                val originalPath = img.uri.path ?: return@mapNotNull null
                                val originalFile = java.io.File(originalPath)
                                // Only delete if it's NOT already in history_images (avoid deleting history copies)
                                if (originalFile.exists() && !originalPath.contains("history_images")) {
                                    originalFile.delete()
                                    Log.d("StorageCleanup", "Deleted original: ${originalFile.name}")
                                }
                                // The history entry carries both the downscaled copy's URI
                                // and the coordinates rescaled to match it.
                                val historyFile = java.io.File(historyDir, img.name)
                                if (historyFile.exists()) {
                                    finalImages.find { it.name == img.name }
                                        ?: img.copy(uri = Uri.fromFile(historyFile))
                                } else null
                            } catch (e: Exception) {
                                Log.w("StorageCleanup", "Failed to delete original image", e)
                                null
                            }
                        }
                    }
                    historyCopies.forEach(onUpdateImage)

                    // Only jump to the map if the camera screen is still what the user
                    // is looking at; otherwise the fix waits behind the Map button.
                    val cameraVisible = lifecycleOwner.lifecycle.currentState
                        .isAtLeast(Lifecycle.State.RESUMED) &&
                        navController.currentDestination?.route == "camera"
                    if (!navigatedToMap && cameraVisible) {
                        onNavigatedToMapChange(true)
                        navController.navigate("map")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("Solver", "Failed to compute position fix", e)
                } finally {
                    // A superseding solve owns the flag from here on.
                    if (fixSolve.job === coroutineContext[Job]) isSolving = false
                }
            }
            fixSolve.job = job
            job.start()
        }
    }
}

/**
 * Builds the observation array for the Iterative/LOP solvers.
 * Slots without any measured sensor altitude are excluded — a missing sensor
 * reading must never be sent to the solver as a real 0° sight.
 * Returns the observations plus the number of slots that were skipped.
 */
private fun buildAltitudeObservations(images: List<ImageData>): Pair<org.json.JSONArray, Int> {
    val obsList = org.json.JSONArray()
    var skipped = 0
    images.forEach { img ->
        val obs = org.json.JSONObject()
        if (img.burstSubResults.isNotEmpty()) {
            // Burst: median ra, dec, alt from solved sub-results
            val solvedSubs = img.burstSubResults.filter { it.tetra3Result.solved }
            val ras = solvedSubs.mapNotNull { it.tetra3Result.raDeg }
            val decs = solvedSubs.mapNotNull { it.tetra3Result.decDeg }
            val alts = solvedSubs.mapNotNull { it.measuredHeight }
            if (ras.isEmpty() || decs.isEmpty() || alts.isEmpty()) {
                skipped++
                return@forEach
            }
            obs.put("ra", ras.sorted()[ras.size / 2])
            obs.put("dec", decs.sorted()[decs.size / 2])
            obs.put("alt", alts.sorted()[alts.size / 2])
        } else {
            val alt = img.measuredHeight
            if (alt == null) {
                skipped++
                return@forEach
            }
            obs.put("ra", img.tetra3Result.raDeg)
            obs.put("dec", img.tetra3Result.decDeg)
            obs.put("alt", alt)
        }
        obs.put("time_iso", img.timestamp)
        obsList.put(obs)
    }
    return Pair(obsList, skipped)
}
