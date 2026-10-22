package com.trackspeed.android.ui.screens.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.analytics.AnalyticsEvent
import com.trackspeed.android.analytics.AnalyticsService
import com.trackspeed.android.cloud.AuthState
import com.trackspeed.android.cloud.isRealAuthenticated
import com.trackspeed.android.cloud.safeCloudErrorCode
import com.trackspeed.android.billing.PromoCodeError
import com.trackspeed.android.billing.PromoRedemptionResult
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.billing.EarlyReleaseAccess
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.OnboardingProfile
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.data.model.UserRole
import com.trackspeed.android.data.repository.AuthRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.referral.ReferralService
import com.trackspeed.android.ui.screens.tools.FlyingTimeEstimator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import androidx.lifecycle.SavedStateHandle

sealed interface PromoRedemptionState {
    data object Idle : PromoRedemptionState
    data object Loading : PromoRedemptionState
    data class Success(val result: PromoRedemptionResult) : PromoRedemptionState
    data class Error(val reason: PromoRedemptionError) : PromoRedemptionState
}

enum class PromoRedemptionError {
    INVALID,
    EXPIRED,
    MAX_USES,
    ALREADY_REDEEMED,
    RATE_LIMITED,
    NETWORK,
    GENERIC
}

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val profile: OnboardingProfile = OnboardingProfile(),
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val appLanguage: String = SettingsRepository.Defaults.APP_LANGUAGE,
    val promoRedemptionState: PromoRedemptionState = PromoRedemptionState.Idle,
    val referralCode: String = "",
    val referralLink: String = "",
    val isReferred: Boolean = false,
    val annualFreeTrialDays: Int? = null,
    val hasResolvedAnnualOffer: Boolean = false,
    val selectedPainPoints: Set<OnboardingPainPoint> = emptySet()
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val settingsRepository: SettingsRepository,
    private val subscriptionManager: SubscriptionManager,
    private val referralService: ReferralService,
    private val authRepository: AuthRepository,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    companion object {
        private const val TAG = "OnboardingViewModel"
    }

    val onboardingCompletedFlow: Flow<Boolean> = settingsRepository.onboardingCompleted

    private val _uiState = MutableStateFlow(OnboardingUiState(
        currentStep = savedStateHandle.get<String>("onboardingStep")?.let { raw ->
            OnboardingStep.entries.firstOrNull { it.name == raw }
        } ?: OnboardingStep.WELCOME,
        profile = OnboardingProfile(
            role = savedStateHandle.get<String>("onboardingRole")?.let(UserRole::fromRawValue),
            discipline = savedStateHandle.get<String>("onboardingSport")?.let { raw -> SportDiscipline.entries.firstOrNull { it.rawValue == raw } }
        )
    ))
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                isReferred = isReferredUser(),
                isAuthenticated = authRepository.isAuthenticated
            )
        }

        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                _uiState.update {
                    it.copy(
                        isAuthenticated = authState.isRealAuthenticated(),
                        isReferred = isReferredUser()
                    )
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.appLanguage.collect { language ->
                _uiState.update { it.copy(appLanguage = language) }
            }
        }

        if (EarlyReleaseAccess.isEnabled) {
            _uiState.update { it.copy(annualFreeTrialDays = null, hasResolvedAnnualOffer = true) }
        } else subscriptionManager.getOfferings(
            onSuccess = { offerings ->
                _uiState.update {
                    it.copy(
                        annualFreeTrialDays = subscriptionManager.annualFreeTrialDays(offerings),
                        hasResolvedAnnualOffer = true
                    )
                }
            },
            onError = {
                // Unknown eligibility must never be presented as a free trial.
                _uiState.update {
                    it.copy(annualFreeTrialDays = null, hasResolvedAnnualOffer = true)
                }
            }
        )

        viewModelScope.launch {
            uiState.collect { state ->
                savedStateHandle["onboardingStep"] = state.currentStep.name
                savedStateHandle["onboardingRole"] = state.profile.role?.rawValue
                savedStateHandle["onboardingSport"] = state.profile.discipline?.rawValue
            }
        }

        // Load referral code on init
        viewModelScope.launch {
            try {
                val code = referralService.getOrCreateReferralCode()
                val link = referralService.getReferralLink()
                _uiState.update { it.copy(referralCode = code, referralLink = link) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load referral code: ${e.safeCloudErrorCode()}")
            }
        }
    }

    fun advanceAfterWelcome() {
        val isReferred = isReferredUser()
        val nextStep = if (isReferred) {
            OnboardingStep.REFERRAL_WELCOME
        } else {
            OnboardingStep.VALUE_PROPOSITION
        }
        analyticsService.track(
            AnalyticsEvent.ONBOARDING_STEP_COMPLETED,
            mapOf(
                "from_step" to OnboardingStep.WELCOME.analyticsName,
                "to_step" to nextStep.analyticsName,
                "is_referred" to isReferred
            )
        )
        _uiState.update { state ->
            state.copy(
                isReferred = isReferred,
                currentStep = nextStep
            )
        }
    }

    suspend fun isOnboardingCompleted(): Boolean {
        return settingsRepository.onboardingCompleted.first()
    }

    private fun route() = OnboardingRoute(_uiState.value.isReferred, EarlyReleaseAccess.isEnabled)

    fun goForward() = goToStep(route().next(_uiState.value.currentStep))

    fun goBack() {
        val current = _uiState.value.currentStep
        val previous = route().previous(current)
        analyticsService.track(AnalyticsEvent.ONBOARDING_BACK_PRESSED, mapOf(
            "from_step" to current.analyticsName, "to_step" to previous.analyticsName,
            "flow_version" to "android_streamlined_20261022"
        ))
        _uiState.update { it.copy(currentStep = previous) }
    }

    fun goToStep(step: OnboardingStep) {
        val currentStep = _uiState.value.currentStep
        if (currentStep != step) {
            trackStepCompleted(currentStep, step)
        }
        _uiState.update { it.copy(currentStep = step) }
    }

    fun skipToHome() {
        completeOnboardingIfPro()
    }

    fun completeOnboardingIfPro(): Boolean {
        if (!shouldSkipPaywall()) {
            goToStep(OnboardingStep.PAYWALL)
            return false
        }

        // All first-time completion paths share the same short guide.
        goToStep(OnboardingStep.GETTING_STARTED)
        return false
    }

    fun completeOnboarding(onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            if (!authRepository.isAuthenticated) {
                settingsRepository.setHasSkippedLogin(true)
            }
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
            settingsRepository.setOnboardingCompleted(true)
            onCompleted()
            settingsRepository.setPendingProfileSync(true)
            authRepository.processPendingProfileSync()

            // Track referral signup if a referral code was entered during onboarding
            val enteredReferralCode = _uiState.value.profile.referralCode
            if (!enteredReferralCode.isNullOrBlank()) {
                try {
                    referralService.trackReferralSignup(enteredReferralCode)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to track referral signup: ${e.safeCloudErrorCode()}")
                }
            }

            // Process pending referral code from deeplink (trackspeed://invite/CODE)
            processPendingReferralCode()

            analyticsService.track(
                AnalyticsEvent.ONBOARDING_COMPLETED,
                mapOf(
                    "is_pro" to subscriptionManager.isProUser.value,
                    "is_authenticated" to authRepository.isAuthenticated,
                    "is_referred" to _uiState.value.isReferred
                )
            )
            analyticsService.refreshPersonProperties()
        }
    }

    private suspend fun processPendingReferralCode() {
        val pendingCode = ReferralService.getPendingReferralCode(application) ?: return
        Log.d(TAG, "Processing pending referral code from deeplink: $pendingCode")

        // First try as promo code
        try {
            subscriptionManager.redeemPromoCode(pendingCode, "pending_referral")
            persistPendingReferralCodeForProfile(pendingCode)
            ReferralService.clearPendingReferralCode(application)
            Log.d(TAG, "Pending referral code redeemed as promo code: $pendingCode")
            return
        } catch (e: PromoCodeError) {
            if (e is PromoCodeError.AlreadyRedeemed) {
                Log.d(TAG, "Pending promo code already redeemed, clearing: $pendingCode")
                persistPendingReferralCodeForProfile(pendingCode)
                ReferralService.clearPendingReferralCode(application)
                return
            }
            Log.d(TAG, "Not a promo code, trying as referral: ${e.safeCloudErrorCode()}")
        } catch (e: Exception) {
            Log.d(TAG, "Not a promo code, trying as referral: ${e.safeCloudErrorCode()}")
        }

        // Fall back to tracking as referral
        try {
            val success = referralService.trackReferralSignup(pendingCode)
            if (success) {
                persistPendingReferralCodeForProfile(pendingCode)
                Log.d(TAG, "Pending referral code tracked as referral: $pendingCode")
            } else {
                Log.w(TAG, "Failed to track pending referral code: $pendingCode")
                ReferralService.clearPendingReferralCode(application)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process pending referral code: ${e.safeCloudErrorCode()}")
            ReferralService.clearPendingReferralCode(application)
        }
    }

    private suspend fun persistPendingReferralCodeForProfile(code: String) {
        normalizedCode(code)?.let { normalized ->
            settingsRepository.setReferralCode(normalized)
            _uiState.update {
                it.copy(
                    isReferred = true,
                    profile = it.profile.copy(referralCode = normalized)
                )
            }
        }
    }

    private suspend fun storePendingReferralCodeForSignup(code: String) {
        normalizedCode(code)?.let { normalized ->
            ReferralService.storePendingReferralCode(application, normalized)
            settingsRepository.setReferralCode(normalized)
            _uiState.update {
                it.copy(
                    isReferred = true,
                    promoRedemptionState = PromoRedemptionState.Idle,
                    profile = it.profile.copy(referralCode = normalized)
                )
            }
        }
    }

    private fun normalizedCode(code: String): String? {
        return code.trim()
            .takeIf { it.isNotBlank() }
            ?.uppercase(Locale.US)
    }

    private fun isReferredUser(): Boolean {
        return !ReferralService.getPendingReferralCode(application).isNullOrBlank() ||
            subscriptionManager.wasReferred()
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
                val reason = when (e) {
                    is PromoCodeError.InvalidCode -> PromoRedemptionError.INVALID
                    is PromoCodeError.Expired -> PromoRedemptionError.EXPIRED
                    is PromoCodeError.MaxUsesReached -> PromoRedemptionError.MAX_USES
                    is PromoCodeError.AlreadyRedeemed -> PromoRedemptionError.ALREADY_REDEEMED
                    is PromoCodeError.RateLimited -> PromoRedemptionError.RATE_LIMITED
                    is PromoCodeError.NetworkError -> PromoRedemptionError.NETWORK
                }
                _uiState.update { it.copy(promoRedemptionState = PromoRedemptionState.Error(reason)) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(promoRedemptionState = PromoRedemptionState.Error(PromoRedemptionError.GENERIC))
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
        _uiState.update { state ->
            val profile = state.profile.copy(discipline = discipline)
            state.copy(profile = profile.withEstimatedFlyingPrIfNeeded())
        }
    }

    fun setPersonalRecord(time: Double?) {
        _uiState.update { state ->
            val profile = state.profile.copy(personalRecord = time)
            state.copy(profile = profile.withEstimatedFlyingPrIfNeeded())
        }
    }

    fun setFlyingDistance(distance: FlyingDistance) {
        _uiState.update { state ->
            val profile = state.profile.copy(flyingDistance = distance)
            state.copy(profile = profile.withEstimatedFlyingPrIfNeeded())
        }
    }

    fun setFlyingPR(time: Double?) {
        _uiState.update { it.copy(profile = it.profile.copy(flyingPR = time)) }
    }

    fun setMeasuredFlyingPR(time: Double?) {
        _uiState.update {
            it.copy(
                profile = it.profile.copy(
                    usesEventPrEstimate = false,
                    flyingPR = time
                )
            )
        }
    }

    fun setUsesEventPrEstimate(enabled: Boolean) {
        _uiState.update { state ->
            val profile = state.profile.copy(usesEventPrEstimate = enabled)
            state.copy(profile = profile.withEstimatedFlyingPrIfNeeded())
        }
    }

    fun setGoalTime(time: Double?) {
        _uiState.update { it.copy(profile = it.profile.copy(goalTime = time)) }
    }

    fun setAttribution(value: String) {
        _uiState.update { it.copy(profile = it.profile.copy(attribution = value)) }
    }

    fun setAppLanguage(language: String) {
        _uiState.update { it.copy(appLanguage = language) }
        viewModelScope.launch {
            settingsRepository.setAppLanguage(language)
        }
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

    fun togglePainPoint(painPoint: OnboardingPainPoint) {
        _uiState.update { state ->
            val updated = if (state.selectedPainPoints.contains(painPoint)) {
                state.selectedPainPoints - painPoint
            } else {
                state.selectedPainPoints + painPoint
            }
            state.copy(selectedPainPoints = updated)
        }
    }

    fun shouldSkipPaywall(): Boolean {
        return subscriptionManager.isProUser.value
    }

    /**
     * Handle Continue from the ATTRIBUTION step. If a promo code was entered,
     * submit it first (async) and then decide whether to skip the paywall.
     * Matches iOS: free promo codes skip all remaining paywall steps.
     */
    fun handleAttributionContinue() {
        // Already pro (e.g. from deeplink redemption) → skip immediately
        if (!EarlyReleaseAccess.isEnabled && shouldSkipPaywall()) {
            goToStep(OnboardingStep.GETTING_STARTED)
            return
        }

        val code = _uiState.value.profile.promoCode?.takeIf { it.isNotBlank() }
        if (code == null) {
            goForward()
            return
        }

        // Submit the promo code, wait for result, then navigate
        _uiState.update { it.copy(promoRedemptionState = PromoRedemptionState.Loading) }
        viewModelScope.launch {
            try {
                val result = subscriptionManager.redeemPromoCode(code, "onboarding_attribution")
                _uiState.update {
                    it.copy(
                        promoRedemptionState = PromoRedemptionState.Success(result),
                        profile = it.profile.copy(promoCode = code)
                    )
                }
            } catch (e: PromoCodeError) {
                Log.d(TAG, "Attribution code was not redeemed as promo; storing as pending referral: ${e.safeCloudErrorCode()}")
                storePendingReferralCodeForSignup(code)
            } catch (e: Exception) {
                Log.d(TAG, "Attribution code promo redemption failed; storing as pending referral: ${e.safeCloudErrorCode()}")
                storePendingReferralCodeForSignup(code)
            }

            // After redemption attempt, check if user now has pro
            if (!EarlyReleaseAccess.isEnabled && shouldSkipPaywall()) {
                goToStep(OnboardingStep.GETTING_STARTED)
            } else {
                goForward()
            }
        }
    }

    fun setReferralCode(code: String) {
        _uiState.update { it.copy(profile = it.profile.copy(referralCode = code)) }
    }

    fun setAuthenticated(authenticated: Boolean) {
        _uiState.update { it.copy(isAuthenticated = authenticated) }
    }

    fun trackStepViewed(step: OnboardingStep) {
        analyticsService.track(
            AnalyticsEvent.ONBOARDING_STEP_VIEWED,
            mapOf(
                "step" to step.analyticsName,
                "progress" to step.progress
            )
        )
    }

    fun trackNotificationPermissionResult(granted: Boolean) {
        analyticsService.track(
            AnalyticsEvent.NOTIFICATION_PERMISSION_RESULT,
            mapOf(
                "granted" to granted,
                "source" to "onboarding"
            )
        )
    }

    fun trackCameraPermissionResult(granted: Boolean) {
        analyticsService.trackFeature(
            "camera_permission_result",
            mapOf(
                "granted" to granted,
                "source" to "onboarding"
            )
        )
    }

    private fun trackStepCompleted(from: OnboardingStep, to: OnboardingStep) {
        if (from == to) return
        analyticsService.track(
            AnalyticsEvent.ONBOARDING_STEP_COMPLETED,
            mapOf(
                "from_step" to from.analyticsName,
                "to_step" to to.analyticsName,
                "progress" to to.progress
            )
        )
    }

    private fun OnboardingProfile.withEstimatedFlyingPrIfNeeded(): OnboardingProfile {
        if (!usesEventPrEstimate) return this
        val distance = flyingDistance ?: FlyingDistance.METERS_10
        val discipline = discipline ?: SportDiscipline.SPRINT_100M
        val estimate = personalRecord?.let { eventTime ->
            FlyingTimeEstimator.estimateFlyingTime(
                eventTime = eventTime,
                event = discipline,
                targetDistance = distance
            )
        }
        return copy(
            flyingDistance = distance,
            discipline = discipline,
            flyingPR = estimate
        )
    }
}
