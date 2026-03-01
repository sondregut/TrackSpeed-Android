package com.trackspeed.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.ui.navigation.TrackSpeedNavHost
import com.trackspeed.android.ui.screens.settings.SettingsViewModel
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

sealed class DeepLinkEvent {
    data class Invite(val code: String) : DeepLinkEvent()
    data object Promo : DeepLinkEvent()
    data object Subscribe : DeepLinkEvent()
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

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

        setContent {
            val appearanceMode by settingsRepository.appearanceMode.collectAsState(initial = "midnight")
            val appTheme = SettingsViewModel.appearanceModeToTheme(appearanceMode)

            TrackSpeedTheme(appTheme = appTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TrackSpeedNavHost(deepLinkEvent = deepLinkEvent, onDeepLinkConsumed = ::consumeDeepLinkEvent)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        Log.d(TAG, "Deeplink received: $uri")

        val event = parseDeepLink(uri)
        if (event != null) {
            if (event is DeepLinkEvent.Invite) {
                storePendingReferralCode(event.code)
            }
            _deepLinkEvent.value = event
        }
    }

    private fun parseDeepLink(uri: Uri): DeepLinkEvent? {
        // trackspeed://invite/CODE or https://mytrackspeed.com/invite/CODE
        val pathSegments = uri.pathSegments
        if (pathSegments.size >= 2 && pathSegments[0] == "invite") {
            val code = pathSegments[1]
            if (code.isNotBlank()) {
                return DeepLinkEvent.Invite(code)
            }
        }
        if (pathSegments.size == 1 && pathSegments[0] == "invite") {
            // trackspeed://invite (no code) — ignore
            return null
        }

        // trackspeed://promo or trackspeed://subscribe
        val host = uri.host
        if (uri.scheme == "trackspeed") {
            return when (host) {
                "invite" -> {
                    // trackspeed://invite/CODE — host is "invite", path has the code
                    val code = pathSegments.firstOrNull()
                    if (!code.isNullOrBlank()) DeepLinkEvent.Invite(code) else null
                }
                "promo" -> DeepLinkEvent.Promo
                "subscribe" -> DeepLinkEvent.Subscribe
                else -> null
            }
        }

        return null
    }

    private fun storePendingReferralCode(code: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PENDING_REFERRAL_CODE, code).apply()
        Log.d(TAG, "Stored pending referral code: $code")
    }

    companion object {
        private const val TAG = "MainActivity"
        const val PREFS_NAME = "trackspeed"
        const val KEY_PENDING_REFERRAL_CODE = "pendingReferralCode"

        fun getPendingReferralCode(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_PENDING_REFERRAL_CODE, null)
        }

        fun clearPendingReferralCode(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PENDING_REFERRAL_CODE).apply()
        }
    }
}
