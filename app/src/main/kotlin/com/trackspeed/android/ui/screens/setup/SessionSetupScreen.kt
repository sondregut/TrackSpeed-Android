package com.trackspeed.android.ui.screens.setup

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Speed
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
import com.trackspeed.android.ui.theme.TrackSpeedTheme

private val CardBackground = Color(0xFF2C2C2E)
private val SecondaryText = Color(0xFF8E8E93)
private val AccentGreen = Color(0xFF30D158)
private val AccentBlue = Color(0xFF0A84FF)
private val StepGray = Color(0xFF48484A)
private val SurfaceDark = Color(0xFF1C1C1E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSetupScreen(
    onNavigateBack: () -> Unit,
    onStartSession: (distance: Double, startType: String, athleteIds: List<String>) -> Unit,
    viewModel: SessionSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "New Session",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Black
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
                        onToggle = viewModel::toggleAthlete
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

            val circleColor = when {
                isCompleted -> AccentGreen
                isCurrent -> AccentBlue
                else -> StepGray
            }

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
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                }
            }

            if (index < activeSteps.size - 1) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(2.dp)
                        .background(
                            if (index < currentActiveIndex) AccentGreen
                            else StepGray
                        )
                )
            }
        }
    }
}

// -- Step 1: Athlete Selection --

@Composable
private fun AthleteSelectionStep(
    athletes: List<AthleteEntity>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Who's training?",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = Color.White,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = if (selectedIds.isEmpty()) "Optional - skip if solo practice"
            else "${selectedIds.size} selected",
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryText,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        if (athletes.isEmpty()) {
            EmptyAthletesPlaceholder()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = StepGray,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No athletes added yet",
            style = MaterialTheme.typography.bodyLarge,
            color = SecondaryText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "You can continue without selecting athletes,\nor add them from the Profile tab.",
            style = MaterialTheme.typography.bodySmall,
            color = StepGray,
            textAlign = TextAlign.Center
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(
                    width = 2.dp,
                    color = AccentGreen,
                    shape = RoundedCornerShape(16.dp)
                ) else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(athleteColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = athlete.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = athlete.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
                athlete.nickname?.let { nickname ->
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondaryText
                    )
                }
            }

            Icon(
                imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Person,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) AccentGreen else StepGray,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// -- Step 2: Distance Selection --

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistanceSelectionStep(
    selectedDistance: Double,
    customText: String,
    onSelectPreset: (Double) -> Unit,
    onCustomTextChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Select Distance",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = Color.White,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Choose a preset or enter custom",
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryText,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PRESET_DISTANCES.forEach { preset ->
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
            text = "Or enter custom distance",
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = customText,
                onValueChange = onCustomTextChange,
                placeholder = {
                    Text("Distance", color = StepGray)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(140.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = AccentBlue,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = StepGray,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "meters",
                style = MaterialTheme.typography.bodyMedium,
                color = SecondaryText
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
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentGreen else CardBackground
        ),
        modifier = if (isSelected) {
            Modifier.border(2.dp, AccentGreen, RoundedCornerShape(12.dp))
        } else {
            Modifier
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = if (isSelected) Color.Black else Color.White,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
        )
    }
}

// -- Step 3: Start Type Selection --

@Composable
private fun StartTypeSelectionStep(
    selectedStartType: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "How to start?",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = Color.White,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Choose the start method",
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryText,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        StartTypeCard(
            title = "Standing Start",
            description = "Timer starts when the athlete leaves the start line. Best for acceleration tests and combine drills.",
            icon = Icons.Outlined.Flag,
            isSelected = selectedStartType == "standing",
            onClick = { onSelect("standing") }
        )

        Spacer(modifier = Modifier.height(12.dp))

        StartTypeCard(
            title = "Flying Start",
            description = "Timer starts when the athlete crosses the first gate at speed. Best for max velocity testing.",
            icon = Icons.Outlined.Speed,
            isSelected = selectedStartType == "flying",
            onClick = { onSelect("flying") }
        )
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
                    shape = RoundedCornerShape(16.dp)
                ) else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
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
                tint = if (isSelected) AccentGreen else SecondaryText,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
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
            .background(Color.Black)
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
                colors = CardDefaults.cardColors(containerColor = StepGray)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Back",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White
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
                    color = if (isLastStep) Color.Black else Color.White
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
            color = Color.White,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "This session requires 2 phones \u2014 one at the start line and one at the finish.",
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryText,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Two-phone gate layout diagram
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
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
                        color = StepGray,
                        letterSpacing = 4.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "BLE Sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = StepGray
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
            color = SecondaryText,
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
    TrackSpeedTheme(darkTheme = true) {
        // Simplified preview without Hilt
        Scaffold(
            containerColor = Color.Black,
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
    TrackSpeedTheme(darkTheme = true) {
        Scaffold(
            containerColor = Color.Black,
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
