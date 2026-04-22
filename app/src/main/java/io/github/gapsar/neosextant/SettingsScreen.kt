package io.github.gapsar.neosextant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter
import io.github.gapsar.neosextant.model.*

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
    onReplayTutorial: () -> Unit,
    onLocaleChange: (AppLocale) -> Unit,
    isRedTintMode: Boolean,
    onRedTintModeChange: (Boolean) -> Unit,
    onResetSensorCal: () -> Unit,
    onResetHorizon: () -> Unit
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSystemParamsDialog by remember { mutableStateOf(false) }
    var showSolverModeHelpDialog by remember { mutableStateOf(false) }
    var showResetSensorDialog by remember { mutableStateOf(false) }
    var showResetHorizonDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(S.settings) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TimeSyncStatus()

            Text(S.sensorCalibration, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = onNavigateToCalibration, modifier = Modifier.fillMaxWidth()) {
                Text(S.calibrateSensors)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showResetSensorDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(S.resetSensorCal)
                }
                OutlinedButton(onClick = { showResetHorizonDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(S.resetHorizon)
                }
            }

            Button(onClick = onNavigateToHistory, modifier = Modifier.fillMaxWidth()) {
                Text(S.viewPositionHistory)
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { showSystemParamsDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(S.systemParameters)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.tutorialTarget(1)) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(S.vesselInfo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ValidatedNumberField(
                    value = shipSpeed,
                    onValueChange = onShipSpeedChange,
                    label = S.shipSpeed,
                    imeAction = ImeAction.Next,
                    validator = { it >= 0.0 },
                    errorMessage = S.speedNegative,
                    clamp = { it.coerceAtLeast(0.0) },
                    defaultValue = 0.0
                )
                ValidatedNumberField(
                    value = shipHeading,
                    onValueChange = onShipHeadingChange,
                    label = S.shipHeading,
                    imeAction = ImeAction.Next,
                    validator = { it in 0.0..360.0 },
                    errorMessage = S.headingRange,
                    clamp = { it.coerceIn(0.0, 360.0) },
                    defaultValue = 0.0
                )
                ValidatedNumberField(
                    value = initialAltitude,
                    onValueChange = onAltitudeChange,
                    label = S.heightOfEye,
                    imeAction = ImeAction.Done,
                    validator = { it >= -500.0 },
                    errorMessage = S.heightMin,
                    clamp = { it.coerceAtLeast(-500.0) },
                    defaultValue = 0.0
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(S.weatherConditions, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ValidatedNumberField(
                    value = temperature,
                    onValueChange = onTemperatureChange,
                    label = S.temperatureLabel,
                    imeAction = ImeAction.Next,
                    validator = { it >= -273.15 },
                    errorMessage = S.tempAbsZero,
                    clamp = { it.coerceAtLeast(-273.15) },
                    defaultValue = 15.0
                )
                ValidatedNumberField(
                    value = pressure,
                    onValueChange = onPressureChange,
                    label = S.pressureLabel,
                    imeAction = ImeAction.Done,
                    validator = { it > 0.0 },
                    errorMessage = S.pressurePositive,
                    clamp = { if (it <= 0.0) 1013.25 else it },
                    defaultValue = 1013.25
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Solver Mode Toggle
            Column(modifier = Modifier.tutorialTarget(2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S.solverMode, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { showSolverModeHelpDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = S.helpSolverModeTitle, modifier = Modifier.size(20.dp))
                    }
                }
                Text(
                    when (solverMode) {
                        SolverMode.ITERATIVE -> S.iterativeDesc
                        SolverMode.LOP -> S.lopDesc
                        SolverMode.ONE_SHOT -> S.oneShotDesc
                    },
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
                            label = { Text(S.solverModeName(mode)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Estimated position fields (only for LOP mode)
            if (solverMode == SolverMode.LOP) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(S.estimatedPosition, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ValidatedNumberField(
                    value = initialLatitude,
                    onValueChange = onLatitudeChange,
                    label = S.latitudeLabel,
                    imeAction = ImeAction.Next,
                    validator = { it in -90.0..90.0 },
                    errorMessage = S.latitudeRange,
                    clamp = { it.coerceIn(-90.0, 90.0) },
                    defaultValue = 0.0
                )
                ValidatedNumberField(
                    value = initialLongitude,
                    onValueChange = onLongitudeChange,
                    label = S.longitudeLabel,
                    imeAction = ImeAction.Done,
                    validator = { it in -180.0..180.0 },
                    errorMessage = S.longitudeRange,
                    clamp = { it.coerceIn(-180.0, 180.0) },
                    defaultValue = 0.0
                )
            }
        }
    }
}

    // Language picker dialog
    if (showLanguageDialog) {
        val dialogModifier = if (isRedTintMode) {
            Modifier.graphicsLayer {
                colorFilter = ColorFilter.colorMatrix(RedTintMatrix)
            }
        } else {
            Modifier
        }
        AlertDialog(
            modifier = dialogModifier,
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(S.changeLanguage) },
            text = {
                Column {
                    AppLocale.entries.forEach { locale ->
                        TextButton(
                            onClick = {
                                onLocaleChange(locale)
                                showLanguageDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${locale.flag}  ${locale.displayName}", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(S.dismiss)
                }
            }
        )
    }

    // System Parameters Dialog
    if (showSystemParamsDialog) {
        val dialogModifier = if (isRedTintMode) {
            Modifier.graphicsLayer {
                colorFilter = ColorFilter.colorMatrix(RedTintMatrix)
            }
        } else {
            Modifier
        }
        AlertDialog(
            modifier = dialogModifier,
            onDismissRequest = { showSystemParamsDialog = false },
            title = { Text(S.systemParameters) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(S.redTintMode, style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = isRedTintMode,
                            onCheckedChange = onRedTintModeChange
                        )
                    }

                    Button(
                        onClick = onReplayTutorial,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(S.replayTutorial)
                    }

                    Button(
                        onClick = { showLanguageDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Text(S.changeLanguage)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSystemParamsDialog = false }) {
                    Text(S.dismiss)
                }
            }
        )
    }

    if (showResetSensorDialog) {
        val dialogModifier = if (isRedTintMode) {
            Modifier.graphicsLayer { colorFilter = ColorFilter.colorMatrix(RedTintMatrix) }
        } else Modifier
        AlertDialog(
            modifier = dialogModifier,
            onDismissRequest = { showResetSensorDialog = false },
            title = { Text(S.resetConfirmTitle) },
            text = { Text(S.resetConfirmText) },
            confirmButton = {
                TextButton(onClick = {
                    onResetSensorCal()
                    showResetSensorDialog = false
                }) { Text(S.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showResetSensorDialog = false }) { Text(S.cancel) }
            }
        )
    }

    if (showResetHorizonDialog) {
        val dialogModifier = if (isRedTintMode) {
            Modifier.graphicsLayer { colorFilter = ColorFilter.colorMatrix(RedTintMatrix) }
        } else Modifier
        AlertDialog(
            modifier = dialogModifier,
            onDismissRequest = { showResetHorizonDialog = false },
            title = { Text(S.resetConfirmTitle) },
            text = { Text(S.resetConfirmText) },
            confirmButton = {
                TextButton(onClick = {
                    onResetHorizon()
                    showResetHorizonDialog = false
                }) { Text(S.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showResetHorizonDialog = false }) { Text(S.cancel) }
            }
        )
    }

    if (showSolverModeHelpDialog) {
        val dialogModifier = if (isRedTintMode) {
            Modifier.graphicsLayer { colorFilter = ColorFilter.colorMatrix(RedTintMatrix) }
        } else Modifier
        AlertDialog(
            modifier = dialogModifier,
            onDismissRequest = { showSolverModeHelpDialog = false },
            title = { Text(S.helpSolverModeTitle) },
            text = { Text(S.helpSolverModeText) },
            confirmButton = {
                TextButton(onClick = { showSolverModeHelpDialog = false }) { Text(S.confirm) }
            }
        )
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

@Composable
fun TimeSyncStatus() {
    val syncAgePair by produceState<Pair<Long, Boolean>?>(initialValue = TimeSynchronizer.getSyncAgeMillis()) {
        while(true) {
            value = TimeSynchronizer.getSyncAgeMillis()
            kotlinx.coroutines.delay(60000)
        }
    }
    
    val text = if (syncAgePair == null) {
        S.timeSyncNever
    } else {
        val ageMillis = syncAgePair!!.first
        val isReliable = syncAgePair!!.second
        val hours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(ageMillis)
        val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ageMillis) % 60
        S.timeSyncStatus(hours, minutes, isReliable)
    }

    val color = if (syncAgePair == null) {
        MaterialTheme.colorScheme.error
    } else {
        val ageHrs = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(syncAgePair!!.first)
        when {
            ageHrs < 24 -> MaterialTheme.colorScheme.onSurfaceVariant
            ageHrs < 72 -> androidx.compose.ui.graphics.Color(0xFFFFA000) // Amber
            else -> MaterialTheme.colorScheme.error
        }
    }
    
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.SemiBold)
}
