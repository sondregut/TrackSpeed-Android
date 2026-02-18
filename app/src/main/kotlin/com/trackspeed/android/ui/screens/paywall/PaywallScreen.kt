package com.trackspeed.android.ui.screens.paywall

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.billing.ProFeature
import com.trackspeed.android.ui.theme.TrackSpeedTheme

private val AccentGreen = Color(0xFF00E676)
private val CardBackground = Color(0xFF2C2C2E)
private val CardSelectedBorder = Color(0xFF00E676)
private val TextSecondary = Color(0xFF8E8E93)
private val TextTertiary = Color(0xFF636366)
private val BadgeBackground = Color(0xFF00E676)
private val BadgeText = Color.Black
private val ErrorRed = Color(0xFFFF5252)

@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    viewModel: PaywallViewModel = hiltViewModel()
) {
    val selectedPlan by viewModel.selectedPlan.collectAsStateWithLifecycle()
    val purchaseState by viewModel.purchaseState.collectAsStateWithLifecycle()
    val isLoadingOfferings by viewModel.isLoadingOfferings.collectAsStateWithLifecycle()
    val offeringsError by viewModel.offeringsError.collectAsStateWithLifecycle()
    val isProUser by viewModel.isProUser.collectAsStateWithLifecycle()

    val activity = LocalContext.current as? Activity

    // Close on successful purchase or if already pro
    LaunchedEffect(purchaseState) {
        if (purchaseState is PurchaseState.Success) {
            onClose()
        }
    }
    LaunchedEffect(isProUser) {
        if (isProUser) {
            onClose()
        }
    }

    val monthlyPlan = viewModel.getMonthlyPlan()
    val yearlyPlan = viewModel.getYearlyPlan()

    PaywallContent(
        selectedPlan = selectedPlan,
        purchaseState = purchaseState,
        isLoadingOfferings = isLoadingOfferings,
        offeringsError = offeringsError,
        monthlyPlan = monthlyPlan,
        yearlyPlan = yearlyPlan,
        onClose = onClose,
        onSelectPlan = { viewModel.selectPlan(it) },
        onPurchase = { activity?.let { viewModel.purchase(it) } },
        onRestore = { viewModel.restorePurchases() },
        onRetry = { viewModel.loadOfferings() },
        onClearError = { viewModel.clearError() }
    )
}

