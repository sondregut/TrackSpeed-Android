package com.trackspeed.android

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.cloud.AuthService
import com.trackspeed.android.cloud.AuthState
import com.trackspeed.android.cloud.CrossingDebugUploadQueue
import com.trackspeed.android.cloud.RaceEventService
import com.trackspeed.android.cloud.RemoteConfigService
import com.trackspeed.android.cloud.ThumbnailUploadQueue
import com.trackspeed.android.cloud.TimingWorkloadCoordinator
import com.trackspeed.android.cloud.isRealAuthenticated
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.AuthRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.diagnostics.DurableDeviceLogUploadQueue
import com.trackspeed.android.notifications.NotificationIds
import com.trackspeed.android.notifications.NotificationReceiver
import com.trackspeed.android.notifications.NotificationService
import com.trackspeed.android.referral.ReferralService
import com.trackspeed.android.ui.components.RemoteConfigGate
import com.trackspeed.android.ui.navigation.TrackSpeedNavHost
import com.trackspeed.android.ui.screens.settings.SettingsViewModel
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

sealed class DeepLinkEvent {
    data class Invite(val code: String) : DeepLinkEvent()
    data object Promo : DeepLinkEvent()
    data object DiscountPromo : DeepLinkEvent()
    data object Subscribe : DeepLinkEvent()
    data object TrainingReminder : DeepLinkEvent()
    data object BillingIssue : DeepLinkEvent()
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var authService: AuthService

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var sessionRepository: SessionRepository

    @Inject
    lateinit var remoteConfigService: RemoteConfigService

    @Inject
    lateinit var notificationService: NotificationService

    @Inject
    lateinit var subscriptionManager: SubscriptionManager

    @Inject
    lateinit var referralService: ReferralService

    @Inject
    lateinit var thumbnailUploadQueue: ThumbnailUploadQueue

    @Inject
    lateinit var crossingDebugUploadQueue: CrossingDebugUploadQueue

    @Inject
    lateinit var raceEventService: RaceEventService

    @Inject
    lateinit var durableDeviceLogUploadQueue: DurableDeviceLogUploadQueue

    @Inject
    lateinit var timingWorkloadCoordinator: TimingWorkloadCoordinator

    private var cloudMaintenanceInFlight = false
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private val _deepLinkEvent = MutableStateFlow<DeepLinkEvent?>(null)
    val deepLinkEvent: StateFlow<DeepLinkEvent?> = _deepLinkEvent.asStateFlow()

