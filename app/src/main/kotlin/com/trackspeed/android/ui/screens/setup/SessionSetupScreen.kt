package com.trackspeed.android.ui.screens.setup

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.ui.theme.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSetupScreen(
    onNavigateBack: () -> Unit,
    onStartSession: (distance: Double, startType: String, athleteIds: List<String>) -> Unit,
    onAddAthlete: () -> Unit = {},
    viewModel: SessionSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().gradientBackground()) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "New Session",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        bottomBar = {
            BottomBar(
                currentStep = uiState.currentStep,
                activeSteps = uiState.activeSteps,
                isMultiPhone = uiState.isMultiPhone,
                onBack = viewModel::goToPreviousStep,
                onNext = viewModel::goToNextStep,
                onStart = {
                    onStartSession(
                        uiState.selectedDistance,
                        uiState.selectedStartType,
                        uiState.selectedAthleteIds.toList()
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
        ) {
            StepIndicator(
                currentStep = uiState.currentStep,
                activeSteps = uiState.activeSteps,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )

            AnimatedContent(
                targetState = uiState.currentStep,
                transitionSpec = {
                    if (targetState.index > initialState.index) {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    } else {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    }
                },
                label = "step_content"
            ) { step ->
                when (step) {
                    SetupStep.ATHLETES -> AthleteSelectionStep(
                        athletes = uiState.athletes,
                        selectedIds = uiState.selectedAthleteIds,
                        onToggle = viewModel::toggleAthlete,
                        onAddAthlete = onAddAthlete
                    )
                    SetupStep.DISTANCE -> DistanceSelectionStep(
                        selectedDistance = uiState.selectedDistance,
                        customText = uiState.customDistanceText,
                        onSelectPreset = viewModel::selectDistance,
                        onCustomTextChange = viewModel::setCustomDistance
                    )
                    SetupStep.START_TYPE -> StartTypeSelectionStep(
                        selectedStartType = uiState.selectedStartType,
                        onSelect = viewModel::selectStartType
                    )
                    SetupStep.CONNECT -> ConnectPhonesStep()
                }
            }
        }
    }
    } // close Box
}

// -- Step Indicator --

@Composable
private fun StepIndicator(
    currentStep: SetupStep,
    activeSteps: List<SetupStep> = SetupStep.entries.toList(),
    modifier: Modifier = Modifier
) {
    val currentActiveIndex = activeSteps.indexOf(currentStep)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        activeSteps.forEachIndexed { index, step ->
            val isCompleted = index < currentActiveIndex
            val isCurrent = step == currentStep
            val isReachedOrCurrent = index <= currentActiveIndex

            val circleColor = if (isReachedOrCurrent) AccentGreen
                else TextMuted.copy(alpha = 0.3f)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(circleColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCompleted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (isReachedOrCurrent) TextPrimary else TextMuted
                        )
                    }
                }

                if (index < activeSteps.size - 1) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(2.dp)
                            .background(
                                if (index < currentActiveIndex) AccentGreen
                                else TextMuted.copy(alpha = 0.3f)
                            )
                    )
                }
            }
        }
    }
}

// -- Step 1: Athlete Selection --

