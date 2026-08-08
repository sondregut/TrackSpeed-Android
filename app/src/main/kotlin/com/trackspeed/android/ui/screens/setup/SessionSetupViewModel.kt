package com.trackspeed.android.ui.screens.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.analytics.AnalyticsEvent
import com.trackspeed.android.analytics.AnalyticsService
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.model.GatePosition
import com.trackspeed.android.model.StartType
import com.trackspeed.android.model.TestPreset
import com.trackspeed.android.protocol.TimingRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupStep(val index: Int) {
    INFO(0),
    ATHLETES(1),
    DISTANCE(2),
    START_TYPE(3),
    GATE_COUNT(4),
    CONNECT(5)
}

data class PresetDistance(
    val label: String,
    val meters: Double
)

private data class SessionSetupSelection(
    val customDistanceText: String,
    val startType: String,
    val gateCount: Int,
    val hostRole: TimingRole
)

val PRESET_DISTANCES = listOf(
    PresetDistance("10m", 10.0),
    PresetDistance("20m", 20.0),
    PresetDistance("30m", 30.0),
    PresetDistance("40yd", 36.576),
    PresetDistance("60m", 60.0),
    PresetDistance("100m", 100.0),
    PresetDistance("200m", 200.0)
)

data class SessionSetupUiState(
    val currentStep: SetupStep = SetupStep.ATHLETES,
    val activeSteps: List<SetupStep> = listOf(SetupStep.ATHLETES, SetupStep.DISTANCE, SetupStep.START_TYPE),
    val preset: TestPreset? = null,
    val isPresetFlow: Boolean = false,
    val isMultiPhone: Boolean = false,
    val isSinglePhone: Boolean = true,
    val allowsSolo: Boolean = false,
    val isSoloMode: Boolean = false,
    val athletes: List<AthleteEntity> = emptyList(),
    val selectedAthleteIds: Set<String> = emptySet(),
    val selectedDistance: Double = 30.0,
    val distanceOptions: List<PresetDistance> = PRESET_DISTANCES,
    val customDistanceText: String = "",
    val selectedStartType: String = "flying",
    val availableStartTypes: List<StartType> = StartType.entries,
    val selectedGateCount: Int = 1,
    val gateCountOptions: List<Int> = listOf(1),
    val gatePositions: List<GatePosition> = emptyList(),
    val selectedHostRole: TimingRole = TimingRole.FINISH_LINE,
    val isReady: Boolean = false
)