    fun consumeDeepLinkEvent() {
        _deepLinkEvent.value = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        scheduleCloudMaintenance(bootstrapAuth = true)

        lifecycleScope.launch {
            subscriptionManager.waitForReady()
            combine(
                subscriptionManager.isProUser,
                sessionRepository.getTotalSessionCount()
            ) { isProUser, completedSessionCount ->
                isProUser to completedSessionCount
            }.collect { (isProUser, completedSessionCount) ->
                notificationService.scheduleStartupReminders(isProUser, completedSessionCount)
            }
        }

        setContent {
            val appearanceMode by settingsRepository.appearanceMode.collectAsState(initial = "midnight")
            val appTheme = SettingsViewModel.appearanceModeToTheme(appearanceMode)
            val remoteConfigState by remoteConfigService.state.collectAsState()

            TrackSpeedTheme(appTheme = appTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        TrackSpeedNavHost(deepLinkEvent = deepLinkEvent, onDeepLinkConsumed = ::consumeDeepLinkEvent)
                    }
                    RemoteConfigGate(
                        state = remoteConfigState,
                        currentVersion = BuildConfig.VERSION_NAME,
                        onUpdateClick = ::openPlayStoreListing
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        registerConnectivityMaintenanceCallback()
    }

    override fun onResume() {
        super.onResume()
        scheduleCloudMaintenance(bootstrapAuth = false)
    }

    override fun onStop() {
        unregisterConnectivityMaintenanceCallback()
        super.onStop()
    }

    private fun scheduleCloudMaintenance(bootstrapAuth: Boolean) {
        if (timingWorkloadCoordinator.isLiveTimingActive) {
            Log.d(TAG, "Live timing active; skipping foreground cloud maintenance")
            return
        }
        if (cloudMaintenanceInFlight) return
        cloudMaintenanceInFlight = true
        lifecycleScope.launch {
            try {
                if (bootstrapAuth) {
                    authService.checkSession()
                    (authService.authState.value as? AuthState.Authenticated)
                        ?.takeIf { it.isRealAuthenticated() }
                        ?.userId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { subscriptionManager.logIn(it) }
                }
                authService.ensureAnonymousSession()
                remoteConfigService.refreshIfStale()
                performCloudMaintenance()
            } catch (e: Exception) {
                Log.w(TAG, "Cloud maintenance skipped after non-critical error", e)
            } finally {
                cloudMaintenanceInFlight = false
            }
        }
    }

    private suspend fun performCloudMaintenance() {
        authRepository.processPendingProfileSync()
        sessionRepository.processPendingCloudDeletions()
        sessionRepository.processPendingCloudUploads()
        sessionRepository.processPendingFlyingPrSync()
        thumbnailUploadQueue.processQueue()
        crossingDebugUploadQueue.processQueue()
        durableDeviceLogUploadQueue.processQueue()
        raceEventService.processPendingRaceEvents("foreground_maintenance")
        sessionRepository.syncAthletesFromCloud()
        sessionRepository.syncSessionsFromCloud()
    }

    private fun registerConnectivityMaintenanceCallback() {
        if (connectivityCallback != null) return
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (hasValidatedInternet()) {
                    runOnUiThread { scheduleCloudMaintenance(bootstrapAuth = false) }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    runOnUiThread { scheduleCloudMaintenance(bootstrapAuth = false) }
                }
            }
        }

        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            connectivityCallback = callback
        }.onFailure {
            Log.w(TAG, "Unable to register connectivity maintenance callback", it)
        }
    }

    private fun unregisterConnectivityMaintenanceCallback() {
        val callback = connectivityCallback ?: return
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching {
            connectivityManager?.unregisterNetworkCallback(callback)
        }.onFailure {
            Log.w(TAG, "Unable to unregister connectivity maintenance callback", it)
        }
        connectivityCallback = null
    }

    private fun hasValidatedInternet(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun handleIntent(intent: Intent?) {
        val notificationType = intent?.getStringExtra(NotificationReceiver.EXTRA_NOTIFICATION_TYPE)
        if (!notificationType.isNullOrBlank()) {
            Log.d(TAG, "Notification tap received: $notificationType")
            parseNotificationTap(notificationType)?.let { event ->
                _deepLinkEvent.value = event
            }
            return
        }

        val uri = intent?.data ?: return
        Log.d(TAG, "Deeplink received: $uri")

        val event = parseDeepLink(uri)
        if (event != null) {
            if (event is DeepLinkEvent.Invite) {
                handleReferralInvite(event.code)
            }
            _deepLinkEvent.value = event
        }
    }

    private fun parseDeepLink(uri: Uri): DeepLinkEvent? {
        if (uri.scheme == SCHEME_TRACKSPEED) {
            return when (uri.host) {
                HOST_INVITE -> uri.pathSegments.firstOrNull()
                    ?.normalizedReferralCode()
                    ?.let { DeepLinkEvent.Invite(it) }
                HOST_PROMO -> DeepLinkEvent.Promo
                HOST_SUBSCRIBE -> DeepLinkEvent.Subscribe
                else -> null
            }
        }
        // HTTPS app links: https://mytrackspeed.com/invite/CODE
        if (uri.pathSegments.size >= 2 && uri.pathSegments[0] == HOST_INVITE) {
            uri.pathSegments[1].normalizedReferralCode()?.let { code ->
                return DeepLinkEvent.Invite(code)
            }
        }
        return null
    }

    private fun parseNotificationTap(notificationType: String): DeepLinkEvent? {
        return when (notificationType) {
            NotificationIds.TRAINING_REMINDER,
            NotificationIds.RATING_PROMPT,
            NotificationIds.TEST_NOTIFICATION -> DeepLinkEvent.TrainingReminder

            NotificationIds.BILLING_ISSUE_REMINDER,
            NotificationIds.TRIAL_ENDING_REMINDER -> DeepLinkEvent.BillingIssue

            NotificationIds.TRY_PRO_REMINDER,
            NotificationIds.PROMO_OFFER_REMINDER,
            NotificationIds.FIRST_SESSION_NUDGE,
            NotificationIds.MILESTONE_NUDGE,
            NotificationIds.DAY_14_FOLLOW_UP,
            NotificationIds.DAY_30_FINAL_NUDGE -> DeepLinkEvent.DiscountPromo

            else -> null
        }
    }

    private fun handleReferralInvite(code: String) {
        val isAuthenticated = (authService.authState.value as? AuthState.Authenticated)
            ?.isRealAuthenticated() == true

        if (isAuthenticated) {
            lifecycleScope.launch {
                val success = referralService.trackReferralSignup(code)
                if (success) {
                    Log.d(TAG, "Processed referral for authenticated user: $code")
                } else {
                    Log.w(TAG, "Failed to process referral for authenticated user: $code")
                }
            }
        } else {
            storePendingReferralCode(code)
        }
    }

    private fun storePendingReferralCode(code: String) {
        ReferralService.storePendingReferralCode(this, code)
        Log.d(TAG, "Stored pending referral code: $code")
    }

    private fun String.normalizedReferralCode(): String? {
        val trimmed = trim()
        if (trimmed.length !in 4..10) return null
        if (!trimmed.all { it.isLetterOrDigit() }) return null
        return trimmed.uppercase(Locale.US)
    }

    private fun openPlayStoreListing() {
        val url = "https://play.google.com/store/apps/details?id=$packageName"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SCHEME_TRACKSPEED = "trackspeed"
        private const val HOST_INVITE = "invite"
        private const val HOST_PROMO = "promo"
        private const val HOST_SUBSCRIBE = "subscribe"
    }
}
