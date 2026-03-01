package com.trackspeed.android.ui.screens.onboarding.steps

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.paywall.PaywallViewModel
import com.trackspeed.android.ui.screens.paywall.PurchaseState
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun PaywallStep(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    viewModel: PaywallViewModel = hiltViewModel()
) {
    val purchaseState by viewModel.purchaseState.collectAsStateWithLifecycle()
    val isLoadingOfferings by viewModel.isLoadingOfferings.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity

    var canSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(3000); canSkip = true }

    // Navigate on purchase success
    LaunchedEffect(purchaseState) {
        if (purchaseState is PurchaseState.Success) onContinue()
    }

    val yearlyPlan = viewModel.getYearlyPlan()

    // Capture @Composable colors
    val accentColor = AccentBlue
    val textPrimaryColor = TextPrimary
    val textSecondaryColor = TextSecondary
    val textMutedColor = TextMuted

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Phone mockup image – takes available space above content
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.onboarding_photofinish_edit),
                contentDescription = "Photo Finish mode",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp),
                contentScale = ContentScale.Fit
            )

            // Bottom content block
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title: "Ready to get faster?"
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = textPrimaryColor)) { append("Ready to get ") }
                        withStyle(SpanStyle(color = accentColor)) { append("faster") }
                        withStyle(SpanStyle(color = textPrimaryColor)) { append("?") }
                    },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                // "No Payment Due Now" badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "No Payment Due Now",
                        fontSize = 14.sp,
                        color = textSecondaryColor
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Get Started button
                Button(
                    onClick = { viewModel.purchase(activity) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    enabled = purchaseState !is PurchaseState.Loading
                ) {
                    if (purchaseState is PurchaseState.Loading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            "Get Started",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                // Price info (shown once offerings are loaded)
                if (!isLoadingOfferings) {
                    val monthlyEq = yearlyPlan.monthlyEquivalent
                    Text(
                        text = if (monthlyEq != null)
                            "Just ${yearlyPlan.priceDisplay} per year ($monthlyEq/mo)"
                        else
                            "Just ${yearlyPlan.priceDisplay} per year",
                        fontSize = 14.sp,
                        color = textSecondaryColor,
                        textAlign = TextAlign.Center
                    )
                }

                // Error message
                if (purchaseState is PurchaseState.Error) {
                    Text(
                        text = (purchaseState as PurchaseState.Error).message,
                        fontSize = 13.sp,
                        color = TimerRed,
                        textAlign = TextAlign.Center
                    )
                }

                // Footer: Restore · Privacy · Terms
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(
                        onClick = { viewModel.restorePurchases() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Restore", fontSize = 13.sp, color = textMutedColor)
                    }
                    Text("·", fontSize = 13.sp, color = textMutedColor)
                    TextButton(
                        onClick = {},
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Privacy", fontSize = 13.sp, color = textMutedColor)
                    }
                    Text("·", fontSize = 13.sp, color = textMutedColor)
                    TextButton(
                        onClick = {},
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Terms", fontSize = 13.sp, color = textMutedColor)
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }

        // Skip button — X circle, top-right, appears after 3 seconds
        if (canSkip) {
            IconButton(
                onClick = onSkip,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Skip",
                    tint = textSecondaryColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
