package com.trackspeed.android.ui.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.trackspeed.android.DeepLinkEvent
import com.trackspeed.android.ui.theme.gradientBackground
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.StateFlow
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trackspeed.android.ui.screens.athletes.AthleteFormScreen
import com.trackspeed.android.ui.screens.athletes.AthleteListScreen
import com.trackspeed.android.ui.screens.auth.AuthScreen
import com.trackspeed.android.ui.screens.debug.DebugToolsScreen
import com.trackspeed.android.ui.screens.history.RunDetailScreen
import com.trackspeed.android.ui.screens.history.RunFramesScrubberScreen
import com.trackspeed.android.ui.screens.history.SessionDetailScreen
import com.trackspeed.android.ui.screens.history.SessionHistoryScreen
import com.trackspeed.android.ui.screens.history.ShareResultScreen
import com.trackspeed.android.ui.screens.history.ShareSessionScreen
import com.trackspeed.android.ui.screens.home.HomeScreen
import com.trackspeed.android.ui.screens.onboarding.OnboardingScreen
import com.trackspeed.android.ui.screens.onboarding.OnboardingViewModel
import com.trackspeed.android.ui.screens.paywall.PaywallScreen
import com.trackspeed.android.ui.screens.race.RaceModeScreen
import com.trackspeed.android.ui.screens.referral.ReferralScreen
import com.trackspeed.android.ui.screens.settings.NotificationSettingsScreen
import com.trackspeed.android.ui.screens.settings.SettingsScreen
import com.trackspeed.android.ui.screens.setup.SessionSetupScreen
import com.trackspeed.android.ui.screens.stats.StatsScreen
import com.trackspeed.android.ui.screens.sync.ClockSyncScreen
import com.trackspeed.android.ui.screens.timing.BasicTimingScreen
import com.trackspeed.android.ui.screens.tools.DistanceConverterScreen
import com.trackspeed.android.ui.screens.tools.ToolsScreen
import com.trackspeed.android.ui.screens.tools.WindAdjustmentScreen
import com.trackspeed.android.ui.screens.videooverlay.VideoOverlayScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Home : Screen("home")
    data object BasicTiming : Screen("basic_timing?distance={distance}&startType={startType}&athleteIds={athleteIds}") {
        fun createRoute(distance: Double = 60.0, startType: String = "flying", athleteIds: String = "") =
            "basic_timing?distance=$distance&startType=$startType&athleteIds=$athleteIds"
    }
    data object RaceMode : Screen("race_mode?distance={distance}&startType={startType}&numberOfGates={numberOfGates}&gateDistances={gateDistances}&mode={mode}&hostRole={hostRole}&athleteIds={athleteIds}&guestJoin={guestJoin}") {
        fun createRoute(
            distance: Double? = null,
            startType: String? = null,
            numberOfGates: Int = 2,
            gateDistances: List<Double> = emptyList(),
            mode: String = "auto",
            hostRole: String = "finishLine",
            athleteIds: String = "",
            guestJoin: Boolean = false
        ): String {
            val params = mutableListOf<String>()
            if (distance != null) params.add("distance=$distance")
            if (startType != null) params.add("startType=$startType")
            params.add("numberOfGates=$numberOfGates")
            if (gateDistances.isNotEmpty()) params.add("gateDistances=${gateDistances.joinToString(",")}")
            if (mode != "auto") params.add("mode=$mode")
            if (hostRole != "finishLine") params.add("hostRole=$hostRole")
            if (athleteIds.isNotBlank()) params.add("athleteIds=$athleteIds")
            if (guestJoin) params.add("guestJoin=true")
            return if (params.isEmpty()) "race_mode" else "race_mode?${params.joinToString("&")}"
        }
    }
    data object ClockSync : Screen("clock_sync")
    data object Calibration : Screen("calibration")
    data object ActiveTiming : Screen("active_timing")
    data object DevicePairing : Screen("device_pairing")
    data object History : Screen("history")
    data object Settings : Screen("settings")
    data object Auth : Screen("auth?signIn={signIn}") {
        fun createRoute(signInMode: Boolean = false) = "auth?signIn=$signInMode"
    }
    data object Paywall : Screen("paywall")
    data object DiscountPaywall : Screen("discount_paywall")
    data object RedeemPromo : Screen("redeem_promo")
    data object Stats : Screen("stats")

    data object SessionSetup : Screen("session_setup?presetId={presetId}&distance={distance}&startType={startType}&minPhones={minPhones}&numberOfGates={numberOfGates}&athleteIds={athleteIds}&allowsSolo={allowsSolo}") {
        fun createRoute(
            distance: Double? = null,
            startType: String? = null,
            minPhones: Int = 2,
            presetId: String? = null,
            numberOfGates: Int? = null,
            athleteIds: Set<String> = emptySet(),
            allowsSolo: Boolean = false
        ): String {
            val params = mutableListOf<String>()
            if (!presetId.isNullOrBlank()) params.add("presetId=$presetId")
            if (distance != null) params.add("distance=$distance")
            if (startType != null) params.add("startType=$startType")
            params.add("minPhones=$minPhones")
            if (numberOfGates != null) params.add("numberOfGates=$numberOfGates")
            if (athleteIds.isNotEmpty()) {
                params.add("athleteIds=${Uri.encode(athleteIds.joinToString(","))}")
            }
            params.add("allowsSolo=$allowsSolo")
            return if (params.isEmpty()) "session_setup" else "session_setup?${params.joinToString("&")}"
        }
    }

    data object AthleteList : Screen("athlete_list")

    data object AthleteForm : Screen("athlete_form?athleteId={athleteId}") {
        fun createRoute(athleteId: String? = null) =
            if (athleteId != null) "athlete_form?athleteId=$athleteId" else "athlete_form"
    }

    data object Results : Screen("results/{crossingId}") {
        fun createRoute(crossingId: String) = "results/$crossingId"
    }

    data object SessionDetail : Screen("session_detail/{sessionId}") {
        fun createRoute(sessionId: String) = "session_detail/$sessionId"
    }

    data object RunDetail : Screen("run_detail/{runId}/{sessionId}") {
        fun createRoute(runId: String, sessionId: String) = "run_detail/$runId/$sessionId"
    }

    data object ShareResult : Screen("share_result/{runId}/{sessionId}") {
        fun createRoute(runId: String, sessionId: String) = "share_result/$runId/$sessionId"
    }

    data object ShareSession : Screen("share_session/{sessionId}") {
        fun createRoute(sessionId: String) = "share_session/$sessionId"
    }

    data object VideoOverlay : Screen("video_overlay/{runId}/{sessionId}") {
        fun createRoute(runId: String, sessionId: String) = "video_overlay/$runId/$sessionId"
    }

    data object RunFramesScrubber : Screen("run_frames/{runId}/{sessionId}") {
        fun createRoute(runId: String, sessionId: String) = "run_frames/$runId/$sessionId"
    }

    data object NotificationSettings : Screen("notification_settings")
    data object Tools : Screen("tools")
    data object WindAdjustment : Screen("wind_adjustment")
    data object DistanceConverter : Screen("distance_converter")
    data object Referral : Screen("referral")
    data object DebugTools : Screen("debug_tools")
}