@HiltViewModel
class SessionSetupViewModel @Inject constructor(
    private val athleteDao: AthleteDao,
    private val subscriptionManager: SubscriptionManager,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val analyticsService: AnalyticsService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val presetId = savedStateHandle.get<String>("presetId")?.ifBlank { null }
    private val preset = presetId?.let { TestPreset.preset(it) }

    private val initialDistance = savedStateHandle.get<Float>("distance")
        ?.toDouble()?.takeIf { it > 0 }
    private val initialStartType = savedStateHandle.get<String>("startType")?.ifBlank { null }
    private val routeMinPhones = savedStateHandle.get<Int>("minPhones") ?: 2
    private val allowsSolo = savedStateHandle.get<Boolean>("allowsSolo") ?: false
    private val initialAthleteIds = savedStateHandle.get<String>("athleteIds")
        .orEmpty()
        .split(",")
        .filter { it.isNotBlank() }
        .toSet()
    private val minPhones = preset?.minPhones ?: if (routeMinPhones >= 2 || allowsSolo) 2 else 1
    private val maxPhones = preset?.maxPhones ?: if (routeMinPhones >= 2 || allowsSolo) 6 else 1
    private val routeGateCount = savedStateHandle.get<Int>("numberOfGates")
    private val initialGateCount = (routeGateCount ?: minPhones).coerceIn(1, maxPhones)
    private val supportsSelectableGateCount = when {
        preset != null -> !preset.isSinglePhone && maxPhones > minPhones
        routeMinPhones >= 2 || allowsSolo -> true
        else -> false
    }

    private fun activeStepsFor(gateCount: Int): List<SetupStep> = buildList {
        if (preset != null) {
            add(SetupStep.INFO)
            if (preset.availableStartTypes.size > 1) add(SetupStep.START_TYPE)
            if (supportsSelectableGateCount) add(SetupStep.GATE_COUNT)
            if (!preset.isSinglePhone) add(SetupStep.ATHLETES)
            if (gateCount >= 2) add(SetupStep.CONNECT)
        } else {
            if (gateCount >= 2) {
                add(SetupStep.CONNECT)
                add(SetupStep.DISTANCE)
                add(SetupStep.START_TYPE)
                add(SetupStep.ATHLETES)
            } else {
                add(SetupStep.ATHLETES)
                add(SetupStep.DISTANCE)
                add(SetupStep.START_TYPE)
            }
        }
    }

    private val initialActiveSteps = activeStepsFor(initialGateCount)

    private val _currentStep = MutableStateFlow(initialActiveSteps.first())
    private val _selectedAthleteIds = MutableStateFlow(initialAthleteIds)
    private val _selectedDistance = MutableStateFlow(
        initialDistance
            ?: preset?.selectableDistances?.firstOrNull()
            ?: preset?.distance?.takeIf { it > 0.0 }
            ?: 30.0
    )
    private val _customDistanceText = MutableStateFlow("")
    private val _selectedStartType = MutableStateFlow(initialStartType ?: preset?.defaultStartType?.rawValue ?: "flying")
    private val _selectedGateCount = MutableStateFlow(initialGateCount)
    private val _selectedHostRole = MutableStateFlow(TimingRole.FINISH_LINE)
    private var didTrackSecondaryPhoneJoinTipShown = false
    private var knownAthleteIds: Set<String>? = null
    private var shouldAutoSelectNextAddedAthlete = false
    private var autoSelectBaselineAthleteIds: Set<String>? = null

    init {
        viewModelScope.launch {
            athleteDao.getAllAthletes().collect { athletes ->
                val currentIds = athletes.map { it.id }.toSet()
                val previousIds = knownAthleteIds
                val baselineIds = autoSelectBaselineAthleteIds ?: previousIds
                if (baselineIds != null && shouldAutoSelectNextAddedAthlete) {
                    athletes
                        .filter { it.id !in baselineIds }
                        .maxByOrNull { it.createdAt }
                        ?.let { athlete ->
                            _selectedAthleteIds.value = _selectedAthleteIds.value + athlete.id
                            shouldAutoSelectNextAddedAthlete = false
                            autoSelectBaselineAthleteIds = null
                        }
                }
                knownAthleteIds = currentIds
            }
        }
    }

    val uiState: StateFlow<SessionSetupUiState> = combine(
        _currentStep,
        athleteDao.getAllAthletes(),
        _selectedAthleteIds,
        _selectedDistance,
        combine(
            _customDistanceText,
            _selectedStartType,
            _selectedGateCount,
            _selectedHostRole
        ) { custom, start, gates, hostRole ->
            SessionSetupSelection(custom, start, gates, hostRole)
        }
    ) { step, athletes, selectedIds, distance, selection ->
        val customText = selection.customDistanceText
        val startType = selection.startType
        val gateCount = selection.gateCount
        val activeSteps = activeStepsFor(gateCount)
        SessionSetupUiState(
            currentStep = if (step in activeSteps) step else activeSteps.first(),
            activeSteps = activeSteps,
            preset = preset,
            isPresetFlow = preset != null,
            isMultiPhone = gateCount >= 2,
            isSinglePhone = gateCount == 1,
            allowsSolo = allowsSolo,
            isSoloMode = gateCount == 1,
            athletes = athletes,
            selectedAthleteIds = selectedIds,
            selectedDistance = distance,
            distanceOptions = distanceOptions(),
            customDistanceText = customText,
            selectedStartType = startType,
            availableStartTypes = preset?.availableStartTypes ?: StartType.entries,
            selectedGateCount = gateCount,
            gateCountOptions = gateCountOptions(),
            gatePositions = gatePositionsFor(gateCount, distance),
            selectedHostRole = selection.hostRole,
            isReady = gateCount == 1 ||
                ((customText.isBlank() || customText.toDoubleOrNull()?.let { it > 0 } == true) &&
                    (preset?.isSinglePhone == true || distance > 0))
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SessionSetupUiState(
            currentStep = initialActiveSteps.first(),
            selectedDistance = _selectedDistance.value,
            selectedStartType = _selectedStartType.value,
            selectedGateCount = initialGateCount,
            activeSteps = initialActiveSteps,
            preset = preset,
            isPresetFlow = preset != null,
            isMultiPhone = initialGateCount >= 2,
            isSinglePhone = initialGateCount == 1,
            allowsSolo = allowsSolo,
            isSoloMode = initialGateCount == 1,
            distanceOptions = distanceOptions(),
            availableStartTypes = preset?.availableStartTypes ?: StartType.entries,
            gateCountOptions = gateCountOptions(),
            gatePositions = gatePositionsFor(initialGateCount, _selectedDistance.value),
            selectedHostRole = _selectedHostRole.value,
            isReady = _selectedDistance.value > 0
        )
    )

    val currentStep: StateFlow<SetupStep> = _currentStep.asStateFlow()

    val shouldShowSecondaryPhoneJoinTip: StateFlow<Boolean> = combine(
        subscriptionManager.isProUser,
        settingsRepository.onboardingCompleted,
        sessionRepository.getTotalSessionCount(),
        settingsRepository.hasDismissedSecondaryPhoneJoinTip
    ) { isProUser, onboardingCompleted, sessionCount, dismissed ->
        isProUser && onboardingCompleted && sessionCount == 0 && !dismissed
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    fun goToNextStep() {
        val steps = activeStepsFor(_selectedGateCount.value)
        val currentIndex = steps.indexOf(_currentStep.value)
        if (currentIndex >= 0 && currentIndex < steps.size - 1) {
            _currentStep.value = steps[currentIndex + 1]
        }
    }

    fun goToPreviousStep() {
        val steps = activeStepsFor(_selectedGateCount.value)
        val currentIndex = steps.indexOf(_currentStep.value)
        if (currentIndex > 0) {
            _currentStep.value = steps[currentIndex - 1]
        }
    }

    fun toggleAthlete(athleteId: String) {
        val current = _selectedAthleteIds.value
        _selectedAthleteIds.value = if (athleteId in current) {
            current - athleteId
        } else {
            current + athleteId
        }
    }

    fun clearAthletes() {
        _selectedAthleteIds.value = emptySet()
    }

    fun prepareToAddAthleteFromSetup() {
        autoSelectBaselineAthleteIds = knownAthleteIds ?: uiState.value.athletes.map { it.id }.toSet()
        shouldAutoSelectNextAddedAthlete = true
    }

    fun selectDistance(meters: Double) {
        _selectedDistance.value = meters
        _customDistanceText.value = ""
    }

    fun setCustomDistance(text: String) {
        _customDistanceText.value = text
        text.toDoubleOrNull()?.let { value ->
            if (value > 0) _selectedDistance.value = value
        }
    }

    fun selectStartType(startType: String) {
        _selectedStartType.value = startType
    }

    fun selectGateCount(gateCount: Int) {
        if (maxPhones < 2) return
        _selectedGateCount.value = gateCount.coerceIn(2.coerceAtLeast(minPhones), maxPhones)
    }

    fun selectSoloMode(enabled: Boolean) {
        if (!allowsSolo) return
        _selectedGateCount.value = if (enabled) {
            1
        } else {
            (routeGateCount ?: 2).coerceIn(2, maxPhones)
        }
    }

    fun selectHostRole(hostRole: TimingRole) {
        _selectedHostRole.value = hostRole
    }

    fun trackSecondaryPhoneJoinTipShown() {
        if (didTrackSecondaryPhoneJoinTipShown) return
        didTrackSecondaryPhoneJoinTipShown = true
        analyticsService.track(
            AnalyticsEvent.SECONDARY_PHONE_JOIN_TIP_SHOWN,
            mapOf(
                "source" to SECONDARY_PHONE_JOIN_TIP_SOURCE,
                "transport" to ANDROID_TIMING_TRANSPORT
            )
        )
    }

    fun dismissSecondaryPhoneJoinTip() {
        analyticsService.track(
            AnalyticsEvent.SECONDARY_PHONE_JOIN_TIP_DISMISSED,
            mapOf("source" to SECONDARY_PHONE_JOIN_TIP_SOURCE)
        )
        viewModelScope.launch {
            settingsRepository.setHasDismissedSecondaryPhoneJoinTip(true)
        }
    }

    private fun distanceOptions(): List<PresetDistance> {
        val selectable = preset?.selectableDistances
        return if (!selectable.isNullOrEmpty()) {
            selectable.map { meters -> PresetDistance("${meters.toInt()}m", meters) }
        } else {
            PRESET_DISTANCES
        }
    }

    private fun gateCountOptions(): List<Int> {
        return if (supportsSelectableGateCount) {
            ((2.coerceAtLeast(minPhones))..maxPhones).toList()
        } else {
            listOf(initialGateCount)
        }
    }

    private fun gatePositionsFor(gateCount: Int, distance: Double): List<GatePosition> {
        val selectedPreset = preset
        if (gateCount <= 1) return listOf(GatePosition(distance = 0.0, label = "Phone"))

        if (selectedPreset == null) {
            return (0 until gateCount).map { index ->
                val denominator = (gateCount - 1).coerceAtLeast(1)
                val gateDistance = distance * index.toDouble() / denominator.toDouble()
                when (index) {
                    0 -> GatePosition(distance = 0.0, label = "Start")
                    gateCount - 1 -> GatePosition(distance = distance, label = "Finish (${distance.toInt()}m)")
                    else -> GatePosition(distance = gateDistance, label = "Split $index (${gateDistance.toInt()}m)")
                }
            }
        }

        if (selectedPreset.isFlying) {
            return (0 until gateCount).map { index ->
                val denominator = (gateCount - 1).coerceAtLeast(1)
                val gateDistance = distance * index.toDouble() / denominator.toDouble()
                when (index) {
                    0 -> GatePosition(distance = gateDistance, label = "Start (Phone 1)")
                    gateCount - 1 -> GatePosition(distance = gateDistance, label = "Finish (${distance.toInt()}m)")
                    else -> GatePosition(distance = gateDistance, label = "Split $index (${gateDistance.toInt()}m)")
                }
            }
        }

        val configured = selectedPreset.gatePositionsForDistance(distance)
        if (configured.size <= 2 || gateCount <= 2) {
            return listOfNotNull(configured.firstOrNull(), configured.lastOrNull()).distinctBy { it.id }
        }

        val optional = configured.drop(1).dropLast(1).filter { it.isOptional }
        return listOfNotNull(configured.firstOrNull()) +
            optional.take((gateCount - 2).coerceAtLeast(0)) +
            listOfNotNull(configured.lastOrNull())
    }

    private companion object {
        private const val SECONDARY_PHONE_JOIN_TIP_SOURCE = "create_session_connect"
        private const val ANDROID_TIMING_TRANSPORT = "Bluetooth"
    }
}
