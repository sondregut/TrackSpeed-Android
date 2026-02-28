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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trainingSessionDao: TrainingSessionDao,
    private val supabaseClient: SupabaseClient
) : UpdatedCustomerInfoListener {

    companion object {
        private const val TAG = "SubscriptionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isProUser = MutableStateFlow(false)
    val isProUser: StateFlow<Boolean> = _isProUser.asStateFlow()

    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    val customerInfo: StateFlow<CustomerInfo?> = _customerInfo.asStateFlow()

    private val _isInBillingGracePeriod = MutableStateFlow(false)
    val isInBillingGracePeriod: StateFlow<Boolean> = _isInBillingGracePeriod.asStateFlow()

    private val _willRenew = MutableStateFlow(false)
    val willRenew: StateFlow<Boolean> = _willRenew.asStateFlow()

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

    // ---- Promo code checking via Supabase ----

    @Serializable
    private data class PromoRedemption(
        val id: String? = null,
        @SerialName("device_id") val deviceId: String,
        val status: String,
        @SerialName("expires_at") val expiresAt: String? = null
    )

    private suspend fun checkPromoAccess() {
        try {
            val deviceId = getDeviceId()
            val redemptions = supabaseClient.postgrest["promo_redemptions"]
                .select {
                    filter {
                        eq("device_id", deviceId)
                        eq("status", "active")
                    }
                }
                .decodeList<PromoRedemption>()

            hasPromoAccess = redemptions.isNotEmpty()
            if (hasPromoAccess) {
                _isProUser.value = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check promo access: ${e.message}")
            // Non-fatal - promo is supplementary
        }
    }

    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("trackspeed", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val newId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newId).apply()
            newId
        }
    }
}
