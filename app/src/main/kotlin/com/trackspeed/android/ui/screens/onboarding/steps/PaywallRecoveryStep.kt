package com.trackspeed.android.ui.screens.onboarding.steps

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.paywall.PaywallViewModel
import com.trackspeed.android.ui.screens.paywall.PurchaseState
import com.trackspeed.android.ui.theme.*

@Composable
fun PaywallRecoveryStep(
    onClaim: () -> Unit,
    onFullPrice: () -> Unit,
    viewModel: PaywallViewModel = hiltViewModel()
) {
    val purchaseState by viewModel.purchaseState.collectAsStateWithLifecycle()
    val isLoadingOfferings by viewModel.isLoadingOfferings.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    val discountPlan = viewModel.getDiscountYearlyPlan()
    val standardPlan = viewModel.getStandardYearlyPlan()

    LaunchedEffect(Unit) {
        viewModel.setAnalyticsSource(PaywallViewModel.SOURCE_ONBOARDING_RECOVERY)
        viewModel.setPreferDiscountPackage(true)
        viewModel.trackDiscountPaywallShownIfNeeded()
    }

    LaunchedEffect(isLoadingOfferings) {
        if (!isLoadingOfferings && !viewModel.hasRealDiscountPackage()) {
            viewModel.trackDiscountPaywallDismissed()
            onFullPrice()
        }
    }

    LaunchedEffect(purchaseState) {
        if (purchaseState is PurchaseState.Success) {
            onClaim()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(120.dp)
                .background(AccentBlue.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.LocalOffer,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_paywall_recovery_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_paywall_recovery_subtitle),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AccentBlue,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBackground, RoundedCornerShape(24.dp))
                .padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (standardPlan.rcPackage != null) {
                        standardPlan.priceDisplay
                    } else {
                        stringResource(R.string.paywall_price_unavailable)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.LineThrough),
                    color = TextMuted
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (discountPlan.rcPackage != null) {
                        discountPlan.priceDisplay
                    } else {
                        stringResource(R.string.paywall_price_unavailable)
                    },
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue
                )
            }
            Text(
                text = stringResource(R.string.onboarding_paywall_recovery_per_year),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = discountPlan.freeTrialDays?.let { days ->
                    stringResource(R.string.paywall_includes_free_trial_days, days)
                } ?: stringResource(R.string.paywall_cancel_anytime),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
        }

        if (purchaseState is PurchaseState.Error) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = (purchaseState as PurchaseState.Error).message,
                style = MaterialTheme.typography.bodySmall,
                color = TimerRed,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { viewModel.purchase(activity) },
            enabled = purchaseState !is PurchaseState.Loading &&
                !isLoadingOfferings &&
                discountPlan.rcPackage != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue,
                contentColor = Color.White
            )
        ) {
            if (purchaseState is PurchaseState.Loading || isLoadingOfferings) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.onboarding_paywall_recovery_claim),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = {
                viewModel.trackDiscountPaywallDismissed()
                onFullPrice()
            },
            enabled = purchaseState !is PurchaseState.Loading
        ) {
            Text(
                text = stringResource(R.string.onboarding_paywall_recovery_full_price),
                color = TextSecondary,
                textDecoration = TextDecoration.Underline
            )
        }

        Text(
            text = if (discountPlan.rcPackage != null) {
                stringResource(
                    R.string.onboarding_paywall_recovery_billing,
                    discountPlan.priceDisplay
                )
            } else {
                stringResource(R.string.paywall_price_unavailable)
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))
    }
}
