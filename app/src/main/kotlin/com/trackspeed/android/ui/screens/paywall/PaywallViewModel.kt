package com.trackspeed.android.ui.screens.paywall

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.trackspeed.android.billing.BillingConfig
import com.trackspeed.android.billing.PromoCodeError
import com.trackspeed.android.billing.PromoRedemptionResult
import com.trackspeed.android.billing.SubscriptionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Currency
import javax.inject.Inject

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
    MONTHLY,
    YEARLY
}

data class PlanInfo(
    val type: PlanType,
    val rcPackage: Package? = null,
    val priceDisplay: String,
    val periodDisplay: String,
    val monthlyEquivalent: String? = null,
    val savingsPercent: Int? = null
)

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {

    companion object {
        private const val TAG = "PaywallViewModel"
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

    // Promo code bottom sheet state
    private val _promoSheetState = MutableStateFlow<PromoSheetState>(PromoSheetState.Hidden)
    val promoSheetState: StateFlow<PromoSheetState> = _promoSheetState.asStateFlow()

    // Whether a discount package should be preferred (from spin wheel)
    private val _preferDiscountPackage = MutableStateFlow(false)
    val preferDiscountPackage: StateFlow<Boolean> = _preferDiscountPackage.asStateFlow()

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
            }
        )
    }

    fun selectPlan(plan: PlanType) {
        _selectedPlan.value = plan
    }

    fun setPreferDiscountPackage(prefer: Boolean) {
        _preferDiscountPackage.value = prefer
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

    fun getYearlyPlan(): PlanInfo {
        // Check for special packages based on eligibility
        val effectivePackageId = subscriptionManager.getEffectiveYearlyPackageId()
        val rcPackage = if (effectivePackageId != "annual") {
            // Try to find the special package in the offering
            _offerings.value?.current?.availablePackages
                ?.firstOrNull { it.identifier == effectivePackageId }
                ?: _offerings.value?.current?.annual
        } else if (_preferDiscountPackage.value) {
            // Spin wheel discount
            _offerings.value?.current?.availablePackages
                ?.firstOrNull { it.identifier == BillingConfig.PACKAGE_ANNUAL_DISCOUNT }
                ?: _offerings.value?.current?.annual
        } else {
            _offerings.value?.current?.annual
        }

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
            priceDisplay = rcPackage?.product?.price?.formatted
                ?: BillingConfig.YEARLY_PRICE_DISPLAY,
            periodDisplay = "year",
            monthlyEquivalent = monthlyEquiv,
            savingsPercent = BillingConfig.YEARLY_SAVINGS_PERCENT
        )
    }

    fun purchase(activity: Activity) {
        val plan = if (_selectedPlan.value == PlanType.MONTHLY) getMonthlyPlan() else getYearlyPlan()
        val rcPackage = plan.rcPackage
        if (rcPackage == null) {
            _purchaseState.value = PurchaseState.Error("Package not available. Please try again.")
            return
        }

        _purchaseState.value = PurchaseState.Loading
        subscriptionManager.purchase(
            activity = activity,
            packageToPurchase = rcPackage,
            onSuccess = {
                _purchaseState.value = PurchaseState.Success
            },
            onError = { message, userCancelled ->
                if (userCancelled) {
                    _purchaseState.value = PurchaseState.Idle
                } else {
                    _purchaseState.value = PurchaseState.Error(message)
                }
            }
        )
    }

    fun restorePurchases() {
        _purchaseState.value = PurchaseState.Loading
        subscriptionManager.restorePurchases(
            onSuccess = { customerInfo ->
                val hasPro = customerInfo
                    .entitlements[BillingConfig.ENTITLEMENT_ID]
                    ?.isActive == true
                if (hasPro) {
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

                // If free type, pro is now active - close after delay
                if (result.type == "free") {
                    kotlinx.coroutines.delay(1500)
                    _promoSheetState.value = PromoSheetState.Hidden
                    _purchaseState.value = PurchaseState.Success
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
                Log.e(TAG, "Promo redemption failed", e)
                _promoSheetState.value = current.copy(
                    isLoading = false,
                    error = "Something went wrong. Please try again."
                )
            }
        }
    }
}
