package io.github.gapsar.neosextant

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Debug tool: captures a burst of full-resolution photos with the exact
 * production astro capture settings, solves each one, and KEEPS the original
 * JPEGs (plus a JSON sidecar with the solve result and capture settings) in
 * getExternalFilesDir()/training_images — raw material for re-running the
 * solver-parameter optimizer on native captures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingCaptureScreen(
    iso: Int,
    exposureTimeMs: Int,
    supportsManualExposure: Boolean,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember(iso, exposureTimeMs, supportsManualExposure) {
        buildAstroImageCapture(iso, exposureTimeMs, supportsManualExposure)
    }
    val scope = rememberCoroutineScope()

    val BURST_COUNT = 7
    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var lastBurstLines by remember { mutableStateOf(listOf<String>()) }
    var sessionSolved by remember { mutableIntStateOf(0) }
    var sessionTotal by remember { mutableIntStateOf(0) }

    // Camera setup (same non-blocking pattern as CalibrationScreen)
    LaunchedEffect(cameraProviderFuture) {
        val cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        } catch (exc: Exception) {
            Log.e("TrainingCapture", "Use case binding failed", exc)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            } catch (e: Exception) {
                Log.w("TrainingCapture", "Camera unbind failed on dispose", e)
            }
        }
    }

    fun startBurst() {
        scope.launch {
            isBusy = true
            lastBurstLines = emptyList()
            val trainingDir = File(context.getExternalFilesDir(null), "training_images")
            trainingDir.mkdirs()

            // 1. Capture the burst (main thread, sequential like production capture)
            val capturedPaths = mutableListOf<String>()
            try {
                for (i in 1..BURST_COUNT) {
                    statusText = "Capturing $i/$BURST_COUNT…"
                    val (_, path) = takePhotoSuspend(context, imageCapture)
                    capturedPaths.add(path)
                }
            } catch (e: Exception) {
                Log.e("TrainingCapture", "Burst capture failed", e)
                statusText = "Capture failed: ${e.message}"
                isBusy = false
                return@launch
            }

            // 2. Solve each shot and archive original + sidecar
            withContext(Dispatchers.IO) {
                ensureChaquopyTmpDir(context)
                val py = Python.getInstance()
                val pythonScript = py.getModule("celestial_navigator")

                for ((idx, path) in capturedPaths.withIndex()) {
                    withContext(Dispatchers.Main) { statusText = "Solving ${idx + 1}/$BURST_COUNT…" }
                    val srcFile = File(path)
                    val line: String = try {
                        val resultStr = pythonScript
                            .callAttr("image_processor", srcFile.name, path)
                            .toString()
                        val result = JSONObject(resultStr)
                        val solved = result.optInt("solved") == 1
                        val prefix = if (solved) "solved" else "failed"
                        val baseName = "${prefix}_${srcFile.nameWithoutExtension}"

                        // Keep the ORIGINAL file untouched (move, never re-encode)
                        val dest = File(trainingDir, "$baseName.jpg")
                        if (!srcFile.renameTo(dest)) {
                            srcFile.copyTo(dest, overwrite = true)
                            srcFile.delete()
                        }

                        val sidecar = JSONObject().apply {
                            put("image", dest.name)
                            put("solved", solved)
                            put("iso", iso)
                            put("exposure_time_ms", exposureTimeMs)
                            put("solve_result", result)
                        }
                        File(trainingDir, "$baseName.json").writeText(sidecar.toString(2))

                        if (solved) {
                            "OK  #${idx + 1}: RA=%.2f Dec=%.2f FOV=%.2f".format(
                                result.optDouble("ra_deg"),
                                result.optDouble("dec_deg"),
                                result.optDouble("fov_deg")
                            )
                        } else {
                            "FAIL #${idx + 1}: ${result.optString("error_message", "no match")}"
                        }.also {
                            withContext(Dispatchers.Main) {
                                sessionTotal += 1
                                if (solved) sessionSolved += 1
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TrainingCapture", "Solve failed for $path", e)
                        "FAIL #${idx + 1}: ${e.message}"
                    }
                    withContext(Dispatchers.Main) { lastBurstLines = lastBurstLines + line }
                }
            }

            statusText = "Saved to training_images/"
            isBusy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Training Capture") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (lastBurstLines.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        lastBurstLines.forEach { line ->
                            Text(
                                text = line,
                                color = if (line.startsWith("OK")) Color.Green else Color(0xFFFF6E6E),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (statusText.isNotEmpty()) {
                    Text(statusText, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }

                Text(
                    "Session: $sessionSolved/$sessionTotal solved  •  ISO $iso  •  ${exposureTimeMs}ms",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = { startBurst() },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        if (isBusy) "WORKING…" else "CAPTURE BURST ($BURST_COUNT)",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
