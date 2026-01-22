package com.trackspeed.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trackspeed.android.ui.screens.home.HomeScreen
import com.trackspeed.android.ui.screens.timing.BasicTimingScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object BasicTiming : Screen("basic_timing")
    data object RaceMode : Screen("race_mode")
    data object Calibration : Screen("calibration")
    data object ActiveTiming : Screen("active_timing")
    data object DevicePairing : Screen("device_pairing")
    data object History : Screen("history")
    data object Settings : Screen("settings")

    data object Results : Screen("results/{crossingId}") {
        fun createRoute(crossingId: String) = "results/$crossingId"
    }

    data object SessionDetail : Screen("session/{sessionId}") {
        fun createRoute(sessionId: String) = "session/$sessionId"
    }
}

@Composable
fun TrackSpeedNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onBasicModeClick = {
                    navController.navigate(Screen.BasicTiming.route)
                },
                onRaceModeClick = {
                    navController.navigate(Screen.RaceMode.route)
                },
                onHistoryClick = {
                    navController.navigate(Screen.History.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.BasicTiming.route) {
            BasicTimingScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // TODO: Add other screen destinations as they are implemented
    }
}
