package com.trackspeed.android.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.OnboardingProfile
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.data.model.UserRole
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep {
    WELCOME,               // 0
    VALUE_PROPOSITION,     // 1
    HOW_IT_WORKS,          // 2
    TRACK_PREVIEW,         // 3  - NEW: visual track illustration
    TRACK_PROGRESS,        // 4  - NEW: progress chart preview
    FLYING_TIME,           // 5
    GOAL_TIME,             // 6
    GOAL_MOTIVATION,       // 7
    START_TYPES,           // 8
    MULTI_DEVICE,          // 9
    ATTRIBUTION,           // 10
    RATING,                // 11
    COMPETITOR_COMPARISON, // 12
    AUTH,                  // 13
    CAMERA_PERMISSION,     // 14 - NEW: camera permission request
    PROFILE_SETUP,         // 15 - NEW: name + team entry
    TRIAL_INTRO,           // 16
    TRIAL_REMINDER,        // 17
    NOTIFICATION,          // 18
    PAYWALL,               // 19
    PROMO_CODE,            // 20 - NEW: referral/promo code entry
    REFERRAL,              // 21 - NEW: invite friends for free months
    SPIN_WHEEL,            // 22
    COMPLETION;            // 23

    val progress: Float get() = ordinal.toFloat() / (entries.size - 1).toFloat()
    val showsProgressBar: Boolean get() = this != WELCOME && this != COMPLETION
    val showsBackButton: Boolean get() = ordinal > 0 && this != COMPLETION
}

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val profile: OnboardingProfile = OnboardingProfile(),
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val onboardingCompletedFlow: Flow<Boolean> = settingsRepository.onboardingCompleted

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    suspend fun isOnboardingCompleted(): Boolean {
        return settingsRepository.onboardingCompleted.first()
    }

    fun goForward() {
        _uiState.update { state ->
            val steps = OnboardingStep.entries
            val nextIndex = (state.currentStep.ordinal + 1).coerceAtMost(steps.size - 1)
            state.copy(currentStep = steps[nextIndex])
        }
    }

    fun goBack() {
        _uiState.update { state ->
            val steps = OnboardingStep.entries
            val prevIndex = (state.currentStep.ordinal - 1).coerceAtLeast(0)
            state.copy(currentStep = steps[prevIndex])
        }
    }

    fun goToStep(step: OnboardingStep) {
        _uiState.update { it.copy(currentStep = step) }
    }

    fun skipToHome() {
        completeOnboarding()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
            // Persist profile data
            _uiState.value.profile.let { profile ->
                profile.role?.let { settingsRepository.setUserRole(it.rawValue) }
                profile.discipline?.let { settingsRepository.setPrimaryEvent(it.rawValue) }
                profile.personalRecord?.let { settingsRepository.setPersonalRecord(it) }
                profile.flyingDistance?.let { settingsRepository.setFlyingDistance(it.rawValue) }
                profile.flyingPR?.let { settingsRepository.setFlyingPR(it) }
                profile.goalTime?.let { settingsRepository.setGoalTime(it) }
                profile.displayName?.let { settingsRepository.setDisplayName(it) }
                profile.teamName?.let { settingsRepository.setTeamName(it) }
                profile.promoCode?.let { settingsRepository.setPromoCode(it) }
                profile.referralCode?.let { settingsRepository.setReferralCode(it) }
            }
        }
    }

    fun setRole(role: UserRole) {
        _uiState.update { it.copy(profile = it.profile.copy(role = role)) }
    }

    fun setDiscipline(discipline: SportDiscipline) {
        _uiState.update { it.copy(profile = it.profile.copy(discipline = discipline)) }
    }

    fun setPersonalRecord(time: Double?) {
        _uiState.update { it.copy(profile = it.profile.copy(personalRecord = time)) }
    }

    fun setFlyingDistance(distance: FlyingDistance) {
        _uiState.update { it.copy(profile = it.profile.copy(flyingDistance = distance)) }
    }

    fun setFlyingPR(time: Double?) {
        _uiState.update { it.copy(profile = it.profile.copy(flyingPR = time)) }
    }

    fun setGoalTime(time: Double?) {
        _uiState.update { it.copy(profile = it.profile.copy(goalTime = time)) }
    }

    fun setAttribution(value: String) {
        _uiState.update { it.copy(profile = it.profile.copy(attribution = value)) }
    }

    fun setPromoCode(code: String) {
        _uiState.update { it.copy(profile = it.profile.copy(promoCode = code)) }
    }

    fun setDisplayName(name: String) {
        _uiState.update { it.copy(profile = it.profile.copy(displayName = name)) }
    }

    fun setTeamName(team: String) {
        _uiState.update { it.copy(profile = it.profile.copy(teamName = team)) }
    }

    fun setReferralCode(code: String) {
        _uiState.update { it.copy(profile = it.profile.copy(referralCode = code)) }
    }

    fun setAuthenticated(authenticated: Boolean) {
        _uiState.update { it.copy(isAuthenticated = authenticated) }
    }
}
