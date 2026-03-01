package com.trackspeed.android.ui.screens.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.billing.PromoCodeError
import com.trackspeed.android.billing.PromoRedemptionResult
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.OnboardingProfile
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.data.model.UserRole
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.referral.ReferralService
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
    NOTIFICATION,          // 3
    TRACK_PREVIEW,         // 4
    FLYING_TIME,           // 5
    GOAL_TIME,             // 6
    GOAL_MOTIVATION,       // 7  - combined with TrackProgress (personalized chart + research)
    START_TYPES,           // 8
    MULTI_DEVICE,          // 9
    ATTRIBUTION,           // 10
    RATING,                // 11
    COMPETITOR_COMPARISON, // 12
    AUTH,                  // 13
    CAMERA_PERMISSION,     // 14
    PROFILE_SETUP,         // 15
    TRIAL_INTRO,           // 16
    TRIAL_REMINDER,        // 17
    PAYWALL,               // 18
    SPIN_WHEEL,            // 19
    COMPLETION;            // 20

    val progress: Float get() = ordinal.toFloat() / (entries.size - 1).toFloat()
    val showsProgressBar: Boolean get() = this != WELCOME && this != COMPLETION
    val showsBackButton: Boolean get() = ordinal > 0 && this != COMPLETION
}

sealed interface PromoRedemptionState {
    data object Idle : PromoRedemptionState
    data object Loading : PromoRedemptionState
    data class Success(val result: PromoRedemptionResult) : PromoRedemptionState
    data class Error(val message: String) : PromoRedemptionState
}

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val profile: OnboardingProfile = OnboardingProfile(),
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val promoRedemptionState: PromoRedemptionState = PromoRedemptionState.Idle,
    val referralCode: String = "",
    val referralLink: String = ""
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val application: Application,
    private val settingsRepository: SettingsRepository,
    private val subscriptionManager: SubscriptionManager,
    private val referralService: ReferralService
) : ViewModel() {

    companion object {
        private const val TAG = "OnboardingViewModel"
    }

    val onboardingCompletedFlow: Flow<Boolean> = settingsRepository.onboardingCompleted

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        // Load referral code on init
        viewModelScope.launch {
            try {
                val code = referralService.getOrCreateReferralCode()
                val link = referralService.getReferralLink()
                _uiState.update { it.copy(referralCode = code, referralLink = link) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load referral code: ${e.message}")
            }
        }
    }

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

            // Track referral signup if a referral code was entered during onboarding
            val enteredReferralCode = _uiState.value.profile.referralCode
            if (!enteredReferralCode.isNullOrBlank()) {
                try {
                    referralService.trackReferralSignup(enteredReferralCode)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to track referral signup: ${e.message}")
                }
            }

            // Process pending referral code from deeplink (trackspeed://invite/CODE)
            processPendingReferralCode()
        }
    }

    private suspend fun processPendingReferralCode() {
        val pendingCode = ReferralService.getPendingReferralCode(application) ?: return
        Log.d(TAG, "Processing pending referral code from deeplink: $pendingCode")
        ReferralService.clearPendingReferralCode(application)

        // First try as promo code
        try {
            subscriptionManager.redeemPromoCode(pendingCode, "deeplink")
            Log.d(TAG, "Pending referral code redeemed as promo code: $pendingCode")
            return
        } catch (e: Exception) {
            Log.d(TAG, "Not a promo code, trying as referral: ${e.message}")
        }

        // Fall back to tracking as referral
        try {
            referralService.trackReferralSignup(pendingCode)
            Log.d(TAG, "Pending referral code tracked as referral: $pendingCode")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process pending referral code: ${e.message}")
        }
    }

    /**
     * Submit a promo code for redemption via the backend.
     */
    fun submitPromoCode(code: String, source: String) {
        if (code.isBlank()) return

        _uiState.update { it.copy(promoRedemptionState = PromoRedemptionState.Loading) }

        viewModelScope.launch {
            try {
                val result = subscriptionManager.redeemPromoCode(code, source)
                _uiState.update {
                    it.copy(
                        promoRedemptionState = PromoRedemptionState.Success(result),
                        profile = it.profile.copy(promoCode = code)
                    )
                }
            } catch (e: PromoCodeError) {
                val message = when (e) {
                    is PromoCodeError.InvalidCode -> "Invalid or inactive promo code"
                    is PromoCodeError.Expired -> "This promo code has expired"
                    is PromoCodeError.MaxUsesReached -> "This promo code has reached its maximum uses"
                    is PromoCodeError.AlreadyRedeemed -> "You've already redeemed this code"
                    is PromoCodeError.RateLimited -> "Please wait before trying again"
                    is PromoCodeError.NetworkError -> "Network error. Please check your connection."
                }
                _uiState.update { it.copy(promoRedemptionState = PromoRedemptionState.Error(message)) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(promoRedemptionState = PromoRedemptionState.Error("Something went wrong. Please try again."))
                }
            }
        }
    }

    /**
     * Reset promo redemption state back to idle.
     */
    fun clearPromoRedemptionState() {
        _uiState.update { it.copy(promoRedemptionState = PromoRedemptionState.Idle) }
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
