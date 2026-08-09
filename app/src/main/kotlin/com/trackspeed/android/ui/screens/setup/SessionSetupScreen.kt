package com.trackspeed.android.ui.screens.setup

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.model.GatePosition
import com.trackspeed.android.model.StartType
import com.trackspeed.android.model.TestPreset
import com.trackspeed.android.protocol.TimingRole
import com.trackspeed.android.ui.theme.*
import com.trackspeed.android.ui.util.localizedDescription
import com.trackspeed.android.ui.util.localizedDisplayName
import com.trackspeed.android.ui.util.localizedName
import com.trackspeed.android.ui.util.localizedShortName
import com.trackspeed.android.ui.util.localizedTips
import java.util.Locale
import com.trackspeed.android.R

private val AccentOrange = Color(0xFFFF9500)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSetupScreen(
    onNavigateBack: () -> Unit,
    onStartSession: (
        distance: Double,
        startType: String,
        athleteIds: List<String>,
        numberOfGates: Int,
        gateDistances: List<Double>,
        hostRole: String
    ) -> Unit,
    onAddAthlete: () -> Unit = {},
    viewModel: SessionSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedStartType = StartType.fromRawValue(uiState.selectedStartType)
    val selectedStartTypeLabel = startTypeShortLabel(selectedStartType)
    val distanceLabel = if (uiState.selectedDistance == uiState.selectedDistance.toInt().toDouble()) {
        stringResource(R.string.setup_distance_meters, uiState.selectedDistance.toInt())
    } else {
        stringResource(R.string.setup_distance_meters_decimal, uiState.selectedDistance)
    }
    val athleteSummary = when (uiState.selectedAthleteIds.size) {
        0 -> stringResource(R.string.setup_no_athletes)
        1 -> stringResource(R.string.setup_one_athlete)
        else -> stringResource(R.string.setup_athlete_count, uiState.selectedAthleteIds.size)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.setup_start_session_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.common_cancel),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .border(1.dp, BorderSubtle)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (uiState.isSoloMode) {
                        stringResource(R.string.setup_solo_footer_summary)
                    } else {
                        stringResource(
                            R.string.setup_footer_summary,
                            selectedStartTypeLabel,
                            uiState.selectedGateCount,
                            athleteSummary,
                            distanceLabel
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    maxLines = 1
                )
                Button(
                    onClick = {
                        onStartSession(
                            uiState.selectedDistance,
                            uiState.selectedStartType,
                            uiState.selectedAthleteIds.toList(),
                            uiState.selectedGateCount,
                            uiState.gatePositions.map { it.distance },
                            uiState.selectedHostRole.value
                        )
                    },
                    enabled = uiState.isReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentNavy,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = stringResource(
                            if (uiState.isSoloMode) {
                                R.string.setup_start_solo_mode
                            } else {
                                R.string.setup_continue_to_connect
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .gradientBackground()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 8.dp,
                end = 20.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Text(
                    text = stringResource(
                        if (uiState.isSoloMode) {
                            R.string.setup_intro_solo
                        } else if (uiState.allowsSolo) {
                            R.string.setup_intro_with_solo
                        } else {
                            R.string.setup_intro_multi
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            item {
                CompactSetupSection(
                    title = stringResource(R.string.setup_start_type),
                    icon = Icons.AutoMirrored.Filled.DirectionsRun
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.availableStartTypes.forEachIndexed { index, startType ->
                            SessionStartOptionChip(
                                title = startTypeShortLabel(startType),
                                icon = startTypeIcon(startType),
                                selected = !uiState.isSoloMode && uiState.selectedStartType == startType.rawValue,
                                onClick = {
                                    viewModel.selectSoloMode(false)
                                    viewModel.selectStartType(startType.rawValue)
                                }
                            )
                            if (index == 0 && uiState.allowsSolo) {
                                SessionStartOptionChip(
                                    title = stringResource(R.string.home_solo_mode),
                                    icon = Icons.Outlined.PhoneAndroid,
                                    selected = uiState.isSoloMode,
                                    onClick = { viewModel.selectSoloMode(true) }
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.isSoloMode) {
                item {
                    CompactSetupSection(
                        title = stringResource(R.string.home_solo_mode),
                        icon = Icons.Outlined.PhoneAndroid
                    ) {
                        Text(
                            text = stringResource(R.string.setup_solo_place_phone),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.setup_solo_explanation),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                item {
                    CompactSetupSection(
                        title = stringResource(R.string.setup_gates),
                        icon = Icons.Outlined.Flag
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        R.string.setup_timing_gates,
                                        uiState.selectedGateCount
                                    ),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = stringResource(R.string.setup_one_phone_per_gate),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            IconButton(
                                onClick = { viewModel.selectGateCount(uiState.selectedGateCount - 1) },
                                enabled = uiState.selectedGateCount > 2
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.setup_remove_gate))
                            }
                            Text(
                                text = uiState.selectedGateCount.toString(),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.width(28.dp),
                                textAlign = TextAlign.Center
                            )
                            IconButton(
                                onClick = { viewModel.selectGateCount(uiState.selectedGateCount + 1) },
                                enabled = uiState.selectedGateCount < 6
                            ) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.setup_add_gate))
                            }
                        }
                    }
                }

                item {
                    CompactSetupSection(
                        title = stringResource(R.string.setup_athletes),
                        icon = Icons.Default.Person
                    ) {
                        if (uiState.athletes.isEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.setup_add_athletes_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                SessionSetupAddAthleteButton(onClick = onAddAthlete)
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                uiState.athletes.forEach { athlete ->
                                    val selected = athlete.id in uiState.selectedAthleteIds
                                    SessionAthleteChip(
                                        athlete = athlete,
                                        selected = selected,
                                        onClick = { viewModel.toggleAthlete(athlete.id) }
                                    )
                                }
                                SessionSetupAddAthleteButton(onClick = onAddAthlete)
                            }
                        }
                    }
                }

                item {
                    CompactSetupSection(
                        title = stringResource(R.string.setup_distance),
                        icon = Icons.Outlined.Tune
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.distanceOptions.forEach { distance ->
                                SessionTextChoiceChip(
                                    title = distance.label,
                                    selected = uiState.customDistanceText.isBlank() &&
                                        kotlin.math.abs(uiState.selectedDistance - distance.meters) < 0.001,
                                    onClick = { viewModel.selectDistance(distance.meters) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = uiState.customDistanceText,
                            onValueChange = viewModel::setCustomDistance,
                            label = { Text(stringResource(R.string.setup_custom_distance)) },
                            suffix = { Text(stringResource(R.string.setup_meters)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSetupSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gunmetalCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary
            )
        }
        content()
    }
}

@Composable
private fun SessionStartOptionChip(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) AccentNavy else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = if (selected) AccentNavy else BorderSubtle,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val contentColor = if (selected) Color.White else TextPrimary
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(17.dp))
        Text(title, color = contentColor, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SessionTextChoiceChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = title,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) AccentNavy else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, if (selected) AccentNavy else BorderSubtle, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        color = if (selected) Color.White else TextPrimary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
private fun SessionAthleteChip(
    athlete: AthleteEntity,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) AccentNavy else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(athleteColorFromString(athlete.color)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = athlete.displayName.take(1).uppercase(Locale.getDefault()),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = athlete.displayName,
            color = if (selected) Color.White else TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun SessionSetupAddAthleteButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = AccentNavy, modifier = Modifier.size(17.dp))
        Text(stringResource(R.string.athlete_chip_add), color = AccentNavy, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun startTypeShortLabel(startType: StartType): String = when (startType) {
    StartType.FLYING -> stringResource(R.string.setup_start_flying)
    StartType.TOUCH_RELEASE -> stringResource(R.string.setup_start_touch)
    StartType.COUNTDOWN -> stringResource(R.string.setup_start_countdown)
    StartType.VOICE_COMMAND -> stringResource(R.string.setup_start_voice)
    StartType.IN_FRAME -> stringResource(R.string.setup_start_in_frame)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacySessionSetupScreen(
    onNavigateBack: () -> Unit,
    onStartSession: (
        distance: Double,
        startType: String,
        athleteIds: List<String>,
        numberOfGates: Int,
        gateDistances: List<Double>,
        hostRole: String
    ) -> Unit,
    onAddAthlete: () -> Unit = {},
    viewModel: SessionSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shouldShowSecondaryPhoneJoinTip by viewModel.shouldShowSecondaryPhoneJoinTip.collectAsStateWithLifecycle()
    val sessionTitle = uiState.preset?.localizedName()
        ?: stringResource(R.string.setup_new_session)
    val addAthleteFromSetup = {
        viewModel.prepareToAddAthleteFromSetup()
        onAddAthlete()
    }

    LaunchedEffect(uiState.currentStep, shouldShowSecondaryPhoneJoinTip) {
        if (uiState.currentStep == SetupStep.CONNECT && shouldShowSecondaryPhoneJoinTip) {
            viewModel.trackSecondaryPhoneJoinTipShown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().gradientBackground()) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = sessionTitle,
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
                            contentDescription = stringResource(R.string.common_cancel),
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
                        uiState.selectedAthleteIds.toList(),
                        uiState.selectedGateCount,
                        uiState.gatePositions.map { it.distance },
                        uiState.selectedHostRole.value
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
                    val initialIndex = uiState.activeSteps.indexOf(initialState)
                    val targetIndex = uiState.activeSteps.indexOf(targetState)
                    if (targetIndex > initialIndex) {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    } else {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    }
                },
                label = "step_content"
            ) { step ->
                when (step) {
                    SetupStep.INFO -> PresetInfoStep(
                        preset = uiState.preset,
                        selectedDistance = uiState.selectedDistance,
                        distanceOptions = uiState.distanceOptions,
                        customText = uiState.customDistanceText,
                        selectedGateCount = uiState.selectedGateCount,
                        gatePositions = uiState.gatePositions,
                        onSelectDistance = viewModel::selectDistance,
                        onCustomTextChange = viewModel::setCustomDistance
                    )
                    SetupStep.ATHLETES -> AthleteSelectionStep(
                        athletes = uiState.athletes,
                        selectedIds = uiState.selectedAthleteIds,
                        selectedDistance = uiState.selectedDistance,
                        selectedStartType = uiState.selectedStartType,
                        onToggle = viewModel::toggleAthlete,
                        onClearSelection = viewModel::clearAthletes,
                        onAddAthlete = addAthleteFromSetup
                    )
                    SetupStep.DISTANCE -> DistanceSelectionStep(
                        selectedDistance = uiState.selectedDistance,
                        distanceOptions = uiState.distanceOptions,
                        customText = uiState.customDistanceText,
                        onSelectPreset = viewModel::selectDistance,
                        onCustomTextChange = viewModel::setCustomDistance
                    )
                    SetupStep.START_TYPE -> StartTypeSelectionStep(
                        selectedStartType = uiState.selectedStartType,
                        availableStartTypes = uiState.availableStartTypes,
                        onSelect = viewModel::selectStartType
                    )
                    SetupStep.GATE_COUNT -> GateCountSelectionStep(
                        selectedGateCount = uiState.selectedGateCount,
                        options = uiState.gateCountOptions,
                        distance = uiState.selectedDistance,
                        preset = uiState.preset,
                        gatePositions = uiState.gatePositions,
                        onSelect = viewModel::selectGateCount
                    )
                    SetupStep.CONNECT -> ConnectPhonesStep(
                        gateCount = uiState.selectedGateCount,
                        gatePositions = uiState.gatePositions,
                        selectedHostRole = uiState.selectedHostRole,
                        onHostRoleSelected = viewModel::selectHostRole,
                        startsPairingNext = uiState.currentStep == uiState.activeSteps.last(),
                        showSecondaryPhoneJoinTip = shouldShowSecondaryPhoneJoinTip,
                        onDismissSecondaryPhoneJoinTip = viewModel::dismissSecondaryPhoneJoinTip
                    )
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

// -- Preset Info Step --

@Composable
private fun PresetInfoStep(
    preset: TestPreset?,
    selectedDistance: Double,
    distanceOptions: List<PresetDistance>,
    customText: String,
    selectedGateCount: Int,
    gatePositions: List<GatePosition>,
    onSelectDistance: (Double) -> Unit,
    onCustomTextChange: (String) -> Unit
) {
    if (preset == null) {
        DistanceSelectionStep(
            selectedDistance = selectedDistance,
            distanceOptions = distanceOptions,
            customText = customText,
            onSelectPreset = onSelectDistance,
            onCustomTextChange = onCustomTextChange
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = preset.localizedName(),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = if (preset.hasSelectableDistance) {
                stringResource(R.string.setup_select_distance_below)
            } else {
                preset.distanceDisplay
            },
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        if (preset.hasSelectableDistance) {
            DistanceSelectionStep(
                selectedDistance = selectedDistance,
                distanceOptions = distanceOptions,
                customText = customText,
                onSelectPreset = onSelectDistance,
                onCustomTextChange = onCustomTextChange,
                compact = true
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        GateLayoutCard(
            gatePositions = gatePositions,
            isFlying = preset.isFlying,
            selectedGateCount = selectedGateCount
        )

        Spacer(modifier = Modifier.height(16.dp))

        SetupSummaryCard(
            phoneCount = selectedGateCount,
            startType = preset.defaultStartType
        )

        val localizedTips = preset.localizedTips()
        if (localizedTips.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            TipsCard(
                title = stringResource(
                    if (preset.isFlying) R.string.setup_important else R.string.setup_tips
                ),
                tips = localizedTips,
                warning = preset.isFlying
            )
        }
    }
}

@Composable
private fun GateLayoutCard(
    gatePositions: List<GatePosition>,
    isFlying: Boolean,
    selectedGateCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.setup_section_label),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = TextMuted
            )
            if (isFlying) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.setup_runup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentOrange
                )
            }
            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                gatePositions.forEachIndexed { index, position ->
                    GateMarker(
                        label = gateLabel(index, gatePositions.size),
                        distanceLabel = gateDistanceLabel(position),
                        tint = when (index) {
                            0 -> AccentGreen
                            gatePositions.lastIndex -> AccentBlue
                            else -> AccentOrange
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (selectedGateCount == 1) {
                    stringResource(R.string.setup_single_phone_lap_timing)
                } else {
                    pluralStringResource(
                        R.plurals.setup_phones_required,
                        selectedGateCount,
                        selectedGateCount
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun GateMarker(
    label: String,
    distanceLabel: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.PhoneAndroid,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = distanceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun SetupSummaryCard(
    phoneCount: Int,
    startType: StartType
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SetupSummaryItem(
                icon = Icons.Outlined.PhoneAndroid,
                label = if (phoneCount == 1) {
                    stringResource(R.string.common_solo)
                } else {
                    pluralStringResource(R.plurals.setup_phone_count, phoneCount, phoneCount)
                },
                color = AccentBlue
            )
            SetupSummaryItem(
                icon = startTypeIcon(startType),
                label = startType.localizedShortName(),
                color = if (startType == StartType.FLYING) AccentOrange else AccentGreen
            )
        }
    }
}

@Composable
private fun SetupSummaryItem(
    icon: ImageVector,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TipsCard(
    title: String,
    tips: List<String>,
    warning: Boolean
) {
    val tint = if (warning) AccentOrange else AccentBlue
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = tint
            )
            tips.forEach { tip ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = if (warning) Icons.Default.Timer else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        lineHeight = 18.sp
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
    selectedDistance: Double,
    selectedStartType: String,
    onToggle: (String) -> Unit,
    onClearSelection: () -> Unit,
    onAddAthlete: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.setup_training_title),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
        )

        Text(
            text = stringResource(R.string.setup_training_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        if (athletes.isEmpty()) {
            EmptyAthletesPlaceholder(onAddAthlete = onAddAthlete)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.setup_select_athletes_count, selectedIds.size),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = TextMuted,
                    modifier = Modifier.weight(1f)
                )

                Card(
                    onClick = onAddAthlete,
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.14f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.athlete_chip_add),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = AccentBlue
                        )
                    }
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SkipAthleteRow(
                        isSelected = selectedIds.isEmpty(),
                        onClick = onClearSelection
                    )
                }

                items(athletes, key = { it.id }) { athlete ->
                    AthleteRow(
                        athlete = athlete,
                        isSelected = athlete.id in selectedIds,
                        selectedDistance = selectedDistance,
                        selectedStartType = selectedStartType,
                        onClick = { onToggle(athlete.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAthletesPlaceholder(
    onAddAthlete: () -> Unit
) {
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
            text = stringResource(R.string.setup_no_athletes_added),
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_no_athletes_description),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            onClick = onAddAthlete,
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AccentBlue)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.setup_add_first_athlete),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SkipAthleteRow(
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val borderColor = if (isSelected) AccentGreen else BorderSubtle
    val borderWidth = if (isSelected) 2.dp else 0.5.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) AccentGreen.copy(alpha = 0.10f) else SurfaceDark)
            .border(borderWidth, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(TextMuted.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PersonOff,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = stringResource(R.string.setup_skip_athletes),
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = stringResource(
                if (isSelected) R.string.common_selected else R.string.common_not_selected
            ),
            tint = if (isSelected) AccentGreen else TextMuted,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun AthleteRow(
    athlete: AthleteEntity,
    isSelected: Boolean,
    selectedDistance: Double,
    selectedStartType: String,
    onClick: () -> Unit
) {
    val athleteColor = athleteColorFromString(athlete.color)
    val personalBest = setupPersonalBestFor(
        athlete = athlete,
        selectedDistance = selectedDistance,
        selectedStartType = selectedStartType
    )
    val shape = RoundedCornerShape(20.dp)
    val borderColor = if (isSelected) AccentGreen else BorderSubtle
    val borderWidth = if (isSelected) 2.dp else 0.5.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceDark)
            .border(borderWidth, borderColor, shape)
            .clickable(onClick = onClick)
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

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = athlete.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1
            )

            when {
                personalBest != null -> {
                    Text(
                        text = stringResource(
                            R.string.setup_personal_best_value,
                            stringResource(
                                R.string.common_seconds_value,
                                String.format(Locale.getDefault(), "%.3f", personalBest)
                            )
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentGreen,
                        maxLines = 1
                    )
                }
                !athlete.nickname.isNullOrBlank() -> {
                    Text(
                        text = athlete.nickname,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1
                    )
                }
            }
        }

        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = stringResource(
                if (isSelected) R.string.common_selected else R.string.common_not_selected
            ),
            tint = if (isSelected) AccentGreen else TextMuted,
            modifier = Modifier.size(26.dp)
        )
    }
}

private fun setupPersonalBestFor(
    athlete: AthleteEntity,
    selectedDistance: Double,
    selectedStartType: String
): Double? {
    val personalBests = athlete.personalBests()
    if (personalBests.isEmpty()) return null

    val canonicalKey = AthleteEntity.prKey(selectedDistance, selectedStartType)
    val distanceLabel = setupDistanceLabel(selectedDistance)
    val legacyDistanceLabel = legacySetupDistanceLabel(selectedDistance)

    return personalBests[canonicalKey]
        ?: personalBests[distanceLabel]
        ?: personalBests[legacyDistanceLabel]
}

private fun setupDistanceLabel(distance: Double): String {
    return if (kotlin.math.abs(distance - distance.toInt()) < 0.0001) {
        "${distance.toInt()}m"
    } else {
        String.format(Locale.US, "%.1fm", distance)
    }
}

private fun legacySetupDistanceLabel(distance: Double): String {
    return if (kotlin.math.abs(distance - 36.576) <= 0.5) {
        "40yd"
    } else {
        setupDistanceLabel(distance)
    }
}

// -- Step 2: Distance Selection --

@Composable
private fun DistanceSelectionStep(
    selectedDistance: Double,
    distanceOptions: List<PresetDistance>,
    customText: String,
    onSelectPreset: (Double) -> Unit,
    onCustomTextChange: (String) -> Unit,
    compact: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier else Modifier.fillMaxSize())
            .then(if (compact) Modifier else Modifier.verticalScroll(rememberScrollState())),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!compact) {
            Text(
                text = stringResource(R.string.setup_select_distance),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(((distanceOptions.size / 3 + 1) * 60).dp)
        ) {
            items(distanceOptions) { preset ->
                val isSelected = customText.isEmpty() && selectedDistance == preset.meters
                DistanceChip(
                    label = preset.label,
                    isSelected = isSelected,
                    onClick = { onSelectPreset(preset.meters) }
                )
            }
        }

        Spacer(modifier = Modifier.height(if (compact) 18.dp else 32.dp))

        Text(
            text = stringResource(R.string.setup_or_enter_custom),
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
                    Text(stringResource(R.string.timing_label_distance), color = TextMuted)
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
                text = stringResource(R.string.setup_meters),
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
    availableStartTypes: List<StartType>,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.setup_how_to_start),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            availableStartTypes.forEach { startType ->
                StartTypeCard(
                    title = startType.localizedDisplayName(),
                    description = startType.localizedDescription(),
                    icon = startTypeIcon(startType),
                    isSelected = selectedStartType == startType.rawValue,
                    onClick = { onSelect(startType.rawValue) }
                )
            }
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
                    contentDescription = stringResource(R.string.common_selected),
                    tint = AccentGreen,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

// -- Gate Count Selection --

@Composable
private fun GateCountSelectionStep(
    selectedGateCount: Int,
    options: List<Int>,
    distance: Double,
    preset: TestPreset?,
    gatePositions: List<GatePosition>,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.setup_how_many_gates),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.setup_gate_count_description),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { count ->
                val positions = if (count == selectedGateCount) {
                    gatePositions
                } else {
                    previewGatePositions(preset, count, distance)
                }
                GateCountCard(
                    count = count,
                    positions = positions,
                    isSelected = selectedGateCount == count,
                    onClick = { onSelect(count) }
                )
            }
        }
    }
}

@Composable
private fun GateCountCard(
    count: Int,
    positions: List<GatePosition>,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(2.dp, AccentGreen, shape) else Modifier
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pluralStringResource(R.plurals.setup_phone_count, count, count),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (count <= 2) {
                        stringResource(R.string.setup_start_and_finish)
                    } else {
                        val splitCount = count - 2
                        pluralStringResource(R.plurals.setup_split_gate_count, splitCount, splitCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            positions.forEachIndexed { index, position ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) AccentGreen else TextMuted)
                    )
                    Text(
                        text = stringResource(
                            R.string.setup_gate_with_distance,
                            gateLabel(index, positions.size),
                            gateDistanceLabel(position)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
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
    val lastStepLabel = if (isMultiPhone) {
        stringResource(R.string.setup_start_pairing)
    } else {
        stringResource(R.string.setup_start_session)
    }

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
                        text = stringResource(R.string.common_back),
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
                    text = if (isLastStep) lastStepLabel else stringResource(R.string.common_next),
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
private fun ConnectPhonesStep(
    gateCount: Int,
    gatePositions: List<GatePosition>,
    selectedHostRole: TimingRole,
    onHostRoleSelected: (TimingRole) -> Unit,
    startsPairingNext: Boolean,
    showSecondaryPhoneJoinTip: Boolean,
    onDismissSecondaryPhoneJoinTip: () -> Unit
) {
    val timingPhoneText = pluralStringResource(
        R.plurals.setup_timing_phone_count,
        gateCount,
        gateCount
    )
    val requiredJoiners = if (selectedHostRole == TimingRole.CONTROL_ONLY) {
        gateCount
    } else {
        (gateCount - 1).coerceAtLeast(1)
    }
    val joinerText = stringResource(
        if (requiredJoiners == 1) R.string.setup_other_phone else R.string.setup_each_timing_phone
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.setup_connect_phones),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = if (selectedHostRole == TimingRole.CONTROL_ONLY) {
                stringResource(R.string.setup_control_connect_desc, timingPhoneText)
            } else {
                stringResource(R.string.setup_finish_connect_desc, joinerText)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        if (showSecondaryPhoneJoinTip) {
            SecondaryPhoneJoinTipCard(
                onDismiss = onDismissSecondaryPhoneJoinTip
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        HostRoleSelector(
            selectedHostRole = selectedHostRole,
            onSelect = onHostRoleSelected
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Gate layout diagram
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
                gatePositions.forEachIndexed { index, position ->
                    val tint = when (index) {
                        0 -> AccentGreen
                        gatePositions.lastIndex -> AccentBlue
                        else -> AccentOrange
                    }
                    GateMarker(
                        label = gateLabel(index, gatePositions.size),
                        distanceLabel = gateDistanceLabel(position),
                        tint = tint,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (selectedHostRole == TimingRole.CONTROL_ONLY) {
                if (startsPairingNext) {
                    stringResource(R.string.setup_control_pair_next, joinerText)
                } else {
                    stringResource(R.string.setup_control_pair_after, joinerText)
                }
            } else {
                if (startsPairingNext) {
                    stringResource(R.string.setup_finish_pair_next, joinerText)
                } else {
                    stringResource(R.string.setup_finish_pair_after, joinerText)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun SecondaryPhoneJoinTipCard(
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.10f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.PhoneAndroid,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(28.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.setup_other_phone_free),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
                Text(
                    text = stringResource(R.string.setup_other_phone_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
                Text(
                    text = stringResource(R.string.race_connection_help_close),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = AccentBlue,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun HostRoleSelector(
    selectedHostRole: TimingRole,
    onSelect: (TimingRole) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.setup_this_phone),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = TextMuted
            )
            Text(
                text = stringResource(
                    if (selectedHostRole == TimingRole.CONTROL_ONLY) {
                        R.string.setup_control_only
                    } else {
                        R.string.setup_finish_camera
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark.copy(alpha = 0.72f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HostRoleSegmentButton(
                title = stringResource(R.string.device_role_finish),
                icon = Icons.Outlined.Flag,
                isSelected = selectedHostRole == TimingRole.FINISH_LINE,
                onClick = { onSelect(TimingRole.FINISH_LINE) },
                modifier = Modifier.weight(1f)
            )
            HostRoleSegmentButton(
                title = stringResource(R.string.setup_control),
                icon = Icons.Outlined.Tune,
                isSelected = selectedHostRole == TimingRole.CONTROL_ONLY,
                onClick = { onSelect(TimingRole.CONTROL_ONLY) },
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = if (selectedHostRole == TimingRole.CONTROL_ONLY) {
                stringResource(R.string.setup_control_role_desc)
            } else {
                stringResource(R.string.setup_finish_role_desc)
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun HostRoleSegmentButton(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentBlue else Color.Transparent
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) TextPrimary else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (isSelected) TextPrimary else TextSecondary
            )
        }
    }
}

// -- Helpers --

private fun startTypeIcon(startType: StartType): ImageVector = when (startType) {
    StartType.FLYING -> Icons.AutoMirrored.Filled.DirectionsRun
    StartType.TOUCH_RELEASE -> Icons.Default.TouchApp
    StartType.COUNTDOWN -> Icons.Default.Timer
    StartType.VOICE_COMMAND -> Icons.Default.Mic
    StartType.IN_FRAME -> Icons.Default.PersonOff
}

@Composable
private fun gateLabel(index: Int, total: Int): String = when {
    total <= 1 -> stringResource(R.string.setup_phone)
    index == 0 -> stringResource(R.string.device_role_start)
    index == total - 1 -> stringResource(R.string.device_role_finish)
    else -> stringResource(R.string.setup_split_number, index)
}

private fun gateDistanceLabel(position: GatePosition): String {
    return if (position.distance == 0.0) {
        "0m"
    } else if (kotlin.math.abs(position.distance - 36.576) <= 0.5) {
        "40yd"
    } else if (position.distance == position.distance.toInt().toDouble()) {
        "${position.distance.toInt()}m"
    } else {
        "%.1fm".format(position.distance)
    }
}

private fun previewGatePositions(
    preset: TestPreset?,
    gateCount: Int,
    distance: Double
): List<GatePosition> {
    if (gateCount <= 1) {
        return listOf(GatePosition(distance = 0.0, label = "Phone"))
    }
    if (preset?.isFlying == true || preset == null) {
        return (0 until gateCount).map { index ->
            val denominator = (gateCount - 1).coerceAtLeast(1)
            val gateDistance = distance * index.toDouble() / denominator.toDouble()
            when (index) {
                0 -> GatePosition(distance = gateDistance, label = "Start")
                gateCount - 1 -> GatePosition(distance = gateDistance, label = "Finish")
                else -> GatePosition(distance = gateDistance, label = "Split $index")
            }
        }
    }

    val configured = preset.gatePositionsForDistance(distance)
    if (configured.size <= 2 || gateCount <= 2) {
        return listOfNotNull(configured.firstOrNull(), configured.lastOrNull()).distinctBy { it.id }
    }
    val optional = configured.drop(1).dropLast(1).filter { it.isOptional }
    return listOfNotNull(configured.firstOrNull()) +
        optional.take((gateCount - 2).coerceAtLeast(0)) +
        listOfNotNull(configured.lastOrNull())
}

private fun athleteColorFromString(color: String): Color {
    return when (color.lowercase()) {
        "blue" -> Color(0xFF2196F3)
        "red" -> Color(0xFFF44336)
        "green" -> Color(0xFF4CAF50)
        "orange" -> Color(0xFFFF9800)
        "purple" -> Color(0xFF9C27B0)
        "gray" -> Color(0xFF8E8E93)
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
                    distanceOptions = PRESET_DISTANCES,
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
                    availableStartTypes = StartType.entries,
                    onSelect = {}
                )
            }
        }
    }
}
