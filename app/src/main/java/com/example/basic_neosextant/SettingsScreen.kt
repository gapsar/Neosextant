package com.example.basic_neosextant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.basic_neosextant.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialLatitude: String,
    onLatitudeChange: (String) -> Unit,
    initialLongitude: String,
    onLongitudeChange: (String) -> Unit,
    initialAltitude: String,
    onAltitudeChange: (String) -> Unit,
    shipSpeed: String,
    onShipSpeedChange: (String) -> Unit,
    shipHeading: String,
    onShipHeadingChange: (String) -> Unit,
    temperature: String,
    onTemperatureChange: (String) -> Unit,
    pressure: String,
    onPressureChange: (String) -> Unit,
    solverMode: SolverMode,
    onSolverModeChange: (SolverMode) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToCalibration: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onReplayTutorial: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sensor Calibration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = onNavigateToCalibration, modifier = Modifier.fillMaxWidth()) {
                Text("Calibrate Sensors")
            }

            Button(onClick = onNavigateToHistory, modifier = Modifier.fillMaxWidth()) {
                Text("View Position History")
            }

            Button(
                onClick = onReplayTutorial,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Replay Tutorial")
            }
            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.tutorialTarget(1)) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Vessel Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ValidatedNumberField(
                    value = shipSpeed,
                    onValueChange = onShipSpeedChange,
                    label = "Ship's Speed (knots)",
                    imeAction = ImeAction.Next,
                    validator = { it >= 0.0 },
                    errorMessage = "Speed cannot be negative",
                    clamp = { it.coerceAtLeast(0.0) },
                    defaultValue = 0.0
                )
                ValidatedNumberField(
                    value = shipHeading,
                    onValueChange = onShipHeadingChange,
                    label = "Ship's Heading (degrees true)",
                    imeAction = ImeAction.Next,
                    validator = { it in 0.0..360.0 },
                    errorMessage = "Heading must be 0-360",
                    clamp = { it.coerceIn(0.0, 360.0) },
                    defaultValue = 0.0
                )
                ValidatedNumberField(
                    value = initialAltitude,
                    onValueChange = onAltitudeChange,
                    label = "Height of Eye (m)",
                    imeAction = ImeAction.Done,
                    validator = { it >= -500.0 },
                    errorMessage = "Height must be at least -500m",
                    clamp = { it.coerceAtLeast(-500.0) },
                    defaultValue = 0.0
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Weather Conditions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ValidatedNumberField(
                    value = temperature,
                    onValueChange = onTemperatureChange,
                    label = "Temperature (°C)",
                    imeAction = ImeAction.Next,
                    validator = { it >= -273.15 },
                    errorMessage = "Temperature cannot be below absolute zero",
                    clamp = { it.coerceAtLeast(-273.15) },
                    defaultValue = 15.0
                )
                ValidatedNumberField(
                    value = pressure,
                    onValueChange = onPressureChange,
                    label = "Pressure (hPa)",
                    imeAction = ImeAction.Done,
                    validator = { it > 0.0 },
                    errorMessage = "Pressure must be positive",
                    clamp = { if (it <= 0.0) 1013.25 else it },
                    defaultValue = 1013.25
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Solver Mode Toggle
            Column(modifier = Modifier.tutorialTarget(2)) {
                Text("Solver Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (solverMode == SolverMode.ITERATIVE) "Iterative: Auto-computes position starting from Estimated Position"
                    else "LOP: Displays Lines of Position on the map near Estimated Position",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SolverMode.entries.forEach { mode ->
                        FilterChip(
                            selected = solverMode == mode,
                            onClick = { onSolverModeChange(mode) },
                            label = { Text(mode.name) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Estimated position fields (now required for both modes)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Estimated Position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ValidatedNumberField(
                    value = initialLatitude,
                    onValueChange = onLatitudeChange,
                    label = "Latitude (°N)",
                    imeAction = ImeAction.Next,
                    validator = { it in -90.0..90.0 },
                    errorMessage = "Latitude must be between -90 and 90",
                    clamp = { it.coerceIn(-90.0, 90.0) },
                    defaultValue = 0.0
                )
                ValidatedNumberField(
                    value = initialLongitude,
                    onValueChange = onLongitudeChange,
                    label = "Longitude (°E)",
                    imeAction = ImeAction.Done,
                    validator = { it in -180.0..180.0 },
                    errorMessage = "Longitude must be between -180 and 180",
                    clamp = { it.coerceIn(-180.0, 180.0) },
                    defaultValue = 0.0
                )
            }
        }
    }
}

@Composable
fun ValidatedNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction,
    validator: (Double) -> Boolean,
    errorMessage: String,
    clamp: (Double) -> Double,
    defaultValue: Double
) {
    var text by remember(value) { mutableStateOf(value) }
    val num = text.toDoubleOrNull()

    val isError = text.isNotEmpty() && text != "-" && text != "." && text != "-." && (num == null || !validator(num))

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            onValueChange(newText)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (!focusState.isFocused && text.isNotEmpty() && text != "-" && text != "." && text != "-.") {
                    val parsed = text.toDoubleOrNull()
                    if (parsed == null) {
                        text = defaultValue.toString()
                        onValueChange(text)
                    } else if (!validator(parsed)) {
                        text = clamp(parsed).toString()
                        onValueChange(text)
                    }
                }
            },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        singleLine = true,
        isError = isError,
        supportingText = if (isError) { { Text(errorMessage, color = MaterialTheme.colorScheme.error) } } else null
    )

    DisposableEffect(Unit) {
        onDispose {
            if (text.isNotEmpty() && text != "-" && text != "." && text != "-.") {
                val parsed = text.toDoubleOrNull()
                if (parsed == null) {
                    onValueChange(defaultValue.toString())
                } else if (!validator(parsed)) {
                    onValueChange(clamp(parsed).toString())
                }
            } else if (text == "-" || text == "." || text == "-.") {
                onValueChange(defaultValue.toString())
            }
        }
    }
}
