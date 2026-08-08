package com.trackspeed.android.ui.screens.onboarding.steps

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.ui.screens.paywall.TrackSpeedProPaywallContent
import com.trackspeed.android.ui.screens.paywall.PaywallViewModel
import com.trackspeed.android.ui.screens.paywall.PurchaseState
import kotlinx.coroutines.launch

@Composable
fun PaywallStep(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    viewModel: PaywallViewModel = hiltViewModel()
) {
    val selectedPlan by viewModel.selectedPlan.collectAsStateWithLifecycle()
    val purchaseState by viewModel.purchaseState.collectAsStateWithLifecycle()
    val isLoadingOfferings by viewModel.isLoadingOfferings.collectAsStateWithLifecycle()
    val offeringsError by viewModel.offeringsError.collectAsStateWithLifecycle()
    val isProUser by viewModel.isProUser.collectAsStateWithLifecycle()
    val promoSheetState by viewModel.promoSheetState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.setAnalyticsSource(PaywallViewModel.SOURCE_ONBOARDING)
    }

    LaunchedEffect(isLoadingOfferings) {
        if (!isLoadingOfferings) {
            viewModel.trackPaywallViewedIfNeeded()
        }
    }

    LaunchedEffect(purchaseState) {
        if (purchaseState is PurchaseState.Success) onContinue()
    }

    LaunchedEffect(isProUser) {
        if (isProUser) onContinue()
    }

    val weeklyPlan = viewModel.getWeeklyPlan()
    val yearlyPlan = viewModel.getYearlyPlan()

    TrackSpeedProPaywallContent(
        selectedPlan = selectedPlan,
        purchaseState = purchaseState,
        isLoadingOfferings = isLoadingOfferings,
        offeringsError = offeringsError,
        weeklyPlan = weeklyPlan,
        yearlyPlan = yearlyPlan,
        onClose = {
            coroutineScope.launch {
                viewModel.handleCloseTapped()
                onSkip()
            }
        },
        onSelectPlan = { viewModel.selectPlan(it) },
        onPurchase = { activity?.let { viewModel.purchase(it) } },
        onRestore = { viewModel.restorePurchases() },
        onRetry = { viewModel.loadOfferings() },
        onClearError = { viewModel.clearError() },
        promoSheetState = promoSheetState,
        onShowPromoSheet = { viewModel.showPromoSheet() },
        onHidePromoSheet = { viewModel.hidePromoSheet() },
        onPromoCodeChanged = { viewModel.setPromoCodeInput(it) },
        onRedeemPromoCode = { viewModel.redeemPromoCode() },
        closeRevealDelayMillis = 5_000L
    )
}