@Composable
fun TrackSpeedNavHost(
    navController: NavHostController = rememberNavController(),
    deepLinkEvent: StateFlow<DeepLinkEvent?>? = null,
    onDeepLinkConsumed: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()

    // Resolve the start destination once from DataStore.
    // rememberSaveable preserves across config changes; LaunchedEffect runs once.
    var resolvedStart by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val completed = onboardingViewModel.isOnboardingCompleted()
        resolvedStart = if (completed) Screen.Home.route else Screen.Onboarding.route
    }

    // Handle deeplink events
    val currentDeepLink by deepLinkEvent?.collectAsState() ?: remember { mutableStateOf(null) }
    LaunchedEffect(currentDeepLink, resolvedStart) {
        val event = currentDeepLink ?: return@LaunchedEffect
        val start = resolvedStart ?: return@LaunchedEffect
        if (start == Screen.Home.route) {
            when (event) {
                is DeepLinkEvent.Invite -> navController.navigateHome()
                DeepLinkEvent.Promo,
                DeepLinkEvent.Subscribe -> navController.navigate(Screen.Paywall.route)
                DeepLinkEvent.DiscountPromo -> navController.navigate(Screen.DiscountPaywall.route)
                DeepLinkEvent.TrainingReminder -> navController.navigateHome()
                DeepLinkEvent.BillingIssue -> openPlaySubscriptionManagement(context)
            }
        }
        onDeepLinkConsumed?.invoke()
    }

    // Show a gradient screen while loading the preference (prevents flash of wrong screen).
    val start = resolvedStart
    if (start == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .gradientBackground()
        )
        return
    }

    NavHost(
        navController = navController,
        startDestination = start
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onGuestJoinSession = {
                    navController.navigate(
                        Screen.RaceMode.createRoute(mode = "join", guestJoin = true)
                    )
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onBasicModeClick = {
                    navController.navigate(
                        Screen.SessionSetup.createRoute(
                            distance = 30.0,
                            startType = "flying",
                            minPhones = 2,
                            numberOfGates = 2,
                            allowsSolo = true
                        )
                    )
                },
                onRaceModeClick = {
                    navController.navigate(Screen.RaceMode.createRoute())
                },
                onClockSyncClick = {
                    navController.navigate(Screen.RaceMode.createRoute(mode = "join"))
                },
                onHistoryClick = {
                    navController.navigate(Screen.History.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onSessionClick = { sessionId ->
                    navController.navigate(Screen.SessionDetail.createRoute(sessionId))
                },
                onRepeatSession = { configuration ->
                    navController.navigate(
                        Screen.SessionSetup.createRoute(
                            distance = configuration.distance,
                            startType = configuration.startType,
                            minPhones = if (configuration.numberOfGates == 1) 1 else 2,
                            numberOfGates = configuration.numberOfGates,
                            athleteIds = configuration.athleteIds,
                            allowsSolo = configuration.numberOfGates == 1
                        )
                    )
                },
                onTemplateClick = { distance, startType, minPhones, presetId ->
                    navController.navigate(
                        Screen.SessionSetup.createRoute(
                            distance = distance,
                            startType = startType,
                            minPhones = minPhones,
                            presetId = presetId,
                            numberOfGates = minPhones,
                            allowsSolo = false
                        )
                    )
                },
                onPaywallClick = {
                    navController.navigate(Screen.Paywall.route)
                },
                onAthletesClick = {
                    navController.navigate(Screen.AthleteList.route)
                },
                onAuthClick = {
                    navController.navigate(Screen.Auth.route)
                },
                onStatsClick = {
                    navController.navigate(Screen.Stats.route)
                },
                onReferralClick = {
                    navController.navigate(Screen.Referral.route)
                },
                onWindAdjustmentClick = {
                    navController.navigate(Screen.WindAdjustment.route)
                },
                onDistanceConverterClick = {
                    navController.navigate(Screen.DistanceConverter.route)
                },
                onDiscountPaywallClick = {
                    navController.navigate(Screen.DiscountPaywall.route)
                }
            )
        }

        composable(
            route = Screen.BasicTiming.route,
            arguments = listOf(
                navArgument("distance") { type = NavType.FloatType; defaultValue = 60f },
                navArgument("startType") { type = NavType.StringType; defaultValue = "flying" },
                navArgument("athleteIds") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val distance = backStackEntry.arguments?.getFloat("distance")?.toDouble() ?: 60.0
            val startType = backStackEntry.arguments?.getString("startType") ?: "flying"
            BasicTimingScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onPaywallClick = {
                    navController.navigate(Screen.Paywall.route)
                },
                distance = distance,
                startType = startType
            )
        }

        composable(Screen.ClockSync.route) {
            ClockSyncScreen(
                onSyncComplete = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.RaceMode.route,
            arguments = listOf(
                navArgument("distance") { type = NavType.FloatType; defaultValue = 0f },
                navArgument("startType") { type = NavType.StringType; defaultValue = "" },
                navArgument("numberOfGates") { type = NavType.IntType; defaultValue = 2 },
                navArgument("gateDistances") { type = NavType.StringType; defaultValue = "" },
                navArgument("mode") { type = NavType.StringType; defaultValue = "auto" },
                navArgument("hostRole") { type = NavType.StringType; defaultValue = "finishLine" },
                navArgument("athleteIds") { type = NavType.StringType; defaultValue = "" },
                navArgument("guestJoin") { type = NavType.BoolType; defaultValue = false }
            )
        ) { raceBackStackEntry ->
            RaceModeScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onViewSession = { sessionId ->
                    navController.navigate(Screen.SessionDetail.createRoute(sessionId)) {
                        popUpTo(raceBackStackEntry.destination.id) {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable(Screen.History.route) {
            SessionHistoryScreen(
                onSessionClick = { sessionId ->
                    navController.navigate(Screen.SessionDetail.createRoute(sessionId))
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onPaywallClick = {
                    navController.navigate(Screen.Paywall.route)
                },
                onManageSubscriptionClick = {
                    openPlaySubscriptionManagement(context)
                },
                onRedeemPromoClick = {
                    navController.navigate(Screen.RedeemPromo.route)
                },
                onShowOnboarding = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onShowFirstSessionTutorial = {
                    val popped = navController.popBackStack(Screen.Home.route, inclusive = false)
                    if (!popped) {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                inclusive = false
                            }
                            launchSingleTop = true
                        }
                    }
                },
                onNotificationSettingsClick = {
                    navController.navigate(Screen.NotificationSettings.route)
                },
                onDebugToolsClick = {
                    navController.navigate(Screen.DebugTools.route)
                }
            )
        }

        composable(
            route = Screen.Auth.route,
            arguments = listOf(
                navArgument("signIn") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val signInMode = backStackEntry.arguments?.getBoolean("signIn") ?: false
            val fromOnboarding = navController.previousBackStackEntry?.destination?.route == Screen.Onboarding.route
            AuthScreen(
                startInSignInMode = signInMode,
                onAuthSuccess = {
                    if (fromOnboarding) {
                        if (onboardingViewModel.completeOnboardingIfPro()) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        } else {
                            navController.navigate(Screen.Paywall.route) {
                                popUpTo(Screen.Auth.route) { inclusive = true }
                            }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.SessionDetail.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) {
            SessionDetailScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onRunClick = { runId, sessionId ->
                    navController.navigate(Screen.RunDetail.createRoute(runId, sessionId))
                },
                onShareRunClick = { runId, sessionId ->
                    navController.navigate(Screen.ShareResult.createRoute(runId, sessionId))
                },
                onShareSessionClick = { sessionId ->
                    navController.navigate(Screen.ShareSession.createRoute(sessionId))
                },
                onVideoOverlayClick = { runId, sessionId ->
                    navController.navigate(Screen.VideoOverlay.createRoute(runId, sessionId))
                },
                onFramesClick = { runId, sessionId ->
                    navController.navigate(Screen.RunFramesScrubber.createRoute(runId, sessionId))
                }
            )
        }

        composable(
            route = Screen.RunDetail.route,
            arguments = listOf(
                navArgument("runId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) {
            RunDetailScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onShareClick = { runId, sessionId ->
                    navController.navigate(Screen.ShareResult.createRoute(runId, sessionId))
                },
                onVideoOverlayClick = { runId, sessionId ->
                    navController.navigate(Screen.VideoOverlay.createRoute(runId, sessionId))
                },
                onFramesClick = { runId, sessionId ->
                    navController.navigate(Screen.RunFramesScrubber.createRoute(runId, sessionId))
                }
            )
        }

        composable(
            route = Screen.ShareResult.route,
            arguments = listOf(
                navArgument("runId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) {
            ShareResultScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.ShareSession.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) {
            ShareSessionScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.VideoOverlay.route,
            arguments = listOf(
                navArgument("runId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) {
            VideoOverlayScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.RunFramesScrubber.route,
            arguments = listOf(
                navArgument("runId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) {
            RunFramesScrubberScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Stats.route) {
            StatsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Paywall.route) {
            PaywallScreen(
                onClose = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.DiscountPaywall.route) {
            PaywallScreen(
                onClose = {
                    navController.popBackStack()
                },
                preferDiscountPackage = true
            )
        }

        composable(Screen.RedeemPromo.route) {
            PaywallScreen(
                onClose = {
                    navController.popBackStack()
                },
                showPromoSheetOnLaunch = true
            )
        }

        composable(
            route = Screen.SessionSetup.route,
            arguments = listOf(
                navArgument("presetId") { type = NavType.StringType; defaultValue = "" },
                navArgument("distance") { type = NavType.FloatType; defaultValue = 0f },
                navArgument("startType") { type = NavType.StringType; defaultValue = "" },
                navArgument("minPhones") { type = NavType.IntType; defaultValue = 2 },
                navArgument("numberOfGates") { type = NavType.IntType; defaultValue = 2 },
                navArgument("athleteIds") { type = NavType.StringType; defaultValue = "" },
                navArgument("allowsSolo") { type = NavType.BoolType; defaultValue = false }
            )
        ) {
            SessionSetupScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onStartSession = { distance, startType, athleteIds, numberOfGates, gateDistances, hostRole ->
                    if (numberOfGates >= 2) {
                        navController.navigate(
                            Screen.RaceMode.createRoute(
                                distance = distance,
                                startType = startType,
                                numberOfGates = numberOfGates,
                                gateDistances = gateDistances,
                                mode = "host",
                                hostRole = hostRole,
                                athleteIds = athleteIds.joinToString(",")
                            )
                        ) {
                            popUpTo(Screen.SessionSetup.route) { inclusive = true }
                        }
                    } else {
                        val athleteIdsParam = athleteIds.joinToString(",")
                        navController.navigate(Screen.BasicTiming.createRoute(distance, startType, athleteIdsParam)) {
                            popUpTo(Screen.SessionSetup.route) { inclusive = true }
                        }
                    }
                },
                onAddAthlete = {
                    navController.navigate(Screen.AthleteForm.createRoute())
                }
            )
        }

        composable(Screen.AthleteList.route) {
            AthleteListScreen(
                onAthleteClick = { athleteId ->
                    navController.navigate(Screen.AthleteForm.createRoute(athleteId))
                },
                onAddClick = {
                    navController.navigate(Screen.AthleteForm.createRoute())
                }
            )
        }

        composable(
            route = Screen.AthleteForm.route,
            arguments = listOf(
                navArgument("athleteId") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            AthleteFormScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.NotificationSettings.route) {
            NotificationSettingsScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Tools.route) {
            ToolsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onWindAdjustmentClick = {
                    navController.navigate(Screen.WindAdjustment.route)
                },
                onDistanceConverterClick = {
                    navController.navigate(Screen.DistanceConverter.route)
                }
            )
        }

        composable(Screen.WindAdjustment.route) {
            WindAdjustmentScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.DistanceConverter.route) {
            DistanceConverterScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Referral.route) {
            ReferralScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.DebugTools.route) {
            DebugToolsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

private fun NavHostController.navigateHome() {
    navigate(Screen.Home.route) {
        popUpTo(graph.startDestinationId) {
            inclusive = false
        }
        launchSingleTop = true
    }
}

private fun openPlaySubscriptionManagement(context: Context) {
    val subscriptionsUrl = "https://play.google.com/store/account/subscriptions?package=${context.packageName}"
    val fallbackUrl = "https://play.google.com/store/account/subscriptions"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(subscriptionsUrl))
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
    }
}