@Composable
private fun AthleteSelectionStep(
    athletes: List<AthleteEntity>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onAddAthlete: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Who's training?",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )

        if (athletes.isEmpty()) {
            EmptyAthletesPlaceholder()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(athletes, key = { it.id }) { athlete ->
                    AthleteRow(
                        athlete = athlete,
                        isSelected = athlete.id in selectedIds,
                        onClick = { onToggle(athlete.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAthletesPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "No athletes added yet",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "You can continue without selecting athletes,\nor add them later.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
private fun AthleteRow(
    athlete: AthleteEntity,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val athleteColor = athleteColorFromString(athlete.color)
    val shape = RoundedCornerShape(20.dp)
    val borderColor = if (isSelected) AccentGreen else BorderSubtle
    val borderWidth = if (isSelected) 2.dp else 0.5.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceDark)
            .border(borderWidth, borderColor, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(athleteColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = athlete.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = athlete.nickname ?: athlete.name,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (isSelected) "Selected" else "Not selected",
            tint = if (isSelected) AccentGreen else TextMuted,
            modifier = Modifier.size(26.dp)
        )
    }
}

// -- Step 2: Distance Selection --

@Composable
private fun DistanceSelectionStep(
    selectedDistance: Double,
    customText: String,
    onSelectPreset: (Double) -> Unit,
    onCustomTextChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Select Distance",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(((PRESET_DISTANCES.size / 3 + 1) * 60).dp)
        ) {
            items(PRESET_DISTANCES) { preset ->
                val isSelected = customText.isEmpty() && selectedDistance == preset.meters
                DistanceChip(
                    label = preset.label,
                    isSelected = isSelected,
                    onClick = { onSelectPreset(preset.meters) }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "or enter custom:",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = customText,
                onValueChange = onCustomTextChange,
                placeholder = {
                    Text("Distance", color = TextMuted)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(120.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentBlue,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = TextMuted,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "meters",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun DistanceChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val bgColor = if (isSelected) AccentGreen else SurfaceDark
    val borderMod = if (isSelected) Modifier.border(2.dp, AccentGreen, shape) else Modifier

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .then(borderMod)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = if (isSelected) Color.Black else TextPrimary
        )
    }
}

// -- Step 3: Start Type Selection --

@Composable
private fun StartTypeSelectionStep(
    selectedStartType: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "How to start?",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StartTypeCard(
                title = "Flying Start",
                description = "Gate-triggered start. Timer starts when athlete crosses the gate line.",
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                isSelected = selectedStartType == "flying",
                onClick = { onSelect("flying") }
            )

            StartTypeCard(
                title = "Countdown",
                description = "3\u2026 2\u2026 1\u2026 BEEP! Visual countdown with automatic start.",
                icon = Icons.Default.Timer,
                isSelected = selectedStartType == "countdown",
                onClick = { onSelect("countdown") }
            )

            StartTypeCard(
                title = "Voice Command",
                description = "\"On your marks\u2026 Set\u2026 GO!\" Spoken commands like a real starter.",
                icon = Icons.Default.Mic,
                isSelected = selectedStartType == "voiceCommand",
                onClick = { onSelect("voiceCommand") }
            )

            StartTypeCard(
                title = "Touch Start",
                description = "Touch screen, hold, then lift finger to start the timer.",
                icon = Icons.Default.TouchApp,
                isSelected = selectedStartType == "touch",
                onClick = { onSelect("touch") }
            )

            StartTypeCard(
                title = "In-Frame Start",
                description = "Stand in front of the camera, step away to start.",
                icon = Icons.Default.PersonOff,
                isSelected = selectedStartType == "inFrame",
                onClick = { onSelect("inFrame") }
            )
        }
    }
}

@Composable
private fun StartTypeCard(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(
                    width = 2.dp,
                    color = AccentGreen,
                    shape = RoundedCornerShape(20.dp)
                ) else Modifier
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) AccentGreen else TextSecondary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = AccentGreen,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

// -- Bottom Bar --

@Composable
private fun BottomBar(
    currentStep: SetupStep,
    activeSteps: List<SetupStep> = SetupStep.entries.toList(),
    isMultiPhone: Boolean = false,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onStart: () -> Unit
) {
    val isFirstStep = currentStep == activeSteps.first()
    val isLastStep = currentStep == activeSteps.last()
    val lastStepLabel = if (isMultiPhone) "Start Pairing" else "Start Session"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundGradientBottom)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isFirstStep) {
            Card(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = TextMuted)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Back",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = TextPrimary
                    )
                }
            }
        }

        Card(
            onClick = if (isLastStep) onStart else onNext,
            modifier = Modifier
                .weight(if (isFirstStep) 1f else 1.5f)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isLastStep) AccentGreen else AccentBlue
            )
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLastStep) lastStepLabel else "Next",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (isLastStep) Color.Black else TextPrimary
                )
            }
        }
    }
}

// -- Step 4: Connect Phones (multi-phone only) --

@Composable
private fun ConnectPhonesStep() {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Connect Phones",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "This session requires 2 phones \u2014 one at the start line and one at the finish.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Two-phone gate layout diagram
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Start phone
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(AccentGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Start Line",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = AccentGreen
                    )
                }

                // Dashed line between phones
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "\u2022 \u2022 \u2022 \u2022 \u2022",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted,
                        letterSpacing = 4.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "BLE Sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                // Finish phone
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(AccentBlue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Finish Line",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = AccentBlue
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "On the next screen you\u2019ll pair the two phones using Bluetooth and synchronize their clocks for accurate timing.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            lineHeight = 22.sp
        )
    }
}

// -- Helpers --

private fun athleteColorFromString(color: String): Color {
    return when (color.lowercase()) {
        "blue" -> Color(0xFF2196F3)
        "red" -> Color(0xFFF44336)
        "green" -> Color(0xFF4CAF50)
        "orange" -> Color(0xFFFF9800)
        "purple" -> Color(0xFF9C27B0)
        "teal" -> Color(0xFF009688)
        "pink" -> Color(0xFFE91E63)
        "yellow" -> Color(0xFFFFEB3B)
        else -> Color(0xFF2196F3)
    }
}

// -- Previews --

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun SessionSetupPreview() {
    TrackSpeedTheme() {
        // Simplified preview without Hilt
        Scaffold(
            containerColor = BackgroundDark,
            bottomBar = {
                BottomBar(
                    currentStep = SetupStep.DISTANCE,
                    onBack = {},
                    onNext = {},
                    onStart = {}
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                StepIndicator(
                    currentStep = SetupStep.DISTANCE,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
                DistanceSelectionStep(
                    selectedDistance = 60.0,
                    customText = "",
                    onSelectPreset = {},
                    onCustomTextChange = {}
                )
            }
        }
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "Start Type Step"
)
@Composable
private fun StartTypeStepPreview() {
    TrackSpeedTheme() {
        Scaffold(
            containerColor = BackgroundDark,
            bottomBar = {
                BottomBar(
                    currentStep = SetupStep.START_TYPE,
                    onBack = {},
                    onNext = {},
                    onStart = {}
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                StepIndicator(
                    currentStep = SetupStep.START_TYPE,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
                StartTypeSelectionStep(
                    selectedStartType = "flying",
                    onSelect = {}
                )
            }
        }
    }
}
