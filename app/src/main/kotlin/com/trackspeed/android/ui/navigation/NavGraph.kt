package com.trackspeed.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trackspeed.android.ui.screens.athletes.AthleteFormScreen
import com.trackspeed.android.ui.screens.athletes.AthleteListScreen
import com.trackspeed.android.ui.screens.home.HomeScreen
import com.trackspeed.android.ui.screens.history.SessionDetailScreen
import com.trackspeed.android.ui.screens.history.SessionHistoryScreen
import com.trackspeed.android.ui.screens.onboarding.OnboardingScreen
import com.trackspeed.android.ui.screens.onboarding.OnboardingViewModel
import com.trackspeed.android.ui.screens.paywall.PaywallScreen
import com.trackspeed.android.ui.screens.race.RaceModeScreen
import com.trackspeed.android.ui.screens.settings.SettingsScreen
import com.trackspeed.android.ui.screens.setup.SessionSetupScreen
import com.trackspeed.android.ui.screens.sync.ClockSyncScreen
import com.trackspeed.android.ui.screens.timing.BasicTimingScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Home : Screen("home")
    data object BasicTiming : Screen("basic_timing?distance={distance}&startType={startType}&athleteIds={athleteIds}") {
        fun createRoute(distance: Double = 60.0, startType: String = "standing", athleteIds: String = "") =
            "basic_timing?distance=$distance&startType=$startType&athleteIds=$athleteIds"
    }
    data object RaceMode : Screen("race_mode")
    data object ClockSync : Screen("clock_sync")
    data object Calibration : Screen("calibration")
    data object ActiveTiming : Screen("active_timing")
    data object DevicePairing : Screen("device_pairing")
    data object History : Screen("history")
    data object Settings : Screen("settings")
    data object Paywall : Screen("paywall")

    data object SessionSetup : Screen("session_setup?distance={distance}&startType={startType}") {
        fun createRoute(distance: Double? = null, startType: String? = null): String {
            val params = mutableListOf<String>()
            if (distance != null) params.add("distance=$distance")
            if (startType != null) params.add("startType=$startType")
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
}

@Composable
fun TrackSpeedNavHost(
    navController: NavHostController = rememberNavController()
) {
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()

    // Resolve the start destination once from DataStore.
    // rememberSaveable preserves across config changes; LaunchedEffect runs once.
    var resolvedStart by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val completed = onboardingViewModel.isOnboardingCompleted()
        resolvedStart = if (completed) Screen.Home.route else Screen.Onboarding.route
    }

    // Show a black screen while loading the preference (prevents flash of wrong screen).
    val start = resolvedStart
    if (start == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                    onboardingViewModel.completeOnboarding()
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onBasicModeClick = {
                    navController.navigate(Screen.SessionSetup.createRoute())
                },
                onRaceModeClick = {
                    navController.navigate(Screen.RaceMode.route)
                },
                onClockSyncClick = {
                    navController.navigate(Screen.ClockSync.route)
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
                onTemplateClick = { distance, startType ->
                    navController.navigate(Screen.SessionSetup.createRoute(distance, startType))
                },
                onPaywallClick = {
                    navController.navigate(Screen.Paywall.route)
                },
                onAthletesClick = {
                    navController.navigate(Screen.AthleteList.route)
                }
            )
        }

        composable(
            route = Screen.BasicTiming.route,
            arguments = listOf(
                navArgument("distance") { type = NavType.FloatType; defaultValue = 60f },
                navArgument("startType") { type = NavType.StringType; defaultValue = "standing" },
                navArgument("athleteIds") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val distance = backStackEntry.arguments?.getFloat("distance")?.toDouble() ?: 60.0
            val startType = backStackEntry.arguments?.getString("startType") ?: "standing"
            BasicTimingScreen(
                onNavigateBack = {
                    navController.popBackStack()
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

        composable(Screen.RaceMode.route) {
            RaceModeScreen(
                onNavigateBack = {
                    navController.popBackStack()
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

        composable(
            route = Screen.SessionSetup.route,
            arguments = listOf(
                navArgument("distance") { type = NavType.FloatType; defaultValue = 0f },
                navArgument("startType") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            SessionSetupScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onStartSession = { distance, startType, athleteIds ->
                    val athleteIdsParam = athleteIds.joinToString(",")
                    navController.navigate(Screen.BasicTiming.createRoute(distance, startType, athleteIdsParam)) {
                        popUpTo(Screen.SessionSetup.route) { inclusive = true }
                    }
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
    }
}
