package com.trackspeed.android

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
import com.trackspeed.android.referral.ReferralService
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
        if (uri.scheme == SCHEME_TRACKSPEED) {
            return when (uri.host) {
                HOST_INVITE -> uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }?.let { DeepLinkEvent.Invite(it) }
                HOST_PROMO -> DeepLinkEvent.Promo
                HOST_SUBSCRIBE -> DeepLinkEvent.Subscribe
                else -> null
            }
        }
        // HTTPS app links: https://mytrackspeed.com/invite/CODE
        if (uri.pathSegments.size >= 2 && uri.pathSegments[0] == HOST_INVITE) {
            val code = uri.pathSegments[1]
            if (code.isNotBlank()) return DeepLinkEvent.Invite(code)
        }
        return null
    }

    private fun storePendingReferralCode(code: String) {
        ReferralService.storePendingReferralCode(this, code)
        Log.d(TAG, "Stored pending referral code: $code")
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SCHEME_TRACKSPEED = "trackspeed"
        private const val HOST_INVITE = "invite"
        private const val HOST_PROMO = "promo"
        private const val HOST_SUBSCRIBE = "subscribe"
    }
}