@Composable
private fun PaywallContent(
    selectedPlan: PlanType,
    purchaseState: PurchaseState,
    isLoadingOfferings: Boolean,
    offeringsError: String?,
    monthlyPlan: PlanInfo,
    yearlyPlan: PlanInfo,
    onClose: () -> Unit,
    onSelectPlan: (PlanType) -> Unit,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onRetry: () -> Unit,
    onClearError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
    ) {
        // Top bar with close button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, end = 8.dp)
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Hero area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // App icon placeholder
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AccentGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "TS",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = AccentGreen
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "TrackSpeed Pro",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                ),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Unlock your full potential",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Feature list
            ProFeature.entries.forEach { feature ->
                FeatureRow(
                    title = feature.displayName,
                    description = feature.description
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Plan selector or loading/error states
            if (isLoadingOfferings) {
                CircularProgressIndicator(
                    color = AccentGreen,
                    modifier = Modifier
                        .padding(vertical = 32.dp)
                        .size(40.dp)
                )
            } else if (offeringsError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Unable to load subscription options",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ErrorRed,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardBackground,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Retry")
                    }
                }
            } else {
                // Plan cards
                PlanCard(
                    planName = "Yearly",
                    price = yearlyPlan.priceDisplay,
                    period = "/ ${yearlyPlan.periodDisplay}",
                    badge = "BEST VALUE",
                    subtitle = "${yearlyPlan.monthlyEquivalent ?: ""} - Save ${yearlyPlan.savingsPercent ?: 0}%",
                    isSelected = selectedPlan == PlanType.YEARLY,
                    onClick = { onSelectPlan(PlanType.YEARLY) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                PlanCard(
                    planName = "Monthly",
                    price = monthlyPlan.priceDisplay,
                    period = "/ ${monthlyPlan.periodDisplay}",
                    badge = null,
                    subtitle = null,
                    isSelected = selectedPlan == PlanType.MONTHLY,
                    onClick = { onSelectPlan(PlanType.MONTHLY) }
                )

                Spacer(modifier = Modifier.height(28.dp))

                // CTA button
                Button(
                    onClick = onPurchase,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        contentColor = Color.Black,
                        disabledContainerColor = AccentGreen.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    enabled = purchaseState !is PurchaseState.Loading
                ) {
                    if (purchaseState is PurchaseState.Loading) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Text(
                            text = "Subscribe",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        )
                    }
                }

                // Error message
                if (purchaseState is PurchaseState.Error) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = (purchaseState as PurchaseState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClearError() }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "No commitment, cancel anytime",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Footer links
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Restore Purchases",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline
                        ),
                        color = TextSecondary,
                        modifier = Modifier.clickable { onRestore() }
                    )
                    Text(
                        text = "  |  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                    Text(
                        text = "Terms",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline
                        ),
                        color = TextSecondary,
                        modifier = Modifier.clickable { /* Open terms URL */ }
                    )
                    Text(
                        text = "  |  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                    Text(
                        text = "Privacy",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline
                        ),
                        color = TextSecondary,
                        modifier = Modifier.clickable { /* Open privacy URL */ }
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun FeatureRow(
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = Color.White
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun PlanCard(
    planName: String,
    price: String,
    period: String,
    badge: String?,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardBackground
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, CardSelectedBorder)
        } else {
            BorderStroke(1.dp, Color(0xFF48484A))
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Badge
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = BadgeText,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BadgeBackground)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = planName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = price,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = period,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentGreen
                    )
                }
            }

            // Selection indicator
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) AccentGreen else Color.Transparent
                    )
                    .then(
                        if (!isSelected) {
                            Modifier
                                .clip(CircleShape)
                                .background(Color.Transparent)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF48484A))
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PaywallScreenPreview() {
    TrackSpeedTheme(darkTheme = true) {
        PaywallContent(
            selectedPlan = PlanType.YEARLY,
            purchaseState = PurchaseState.Idle,
            isLoadingOfferings = false,
            offeringsError = null,
            monthlyPlan = PlanInfo(
                type = PlanType.MONTHLY,
                priceDisplay = "$8.99",
                periodDisplay = "month"
            ),
            yearlyPlan = PlanInfo(
                type = PlanType.YEARLY,
                priceDisplay = "$49.99",
                periodDisplay = "year",
                monthlyEquivalent = "$4.17/mo",
                savingsPercent = 54
            ),
            onClose = {},
            onSelectPlan = {},
            onPurchase = {},
            onRestore = {},
            onRetry = {},
            onClearError = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PaywallScreenLoadingPreview() {
    TrackSpeedTheme(darkTheme = true) {
        PaywallContent(
            selectedPlan = PlanType.YEARLY,
            purchaseState = PurchaseState.Idle,
            isLoadingOfferings = true,
            offeringsError = null,
            monthlyPlan = PlanInfo(
                type = PlanType.MONTHLY,
                priceDisplay = "$8.99",
                periodDisplay = "month"
            ),
            yearlyPlan = PlanInfo(
                type = PlanType.YEARLY,
                priceDisplay = "$49.99",
                periodDisplay = "year"
            ),
            onClose = {},
            onSelectPlan = {},
            onPurchase = {},
            onRestore = {},
            onRetry = {},
            onClearError = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PaywallScreenErrorPreview() {
    TrackSpeedTheme(darkTheme = true) {
        PaywallContent(
            selectedPlan = PlanType.YEARLY,
            purchaseState = PurchaseState.Idle,
            isLoadingOfferings = false,
            offeringsError = "Network error",
            monthlyPlan = PlanInfo(
                type = PlanType.MONTHLY,
                priceDisplay = "$8.99",
                periodDisplay = "month"
            ),
            yearlyPlan = PlanInfo(
                type = PlanType.YEARLY,
                priceDisplay = "$49.99",
                periodDisplay = "year"
            ),
            onClose = {},
            onSelectPlan = {},
            onPurchase = {},
            onRestore = {},
            onRetry = {},
            onClearError = {}
        )
    }
}
