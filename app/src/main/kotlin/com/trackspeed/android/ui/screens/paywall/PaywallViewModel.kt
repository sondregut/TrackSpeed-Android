package com.trackspeed.android.ui.screens.paywall

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.trackspeed.android.billing.BillingConfig
import com.trackspeed.android.billing.SubscriptionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

sealed interface PurchaseState {
    data object Idle : PurchaseState
    data object Loading : PurchaseState
    data object Success : PurchaseState
    data class Error(val message: String) : PurchaseState
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
        val rcPackage = _offerings.value?.current?.annual
        return PlanInfo(
            type = PlanType.YEARLY,
            rcPackage = rcPackage,
            priceDisplay = rcPackage?.product?.price?.formatted
                ?: BillingConfig.YEARLY_PRICE_DISPLAY,
            periodDisplay = "year",
            monthlyEquivalent = BillingConfig.YEARLY_MONTHLY_EQUIVALENT,
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
}
