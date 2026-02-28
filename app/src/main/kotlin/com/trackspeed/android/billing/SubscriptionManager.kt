package com.trackspeed.android.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.models.StoreTransaction
import com.trackspeed.android.data.local.dao.TrainingSessionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trainingSessionDao: TrainingSessionDao,
    private val supabaseClient: SupabaseClient,
    private val promoCodeService: PromoCodeService
) : UpdatedCustomerInfoListener {

    companion object {
        private const val TAG = "SubscriptionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Pro status determined by RevenueCat entitlement or promo access
    private val _isProUser = MutableStateFlow(false)
    val isProUser: StateFlow<Boolean> = _isProUser.asStateFlow()

    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    val customerInfo: StateFlow<CustomerInfo?> = _customerInfo.asStateFlow()

    private val _isInBillingGracePeriod = MutableStateFlow(false)
    val isInBillingGracePeriod: StateFlow<Boolean> = _isInBillingGracePeriod.asStateFlow()

    private val _willRenew = MutableStateFlow(false)
    val willRenew: StateFlow<Boolean> = _willRenew.asStateFlow()

    // Influencer offer eligibility (trial type promo code redeemed)
    private val _isInfluencerOfferEligible = MutableStateFlow(false)
    val isInfluencerOfferEligible: StateFlow<Boolean> = _isInfluencerOfferEligible.asStateFlow()

    private var hasPromoAccess = false

    init {
        // Listen for customer info updates from RevenueCat
        if (Purchases.isConfigured) {
            Purchases.sharedInstance.updatedCustomerInfoListener = this
            refreshCustomerInfo()
        }
        // Check promo access via Supabase
        scope.launch {
            checkPromoAccess()
        }
    }

    override fun onReceived(customerInfo: CustomerInfo) {
        _customerInfo.value = customerInfo
        updateProStatus(customerInfo)
    }

    private fun refreshCustomerInfo() {
        if (!Purchases.isConfigured) return
        Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                _customerInfo.value = customerInfo
                updateProStatus(customerInfo)
            }
            override fun onError(error: com.revenuecat.purchases.PurchasesError) {
                Log.w(TAG, "Failed to fetch customer info: ${error.message}")
            }
        })
    }

    private fun updateProStatus(customerInfo: CustomerInfo) {
        val entitlement = customerInfo.entitlements[BillingConfig.ENTITLEMENT_ID]
        val hasEntitlement = entitlement?.isActive == true
        _isProUser.value = hasEntitlement || hasPromoAccess

        // Grace period: billingIssueDetectedAt is non-null when there's a payment problem
        _isInBillingGracePeriod.value = entitlement?.billingIssueDetectedAt != null
        _willRenew.value = entitlement?.willRenew == true
    }

    /**
     * Fetch current RevenueCat offerings (subscription plans).
     */
    fun getOfferings(
        onSuccess: (Offerings) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!Purchases.isConfigured) {
            onError("Purchases SDK not configured")
            return
        }
        Purchases.sharedInstance.getOfferings(object : ReceiveOfferingsCallback {
            override fun onReceived(offerings: Offerings) {
                onSuccess(offerings)
            }
            override fun onError(error: com.revenuecat.purchases.PurchasesError) {
                onError(error.message)
            }
        })
    }

    /**
     * Initiate a purchase for the given package.
     */
    fun purchase(
        activity: Activity,
        packageToPurchase: Package,
        onSuccess: (CustomerInfo) -> Unit,
        onError: (String, Boolean) -> Unit
    ) {
        if (!Purchases.isConfigured) {
            onError("Purchases SDK not configured", false)
            return
        }
        Purchases.sharedInstance.purchase(
            PurchaseParams.Builder(activity, packageToPurchase).build(),
            object : PurchaseCallback {
                override fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo) {
                    _customerInfo.value = customerInfo
                    updateProStatus(customerInfo)
                    onSuccess(customerInfo)
                }
                override fun onError(error: com.revenuecat.purchases.PurchasesError, userCancelled: Boolean) {
                    onError(error.message, userCancelled)
                }
            }
        )
    }

    /**
     * Restore previous purchases (e.g. after reinstall).
     */
    fun restorePurchases(
        onSuccess: (CustomerInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!Purchases.isConfigured) {
            onError("Purchases SDK not configured")
            return
        }
        Purchases.sharedInstance.restorePurchases(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                _customerInfo.value = customerInfo
                updateProStatus(customerInfo)
                onSuccess(customerInfo)
            }
            override fun onError(error: com.revenuecat.purchases.PurchasesError) {
                onError(error.message)
            }
        })
    }

    /**
     * Redeem a promo code via PromoCodeService.
     * Returns the result and refreshes pro status.
     */
    suspend fun redeemPromoCode(code: String, source: String): PromoRedemptionResult {
        val result = promoCodeService.redeemPromoCode(code, source)

        // Refresh pro status based on result
        when (result.type) {
            "free" -> {
                hasPromoAccess = true
                _isProUser.value = true
            }
            "trial" -> {
                _isInfluencerOfferEligible.value = true
            }
        }

        return result
    }

    /**
     * Whether the user can save a new session (under free limit or pro).
     */
    suspend fun canSaveSession(): Boolean {
        if (_isProUser.value) return true
        return getSessionCount() < BillingConfig.FREE_SESSION_LIMIT
    }

    /**
     * Current total number of saved training sessions.
     */
    suspend fun getSessionCount(): Int {
        return trainingSessionDao.getTotalSessionCount().first()
    }

    /**
     * Check if a specific Pro feature is available.
     */
    fun isFeatureAvailable(feature: ProFeature): Boolean {
        return _isProUser.value
    }

    /**
     * Whether the given start mode is available to the current user.
     * Free users: flying, touch, inFrame.
     * Pro users: all modes including voice and countdown.
     */
    fun canUseStartMode(modeName: String): Boolean {
        return when (modeName.lowercase()) {
            "flying", "touch", "inframe" -> true
            "voice", "countdown" -> _isProUser.value
            else -> true
        }
    }

    /**
     * Whether the user can use ElevenLabs TTS voices (Pro only).
     */
    fun canUseElevenLabs(): Boolean = _isProUser.value

    /**
     * Whether the user can access multi-device race mode (Pro only).
     */
    fun canUseRaceMode(): Boolean = _isProUser.value

    /**
     * Get the effective yearly package identifier based on eligibility.
     * Returns annual_referral (30-day trial) if influencer/referral eligible, else standard annual.
     */
    fun getEffectiveYearlyPackageId(): String {
        return if (_isInfluencerOfferEligible.value) {
            BillingConfig.PACKAGE_ANNUAL_REFERRAL
        } else {
            "annual"
        }
    }

    /**
     * Get the discount package identifier for spin wheel.
     */
    fun getDiscountPackageId(): String {
        return BillingConfig.PACKAGE_ANNUAL_DISCOUNT
    }

    // ---- Promo code checking via Supabase ----

    private suspend fun checkPromoAccess() {
        try {
            val activeRedemption = promoCodeService.getActivePromoAccess()
            hasPromoAccess = activeRedemption != null
            if (hasPromoAccess) {
                _isProUser.value = true
            }

            // Also check influencer offer eligibility
            val hasInfluencer = promoCodeService.hasInfluencerOffer()
            _isInfluencerOfferEligible.value = hasInfluencer
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check promo access: ${e.message}")
        }
    }

    /**
     * Force refresh of pro status from all sources.
     */
    fun refreshProStatus() {
        refreshCustomerInfo()
        scope.launch {
            checkPromoAccess()
        }
    }
}
