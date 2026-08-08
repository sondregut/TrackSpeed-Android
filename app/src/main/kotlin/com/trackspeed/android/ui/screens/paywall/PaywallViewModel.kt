package com.trackspeed.android.ui.screens.paywall

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.models.Period
import com.trackspeed.android.analytics.AnalyticsEvent
import com.trackspeed.android.analytics.AnalyticsService
import com.trackspeed.android.billing.BillingConfig
import com.trackspeed.android.billing.PromoCodeError
import com.trackspeed.android.billing.PromoCodeType
import com.trackspeed.android.billing.PromoRedemptionResult
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.cloud.safeCloudErrorCode
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Currency
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.roundToInt

sealed interface PurchaseState {
    data object Idle : PurchaseState
    data object Loading : PurchaseState
    data object Success : PurchaseState
    data class Error(val message: String) : PurchaseState
}

sealed interface PromoSheetState {
    data object Hidden : PromoSheetState
    data class Visible(
        val code: String = "",
        val isLoading: Boolean = false,
        val result: PromoRedemptionResult? = null,
        val error: String? = null
    ) : PromoSheetState
}

enum class PlanType {
    WEEKLY,
    MONTHLY,
    YEARLY
}

data class PlanInfo(
    val type: PlanType,
    val rcPackage: Package? = null,
    val priceDisplay: String,
    val periodDisplay: String,
    val monthlyEquivalent: String? = null,
    val savingsPercent: Int? = null,
    val freeTrialDays: Int? = null
)

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val subscriptionManager: SubscriptionManager,
    private val settingsRepository: SettingsRepository,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    companion object {
        private const val TAG = "PaywallViewModel"
        private const val DISCOUNT_PAYWALL_MAX_SHOWS = 2
        private val DISCOUNT_PAYWALL_GAP_MILLIS = TimeUnit.DAYS.toMillis(7)
        private val DISCOUNT_OFFER_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(48)
        const val SOURCE_PAYWALL = "paywall"
        const val SOURCE_DISCOUNT = "discount"
        const val SOURCE_ONBOARDING = "onboarding"
        const val SOURCE_ONBOARDING_RECOVERY = "onboarding_recovery"
    }

    private val _selectedPlan = MutableStateFlow(PlanType.YEARLY)
    val selectedPlan: StateFlow<PlanType> = _selectedPlan.asStateFlow()

    private val _purchaseState = MutableStateFlow<PurchaseState>(PurchaseState.Idle)
    val purchaseState: StateFlow<PurchaseState> = _purchaseState.asStateFlow()

    private val _offerings = MutableStateFlow<Offerings?>(null)
    val offerings: StateFlow<Offerings?> = _offerings.asStateFlow()

    private val _isLoadingOfferings = MutableStateFlow(true)
    val isLoadingOfferings: StateFlow<Boolean> = _isLoadingOfferings.asStateFlow()

    private val _offeringsError = MutableStateFlow<String?>(null)
    val offeringsError: StateFlow<String?> = _offeringsError.asStateFlow()

    val isProUser: StateFlow<Boolean> = subscriptionManager.isProUser
    val isDiscountPaywallUnlocked: StateFlow<Boolean> =
        subscriptionManager.isDiscountPaywallUnlocked

    // Promo code bottom sheet state
    private val _promoSheetState = MutableStateFlow<PromoSheetState>(PromoSheetState.Hidden)
    val promoSheetState: StateFlow<PromoSheetState> = _promoSheetState.asStateFlow()

    // Whether a discount package should be preferred (from spin wheel)
    private val _preferDiscountPackage = MutableStateFlow(false)
    val preferDiscountPackage: StateFlow<Boolean> = _preferDiscountPackage.asStateFlow()
    private var postOnboardingDiscountPrepared = false
    private var analyticsSource = SOURCE_PAYWALL
    private val trackedPaywallViewSources = mutableSetOf<String>()
    private val trackedDiscountShownSources = mutableSetOf<String>()

    init {
        loadOfferings()
    }

    fun loadOfferings() {
        _isLoadingOfferings.value = true
        _offeringsError.value = null
        subscriptionManager.getOfferings(
            onSuccess = { offerings ->
                _offerings.value = offerings
                _isLoadingOfferings.value = false
            },
            onError = { error ->
                _offeringsError.value = error
                _isLoadingOfferings.value = false
                analyticsService.trackPaywall(
                    AnalyticsEvent.PAYWALL_OFFERINGS_UNAVAILABLE,
                    paywallBaseProperties() + mapOf("error" to error)
                )
            }
        )
    }

    fun setAnalyticsSource(source: String) {
        analyticsSource = source
    }

    fun trackPaywallViewedIfNeeded() {
        if (!trackedPaywallViewSources.add(analyticsSource)) return
        val offeringsAvailable = selectedPlanPackage() != null
        analyticsService.trackPaywall(
            AnalyticsEvent.PAYWALL_VIEWED,
            paywallBaseProperties() + mapOf("offerings_available" to offeringsAvailable)
        )
        if (!offeringsAvailable) {
            analyticsService.trackPaywall(
                AnalyticsEvent.PAYWALL_OFFERINGS_UNAVAILABLE,
                paywallBaseProperties()
            )
        }
        if (_preferDiscountPackage.value) {
            trackDiscountPaywallShownIfNeeded()
        }
    }

    fun selectPlan(plan: PlanType) {
        _selectedPlan.value = plan
        analyticsService.trackPaywall(
            AnalyticsEvent.PAYWALL_PLAN_SELECTED,
            paywallBaseProperties() + mapOf("plan" to plan.analyticsValue())
        )
    }

    fun setPreferDiscountPackage(prefer: Boolean) {
        _preferDiscountPackage.value = prefer
    }

    suspend fun preparePostOnboardingDiscountPaywall() {
        if (postOnboardingDiscountPrepared) return
        postOnboardingDiscountPrepared = true

        if (shouldShowPostOnboardingDiscountPaywall()) {
            _preferDiscountPackage.value = true
            markPostOnboardingDiscountShown()
        } else {
            _preferDiscountPackage.value = false
        }
    }

    suspend fun markStandardPaywallDismissedIfNeeded() {
        if (_preferDiscountPackage.value) return
        settingsRepository.setHasDismissedStandardPaywall(true)
    }

    private suspend fun shouldShowPostOnboardingDiscountPaywall(): Boolean {
        if (subscriptionManager.isProUser.value) return false
        if (!hasRealDiscountPackage()) return false
        if (!settingsRepository.hasDismissedStandardPaywall.first()) return false
        if (settingsRepository.discountPaywallShowCount.first() >= DISCOUNT_PAYWALL_MAX_SHOWS) return false

        val lastShownAt = settingsRepository.discountPaywallLastShownAtMillis.first()
        val now = System.currentTimeMillis()
        if (lastShownAt > 0L && now - lastShownAt < DISCOUNT_PAYWALL_GAP_MILLIS) return false

        return true
    }

    private suspend fun markPostOnboardingDiscountShown() {
        val now = System.currentTimeMillis()
        val currentCount = settingsRepository.discountPaywallShowCount.first()
        settingsRepository.setDiscountPaywallShowCount(currentCount + 1)
        settingsRepository.setDiscountPaywallLastShownAtMillis(now)
        settingsRepository.setDiscountOfferExpiresAtMillis(now + DISCOUNT_OFFER_WINDOW_MILLIS)
        trackDiscountPaywallShownIfNeeded()
    }

    private fun currentPackageByIdentifiers(vararg identifiers: String): Package? {
        val identifierSet = identifiers.toSet()
        return _offerings.value?.current?.availablePackages
            ?.firstOrNull { it.identifier in identifierSet }
    }

    private fun getAnnualFullPackage(): Package? {
        return currentPackageByIdentifiers(BillingConfig.PACKAGE_ANNUAL_DEFAULT_FULL)
            ?: _offerings.value?.current?.annual
    }

    private fun getAnnualDiscountPackage(): Package? {
        return currentPackageByIdentifiers(
            BillingConfig.PACKAGE_ANNUAL_DEFAULT_DISCOUNT,
            BillingConfig.PACKAGE_ANNUAL_DISCOUNT
        )
    }

    private fun Package.freeTrialDays(): Int? {
        val period = product.defaultOption?.freePhase?.billingPeriod ?: return null
        val days = when (period.unit) {
            Period.Unit.DAY -> period.value
            Period.Unit.WEEK -> period.value * 7
            Period.Unit.MONTH -> period.value * 30
            Period.Unit.YEAR -> period.value * 365
            Period.Unit.UNKNOWN -> return null
        }
        return days.takeIf { it > 0 }
    }

    private fun isRealDiscountPackage(discount: Package?, full: Package?): Boolean {
        if (discount == null || full == null) return false
        val discountPrice = discount.product.price
        val fullPrice = full.product.price
        return discountPrice.currencyCode == fullPrice.currencyCode &&
            discountPrice.amountMicros < fullPrice.amountMicros
    }

    fun getMonthlyPlan(): PlanInfo {
        val rcPackage = _offerings.value?.current?.monthly
        return PlanInfo(
            type = PlanType.MONTHLY,
            rcPackage = rcPackage,
            priceDisplay = rcPackage?.product?.price?.formatted
                ?: BillingConfig.MONTHLY_PRICE_DISPLAY,
            periodDisplay = "month"
        )
    }

    fun getWeeklyPlan(): PlanInfo {
        val offering = _offerings.value?.current
        val rcPackage = offering?.availablePackages
            ?.firstOrNull {
                it.identifier == BillingConfig.PACKAGE_WEEKLY_DEFAULT ||
                    it.product.id == BillingConfig.PRODUCT_WEEKLY ||
                    it.product.id == BillingConfig.PRODUCT_WEEKLY_IOS_FALLBACK
            }
            ?: offering?.weekly

        return PlanInfo(
            type = PlanType.WEEKLY,
            rcPackage = rcPackage,
            priceDisplay = rcPackage?.product?.price?.formatted
                ?: BillingConfig.WEEKLY_PRICE_DISPLAY,
            periodDisplay = "week",
            freeTrialDays = rcPackage?.freeTrialDays()
        )
    }

    fun getYearlyPlan(): PlanInfo {
        val effectivePackageId = subscriptionManager.getEffectiveYearlyPackageId()
        val fullPackage = getAnnualFullPackage()
        val requiresDiscount = subscriptionManager.isDiscountPaywallUnlocked.value ||
            _preferDiscountPackage.value
        val rcPackage = when {
            requiresDiscount -> getAnnualDiscountPackage()
                ?.takeIf { isRealDiscountPackage(it, fullPackage) }
            effectivePackageId != "annual" -> _offerings.value?.current?.availablePackages
                ?.firstOrNull { it.identifier == effectivePackageId }
                ?: fullPackage
            else -> fullPackage
        }

        val monthlyEquiv = rcPackage?.product?.price?.let { price ->
            val monthly = price.amountMicros / 12.0 / 1_000_000.0
            val formatter = NumberFormat.getCurrencyInstance().apply {
                currency = Currency.getInstance(price.currencyCode)
            }
            formatter.format(monthly)
        } ?: BillingConfig.YEARLY_MONTHLY_EQUIVALENT.takeUnless { requiresDiscount }
        return PlanInfo(
            type = PlanType.YEARLY,
            rcPackage = rcPackage,
            priceDisplay = rcPackage?.product?.price?.formatted
                ?: if (requiresDiscount) "Price unavailable" else BillingConfig.YEARLY_PRICE_DISPLAY,
            periodDisplay = "year",
            monthlyEquivalent = monthlyEquiv,
            savingsPercent = BillingConfig.YEARLY_SAVINGS_PERCENT,
            freeTrialDays = rcPackage?.freeTrialDays()
        )
    }

    fun getStandardYearlyPlan(): PlanInfo {
        val rcPackage = getAnnualFullPackage()
        val monthlyEquiv = rcPackage?.product?.price?.let { price ->
            val monthly = price.amountMicros / 12.0 / 1_000_000.0
            val formatter = NumberFormat.getCurrencyInstance().apply {
                currency = Currency.getInstance(price.currencyCode)
            }
            formatter.format(monthly)
        } ?: BillingConfig.YEARLY_MONTHLY_EQUIVALENT

        return PlanInfo(
            type = PlanType.YEARLY,
            rcPackage = rcPackage,
            priceDisplay = rcPackage?.product?.price?.formatted ?: BillingConfig.YEARLY_PRICE_DISPLAY,
            periodDisplay = "year",
            monthlyEquivalent = monthlyEquiv,
            savingsPercent = BillingConfig.YEARLY_SAVINGS_PERCENT,
            freeTrialDays = rcPackage?.freeTrialDays()
        )
    }

    fun getDiscountYearlyPlan(): PlanInfo {
        val fullPackage = getAnnualFullPackage()
        val rcPackage = getAnnualDiscountPackage()
            ?.takeIf { isRealDiscountPackage(it, fullPackage) }
        val monthlyEquiv = rcPackage?.product?.price?.let { price ->
            val monthly = price.amountMicros / 12.0 / 1_000_000.0
            val formatter = NumberFormat.getCurrencyInstance().apply {
                currency = Currency.getInstance(price.currencyCode)
            }
            formatter.format(monthly)
        }

        val savingsPercent = if (rcPackage != null && fullPackage != null) {
            val full = fullPackage.product.price.amountMicros.toDouble()
            val discount = rcPackage.product.price.amountMicros.toDouble()
            (((full - discount) / full) * 100.0).roundToInt().coerceAtLeast(1)
        } else {
            null
        }

        return PlanInfo(
            type = PlanType.YEARLY,
            rcPackage = rcPackage,
            priceDisplay = rcPackage?.product?.price?.formatted ?: "Price unavailable",
            periodDisplay = "year",
            monthlyEquivalent = monthlyEquiv,
            savingsPercent = savingsPercent,
            freeTrialDays = rcPackage?.freeTrialDays()
        )
    }

    fun hasRealDiscountPackage(): Boolean {
        return isRealDiscountPackage(getAnnualDiscountPackage(), getAnnualFullPackage())
    }

    fun purchase(activity: Activity) {
        val plan = when (_selectedPlan.value) {
            PlanType.WEEKLY -> getWeeklyPlan()
            PlanType.MONTHLY -> getMonthlyPlan()
            PlanType.YEARLY -> getYearlyPlan()
        }
        val rcPackage = plan.rcPackage
        if (rcPackage == null) {
            _purchaseState.value = PurchaseState.Error("Package not available. Please try again.")
            return
        }

        val analyticsProperties = purchaseProperties(rcPackage, plan)
        analyticsService.trackPaywall(AnalyticsEvent.PAYWALL_PURCHASE_TAPPED, analyticsProperties)
        _purchaseState.value = PurchaseState.Loading
        subscriptionManager.purchase(
            activity = activity,
            packageToPurchase = rcPackage,
            onSuccess = {
                analyticsService.trackPaywall(
                    AnalyticsEvent.PAYWALL_PURCHASE_COMPLETED,
                    analyticsProperties
                )
                if (_preferDiscountPackage.value) {
                    analyticsService.trackPaywall(
                        AnalyticsEvent.DISCOUNT_PAYWALL_PURCHASED,
                        analyticsProperties
                    )
                }
                _purchaseState.value = PurchaseState.Success
            },
            onError = { message, userCancelled ->
                if (userCancelled) {
                    analyticsService.trackPaywall(
                        AnalyticsEvent.PAYWALL_PURCHASE_CANCELLED,
                        analyticsProperties
                    )
                    _purchaseState.value = PurchaseState.Idle
                } else {
                    analyticsService.trackPaywall(
                        AnalyticsEvent.PAYWALL_PURCHASE_FAILED,
                        analyticsProperties + mapOf("error" to message)
                    )
                    _purchaseState.value = PurchaseState.Error(message)
                }
            }
        )
    }

    fun restorePurchases() {
        analyticsService.trackPaywall(
            AnalyticsEvent.PAYWALL_RESTORE_TAPPED,
            paywallBaseProperties()
        )
        _purchaseState.value = PurchaseState.Loading
        subscriptionManager.restorePurchases(
            onSuccess = {
                if (subscriptionManager.isProUser.value) {
                    _purchaseState.value = PurchaseState.Success
                } else {
                    _purchaseState.value = PurchaseState.Error("No active subscription found.")
                }
            },
            onError = { message ->
                _purchaseState.value = PurchaseState.Error(message)
            }
        )
    }

    fun clearError() {
        _purchaseState.value = PurchaseState.Idle
    }

    suspend fun handleCloseTapped() {
        analyticsService.trackPaywall(
            AnalyticsEvent.PAYWALL_SKIPPED,
            paywallBaseProperties() + mapOf("is_purchasing" to (_purchaseState.value is PurchaseState.Loading))
        )
        if (_preferDiscountPackage.value) {
            trackDiscountPaywallDismissed()
        } else {
            markStandardPaywallDismissedIfNeeded()
        }
    }

    fun trackDiscountPaywallShownIfNeeded() {
        if (!trackedDiscountShownSources.add(analyticsSource)) return
        analyticsService.trackPaywall(
            AnalyticsEvent.DISCOUNT_PAYWALL_SHOWN,
            paywallBaseProperties()
        )
    }

    fun trackDiscountPaywallDismissed() {
        analyticsService.trackPaywall(
            AnalyticsEvent.DISCOUNT_PAYWALL_DISMISSED,
            paywallBaseProperties()
        )
    }

    // ---- Promo code sheet ----

    fun showPromoSheet() {
        _promoSheetState.value = PromoSheetState.Visible()
    }

    fun hidePromoSheet() {
        _promoSheetState.value = PromoSheetState.Hidden
    }

    fun setPromoCodeInput(code: String) {
        val current = _promoSheetState.value
        if (current is PromoSheetState.Visible) {
            _promoSheetState.value = current.copy(code = code.uppercase(), error = null)
        }
    }

    fun redeemPromoCode() {
        val current = _promoSheetState.value
        if (current !is PromoSheetState.Visible || current.code.isBlank()) return

        _promoSheetState.value = current.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                val result = subscriptionManager.redeemPromoCode(current.code, "paywall")
                _promoSheetState.value = current.copy(
                    isLoading = false,
                    result = result,
                    error = null
                )

                when (result.type) {
                    PromoCodeType.FREE -> {
                        kotlinx.coroutines.delay(1500)
                        _promoSheetState.value = PromoSheetState.Hidden
                        _purchaseState.value = PurchaseState.Success
                    }
                    PromoCodeType.DISCOUNT -> {
                        _preferDiscountPackage.value = true
                        kotlinx.coroutines.delay(900)
                        _promoSheetState.value = PromoSheetState.Hidden
                    }
                    PromoCodeType.TRIAL -> Unit
                }
            } catch (e: PromoCodeError) {
                val message = when (e) {
                    is PromoCodeError.InvalidCode -> "Invalid or inactive promo code"
                    is PromoCodeError.Expired -> "This promo code has expired"
                    is PromoCodeError.MaxUsesReached -> "This code has reached its maximum uses"
                    is PromoCodeError.AlreadyRedeemed -> "You've already redeemed this code"
                    is PromoCodeError.RateLimited -> "Please wait before trying again"
                    is PromoCodeError.NetworkError -> "Network error. Check your connection."
                }
                _promoSheetState.value = current.copy(isLoading = false, error = message)
            } catch (e: Exception) {
                Log.e(TAG, "Promo redemption failed: ${e.safeCloudErrorCode()}")
                _promoSheetState.value = current.copy(
                    isLoading = false,
                    error = "Something went wrong. Please try again."
                )
            }
        }
    }

    private fun selectedPlanPackage(): Package? {
        return when (_selectedPlan.value) {
            PlanType.WEEKLY -> getWeeklyPlan().rcPackage
            PlanType.MONTHLY -> getMonthlyPlan().rcPackage
            PlanType.YEARLY -> getYearlyPlan().rcPackage
        }
    }

    private fun paywallBaseProperties(): Map<String, Any?> {
        return mapOf(
            "source" to analyticsSource,
            "is_referred" to subscriptionManager.wasReferred(),
            "is_discount" to _preferDiscountPackage.value
        )
    }

    private fun purchaseProperties(rcPackage: Package, plan: PlanInfo): Map<String, Any?> {
        val price = rcPackage.product.price
        return paywallBaseProperties() + mapOf(
            "plan" to plan.type.analyticsValue(),
            "product_id" to rcPackage.product.id,
            "package_identifier" to rcPackage.identifier,
            "price" to price.amountMicros / 1_000_000.0,
            "price_display" to price.formatted,
            "currency" to price.currencyCode
        )
    }

    private fun PlanType.analyticsValue(): String {
        return name.lowercase()
    }
}
