package com.trackspeed.android.billing

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PeriodType
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.LogInCallback
import com.revenuecat.purchases.models.StoreTransaction
import com.trackspeed.android.cloud.AuthService
import com.trackspeed.android.analytics.AnalyticsService
import com.trackspeed.android.analytics.SubscriptionAnalyticsSnapshot
import com.trackspeed.android.data.local.dao.TrainingSessionDao
import com.trackspeed.android.model.StartType
import com.trackspeed.android.notifications.NotificationService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

internal fun shouldPreserveOfflineProCache(
    cachedProActive: Boolean,
    livePro: Boolean,
    hasInternetConnectivity: Boolean
): Boolean = cachedProActive && !livePro && !hasInternetConnectivity

internal fun isSupabaseSubscriptionActive(
    status: String,
    expiresAt: String?,
    now: Instant
): Boolean {
    if (status.lowercase() !in setOf("active", "cancelled", "billing_issue")) return false
    return expiresAt?.let { value ->
        runCatching { Instant.parse(value).isAfter(now) }.getOrDefault(false)
    } ?: true
}

@Singleton
class SubscriptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trainingSessionDao: TrainingSessionDao,
    private val supabaseClient: SupabaseClient,
    private val promoCodeService: PromoCodeService,
    private val authService: AuthService,
    private val notificationService: NotificationService,
    private val analyticsService: AnalyticsService
) : UpdatedCustomerInfoListener {

    companion object {
        private const val TAG = "SubscriptionManager"
        private const val PREFS_NAME = "trackspeed"
        private const val KEY_CACHED_IS_PRO_USER = "cachedIsProUser"
        private const val KEY_WAS_REFERRED = "wasReferred"
        private const val KEY_INFLUENCER_OFFER_ELIGIBLE = "isEligibleForInfluencerOffer"
        private const val KEY_DISCOUNT_PAYWALL_UNLOCKED = "hasUnlockedDiscountPaywall"
        private const val OFFERINGS_CACHE_TTL_MS = 5 * 60 * 1000L
        private val OFFERINGS_RETRY_BACKOFF_MS = longArrayOf(500L, 1_500L, 3_000L)
        private const val READY_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var cachedProActive = prefs.getBoolean(KEY_CACHED_IS_PRO_USER, false)
    private var isUsingOfflineProCache = false
    private var readySignal = CompletableDeferred<Unit>()
    private var canPersistLiveProCache = false

    // Pro status determined by RevenueCat entitlement, promo access, or Supabase manual grants.
    private val _isProUser = MutableStateFlow(cachedProActive)
    val isProUser: StateFlow<Boolean> = _isProUser.asStateFlow()

    private val _hasCheckedSubscription = MutableStateFlow(false)
    val hasCheckedSubscription: StateFlow<Boolean> = _hasCheckedSubscription.asStateFlow()

    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    val customerInfo: StateFlow<CustomerInfo?> = _customerInfo.asStateFlow()

    private val _isInBillingGracePeriod = MutableStateFlow(false)
    val isInBillingGracePeriod: StateFlow<Boolean> = _isInBillingGracePeriod.asStateFlow()

    private val _willRenew = MutableStateFlow(false)
    val willRenew: StateFlow<Boolean> = _willRenew.asStateFlow()

    private var cachedOfferings: Offerings? = null
    private var offeringsLoadedAtMillis: Long = 0L

    // Influencer offer eligibility (trial type promo code redeemed)
    private val _isInfluencerOfferEligible = MutableStateFlow(
        prefs.getBoolean(KEY_INFLUENCER_OFFER_ELIGIBLE, false)
    )
    val isInfluencerOfferEligible: StateFlow<Boolean> = _isInfluencerOfferEligible.asStateFlow()

    private val _isDiscountPaywallUnlocked = MutableStateFlow(
        prefs.getBoolean(KEY_DISCOUNT_PAYWALL_UNLOCKED, false)
    )
    val isDiscountPaywallUnlocked: StateFlow<Boolean> =
        _isDiscountPaywallUnlocked.asStateFlow()

    private var hasPromoAccess = false
    private var hasRevenueCatAccess = false
    private var hasSupabaseSubscription = false
    private var pendingRevenueCatLoginUserId: String? = null
    private var revenueCatLoggedInUserId: String? = null
    private var revenueCatLoginInFlightUserId: String? = null

    @Serializable
    private data class SupabaseSubscriptionDto(
        val status: String = "",
        @SerialName("expires_at") val expiresAt: String? = null
    )

    init {
        // Listen for customer info updates from RevenueCat
        if (Purchases.isConfigured) {
            Purchases.sharedInstance.updatedCustomerInfoListener = this
            pendingRevenueCatLoginUserId?.let { userId ->
                pendingRevenueCatLoginUserId = null
                logIn(userId)
            }
            refreshCustomerInfo()
        }
        // Check promo access via Supabase
        scope.launch {
            checkPromoAccess()
            checkDiscountPaywallAccess()
            checkSupabaseSubscription()
            updateCombinedProStatus()
            if (!Purchases.isConfigured) {
                canPersistLiveProCache = true
                signalReady(persistCache = true)
            }
        }
    }

    /**
     * Wait until the first live subscription check has completed.
     *
     * This mirrors iOS' startup behavior: UI can trust the previous-session Pro
     * cache briefly, then proceed even if RevenueCat is slow or unavailable.
     */
    suspend fun waitForReady(timeoutMillis: Long = READY_TIMEOUT_MS) {
        if (_hasCheckedSubscription.value) return
        val completed = withTimeoutOrNull(timeoutMillis) {
            readySignal.await()
            true
        }
        if (completed != true) {
            Log.w(TAG, "waitForReady() timed out after ${timeoutMillis}ms")
        }
    }

    fun logIn(userId: String) {
        if (userId.isBlank()) return

        if (!Purchases.isConfigured) {
            pendingRevenueCatLoginUserId = userId
            Log.i(TAG, "Deferring RevenueCat login until SDK configuration")
            return
        }

        val purchases = Purchases.sharedInstance
        if (revenueCatLoggedInUserId == userId || purchases.appUserID == userId) {
            revenueCatLoggedInUserId = userId
            syncPostHogDistinctIdAttribute()
            refreshUserScopedAccess()
            return
        }
        if (revenueCatLoginInFlightUserId == userId) return
        revenueCatLoginInFlightUserId = userId

        purchases.logIn(userId, object : LogInCallback {
            override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                revenueCatLoginInFlightUserId = null
                revenueCatLoggedInUserId = userId
                _customerInfo.value = customerInfo
                canPersistLiveProCache = true
                updateProStatus(customerInfo)
                signalReady(persistCache = true)
                refreshUserScopedAccess()
                analyticsService.identify(userId)
                syncPostHogDistinctIdAttribute()
                Log.i(TAG, "RevenueCat user logged in: ${userId.take(8)} created=$created")
            }

            override fun onError(error: com.revenuecat.purchases.PurchasesError) {
                revenueCatLoginInFlightUserId = null
                Log.w(TAG, "RevenueCat login failed: ${error.message}")
                signalReady(persistCache = false)
                refreshUserScopedAccess()
            }
        })
    }

    fun logOut() {
        pendingRevenueCatLoginUserId = null
        revenueCatLoginInFlightUserId = null
        clearOfferEligibility()

        if (!Purchases.isConfigured) {
            revenueCatLoggedInUserId = null
            hasRevenueCatAccess = false
            hasSupabaseSubscription = false
            canPersistLiveProCache = true
            signalReady(persistCache = true)
            analyticsService.reset()
            return
        }

        Purchases.sharedInstance.logOut(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                revenueCatLoggedInUserId = null
                _customerInfo.value = customerInfo
                canPersistLiveProCache = true
                updateProStatus(customerInfo)
                hasSupabaseSubscription = false
                updateCombinedProStatus()
                persistProCache()
                analyticsService.reset()
                syncPostHogDistinctIdAttribute()
                Log.i(TAG, "RevenueCat user logged out")
            }

            override fun onError(error: com.revenuecat.purchases.PurchasesError) {
                revenueCatLoggedInUserId = null
                hasRevenueCatAccess = false
                hasSupabaseSubscription = false
                updateCombinedProStatus()
                persistProCache()
                analyticsService.reset()
                Log.w(TAG, "RevenueCat logout failed: ${error.message}")
            }
        })
    }

    private fun refreshUserScopedAccess() {
        scope.launch {
            checkPromoAccess()
            checkDiscountPaywallAccess()
            checkSupabaseSubscription()
            updateCombinedProStatus()
        }
    }

    override fun onReceived(customerInfo: CustomerInfo) {
        _customerInfo.value = customerInfo
        canPersistLiveProCache = true
        updateProStatus(customerInfo)
        signalReady(persistCache = true)
    }

    private fun refreshCustomerInfo() {
        if (!Purchases.isConfigured) return
        Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                _customerInfo.value = customerInfo
                canPersistLiveProCache = true
                updateProStatus(customerInfo)
                signalReady(persistCache = true)
            }
            override fun onError(error: com.revenuecat.purchases.PurchasesError) {
                Log.w(TAG, "Failed to fetch customer info: ${error.message}")
                signalReady(persistCache = false)
            }
        })
    }

    private fun updateProStatus(customerInfo: CustomerInfo) {
        val entitlement = customerInfo.entitlements[BillingConfig.ENTITLEMENT_ID]
        val hasEntitlement = entitlement?.isActive == true
        hasRevenueCatAccess = hasEntitlement
        updateCombinedProStatus()

        // Grace period: billingIssueDetectedAt is non-null when there's a payment problem
        val billingIssueDetected = entitlement?.billingIssueDetectedAt != null
        _isInBillingGracePeriod.value = billingIssueDetected
        _willRenew.value = entitlement?.willRenew == true

        scope.launch {
            val expirationTime = entitlement?.expirationDate?.time
            if (hasEntitlement && billingIssueDetected && expirationTime != null) {
                notificationService.scheduleBillingIssueReminder(expirationTime)
            } else {
                notificationService.cancelBillingIssueReminder()
            }
        }
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

        val now = SystemClock.elapsedRealtime()
        val cached = cachedOfferings
        if (cached != null && now - offeringsLoadedAtMillis < OFFERINGS_CACHE_TTL_MS) {
            onSuccess(cached)
            return
        }

        fetchOfferingsWithRetry(
            attempt = 0,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    /**
     * Google Play decides offer eligibility and exposes only an applicable
     * free pricing phase through the selected subscription option. UI copy
     * must use this live result instead of assuming a configured base plan
     * includes a trial.
     */
    fun annualFreeTrialDays(offerings: Offerings): Int? {
        val offering = offerings.current ?: return null
        val yearlyPackage = offering.availablePackages.firstOrNull {
            it.identifier == BillingConfig.PACKAGE_ANNUAL_DEFAULT_FULL ||
                it.product.id == BillingConfig.PRODUCT_YEARLY
        } ?: offering.annual
        val period = yearlyPackage?.product?.defaultOption?.freePhase?.billingPeriod ?: return null
        return when (period.unit) {
            com.revenuecat.purchases.models.Period.Unit.DAY -> period.value
            com.revenuecat.purchases.models.Period.Unit.WEEK -> period.value * 7
            com.revenuecat.purchases.models.Period.Unit.MONTH -> period.value * 30
            com.revenuecat.purchases.models.Period.Unit.YEAR -> period.value * 365
            com.revenuecat.purchases.models.Period.Unit.UNKNOWN -> null
        }?.takeIf { it > 0 }
    }

    fun hasAnnualFreeTrial(offerings: Offerings): Boolean = annualFreeTrialDays(offerings) != null

    private fun fetchOfferingsWithRetry(
        attempt: Int,
        onSuccess: (Offerings) -> Unit,
        onError: (String) -> Unit
    ) {
        Purchases.sharedInstance.getOfferings(object : ReceiveOfferingsCallback {
            override fun onReceived(offerings: Offerings) {
                cachedOfferings = offerings
                offeringsLoadedAtMillis = SystemClock.elapsedRealtime()
                onSuccess(offerings)
            }

            override fun onError(error: com.revenuecat.purchases.PurchasesError) {
                if (attempt >= OFFERINGS_RETRY_BACKOFF_MS.lastIndex) {
                    Log.w(TAG, "Failed to fetch offerings after ${attempt + 1} attempts: ${error.message}")
                    onError("Failed to load subscription options. Please try again.")
                    return
                }

                val nextAttempt = attempt + 1
                val backoffMs = OFFERINGS_RETRY_BACKOFF_MS[attempt]
                Log.w(TAG, "Offerings fetch attempt ${attempt + 1} failed: ${error.message}; retrying")
                scope.launch {
                    delay(backoffMs)
                    fetchOfferingsWithRetry(nextAttempt, onSuccess, onError)
                }
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
                    canPersistLiveProCache = true
                    updateProStatus(customerInfo)
                    scheduleTrialEndReminderIfNeeded(customerInfo)
                    if (hasRevenueCatAccess) {
                        clearOfferEligibility()
                        notificationService.cancelConversionNotifications()
                    }
                    persistProCache()
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
                canPersistLiveProCache = true
                updateProStatus(customerInfo)
                scope.launch {
                    checkPromoAccess()
                    checkDiscountPaywallAccess()
                    checkSupabaseSubscription()
                    updateCombinedProStatus()
                    persistProCache()
                    onSuccess(customerInfo)
                }
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
            PromoCodeType.FREE -> {
                hasPromoAccess = true
                updateCombinedProStatus()
            }
            PromoCodeType.TRIAL -> {
                setInfluencerOfferEligible(true)
            }
            PromoCodeType.DISCOUNT -> setDiscountPaywallUnlocked(true)
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
        val type = StartType.fromRawValue(modeName)
        return if (type.isPro) _isProUser.value else true
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
        return if (_isDiscountPaywallUnlocked.value) {
            BillingConfig.PACKAGE_ANNUAL_DEFAULT_DISCOUNT
        } else if (isEligibleForInfluencerOffer() || isEligibleForReferralOffer()) {
            BillingConfig.PACKAGE_ANNUAL_REFERRAL
        } else {
            "annual"
        }
    }

    fun isEligibleForInfluencerOffer(): Boolean {
        return _isInfluencerOfferEligible.value && !_isProUser.value
    }

    fun isEligibleForReferralOffer(): Boolean {
        return wasReferred() && !_isProUser.value
    }

    fun wasReferred(): Boolean {
        return prefs.getBoolean(KEY_WAS_REFERRED, false)
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

            updateCombinedProStatus()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check promo access: ${e.message}")
        }
    }

    private suspend fun checkDiscountPaywallAccess() {
        try {
            promoCodeService.getDiscountPaywallAccess()?.let(::setDiscountPaywallUnlocked)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check discount paywall access: ${e.message}")
        }
    }

    private fun setInfluencerOfferEligible(eligible: Boolean) {
        _isInfluencerOfferEligible.value = eligible
        prefs.edit().putBoolean(KEY_INFLUENCER_OFFER_ELIGIBLE, eligible).apply()
    }

    private fun setDiscountPaywallUnlocked(unlocked: Boolean) {
        _isDiscountPaywallUnlocked.value = unlocked
        prefs.edit().putBoolean(KEY_DISCOUNT_PAYWALL_UNLOCKED, unlocked).apply()
    }

    private fun clearOfferEligibility() {
        _isInfluencerOfferEligible.value = false
        _isDiscountPaywallUnlocked.value = false
        prefs.edit()
            .remove(KEY_INFLUENCER_OFFER_ELIGIBLE)
            .remove(KEY_WAS_REFERRED)
            .remove(KEY_DISCOUNT_PAYWALL_UNLOCKED)
            .apply()
    }

    private suspend fun checkSupabaseSubscription() {
        val userId = authService.currentUserId
        if (userId.isNullOrBlank()) {
            hasSupabaseSubscription = false
            updateCombinedProStatus()
            return
        }

        try {
            val subscriptions = supabaseClient.postgrest["subscriptions"]
                .select {
                    filter {
                        eq("app_user_id", userId)
                    }
                }
                .decodeList<SupabaseSubscriptionDto>()

            val now = Instant.now()
            hasSupabaseSubscription = subscriptions.any { subscription ->
                isSupabaseSubscriptionActive(subscription.status, subscription.expiresAt, now)
            }
            updateCombinedProStatus()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check Supabase subscription: ${e.message}")
        }
    }

    private fun updateCombinedProStatus() {
        val livePro = hasRevenueCatAccess || hasPromoAccess || hasSupabaseSubscription
        isUsingOfflineProCache = shouldPreserveOfflineProCache(
            cachedProActive = cachedProActive,
            livePro = livePro,
            hasInternetConnectivity = hasInternetConnectivity()
        )
        _isProUser.value = livePro ||
            isUsingOfflineProCache ||
            (!_hasCheckedSubscription.value && cachedProActive)
        if (_hasCheckedSubscription.value && canPersistLiveProCache) {
            persistProCache(livePro)
        }
        refreshAnalyticsPersonProperties()
    }

    fun syncPostHogDistinctIdAttribute() {
        if (!Purchases.isConfigured) return
        val distinctId = analyticsService.distinctId()
        if (distinctId.isBlank()) return
        runCatching {
            Purchases.sharedInstance.setAttributes(
                mapOf("\$posthogUserId" to distinctId)
            )
        }.onFailure {
            Log.w(TAG, "Failed to set RevenueCat PostHog attribute: ${it.message}")
        }
    }

    private fun refreshAnalyticsPersonProperties() {
        scope.launch {
            analyticsService.refreshPersonProperties(
                SubscriptionAnalyticsSnapshot(
                    isPro = _isProUser.value,
                    hasRevenueCatSubscription = hasRevenueCatAccess,
                    hasPromoAccess = hasPromoAccess,
                    hasSupabaseSubscription = hasSupabaseSubscription,
                    isBillingGracePeriod = _isInBillingGracePeriod.value,
                    wasReferred = wasReferred(),
                    completedSessionCount = getSessionCount()
                )
            )
        }
    }

    private fun signalReady(persistCache: Boolean) {
        if (_hasCheckedSubscription.value) {
            if (persistCache) persistProCache()
            return
        }

        _hasCheckedSubscription.value = true
        updateCombinedProStatus()
        if (persistCache) persistProCache()
        if (!readySignal.isCompleted) {
            readySignal.complete(Unit)
        }
    }

    private fun persistProCache(
        livePro: Boolean = hasRevenueCatAccess || hasPromoAccess || hasSupabaseSubscription
    ) {
        if (
            shouldPreserveOfflineProCache(
                cachedProActive = cachedProActive,
                livePro = livePro,
                hasInternetConnectivity = hasInternetConnectivity()
            )
        ) {
            isUsingOfflineProCache = true
            _isProUser.value = true
            return
        }

        isUsingOfflineProCache = false
        cachedProActive = livePro
        prefs.edit().putBoolean(KEY_CACHED_IS_PRO_USER, livePro).apply()
    }

    private fun hasInternetConnectivity(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun scheduleTrialEndReminderIfNeeded(customerInfo: CustomerInfo) {
        val entitlement = customerInfo.entitlements[BillingConfig.ENTITLEMENT_ID]
        val trialEndsAtMillis = entitlement
            ?.takeIf { it.isActive && it.periodType == PeriodType.TRIAL }
            ?.expirationDate
            ?.time

        if (trialEndsAtMillis != null) {
            notificationService.scheduleTrialEndReminder(trialEndsAtMillis)
        } else {
            notificationService.cancelTrialEndReminder()
        }
    }

    /**
     * Force refresh of pro status from all sources.
     */
    fun refreshProStatus() {
        refreshCustomerInfo()
        scope.launch {
            checkPromoAccess()
            checkDiscountPaywallAccess()
            checkSupabaseSubscription()
            updateCombinedProStatus()
        }
    }
}
