package com.trackspeed.android.ui.screens.paywall

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.billing.ProFeature
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.delay

private val SuccessGreen = Color(0xFF30D158)
private val ErrorRed = Color(0xFFFF5252)

private const val PRIVACY_URL = "https://mytrackspeed.com/privacy"
private const val TERMS_URL = "https://mytrackspeed.com/terms"

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
    val promoSheetState by viewModel.promoSheetState.collectAsStateWithLifecycle()

    val activity = LocalContext.current as? Activity

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
        onClearError = { viewModel.clearError() },
        promoSheetState = promoSheetState,
        onShowPromoSheet = { viewModel.showPromoSheet() },
        onHidePromoSheet = { viewModel.hidePromoSheet() },
        onPromoCodeChanged = { viewModel.setPromoCodeInput(it) },
        onRedeemPromoCode = { viewModel.redeemPromoCode() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onClearError: () -> Unit,
    promoSheetState: PromoSheetState = PromoSheetState.Hidden,
    onShowPromoSheet: () -> Unit = {},
    onHidePromoSheet: () -> Unit = {},
    onPromoCodeChanged: (String) -> Unit = {},
    onRedeemPromoCode: () -> Unit = {}
) {
    val context = LocalContext.current
    var showCloseButton by remember { mutableStateOf(false) }
    var showAllPlansSheet by remember { mutableStateOf(false) }
    // Show close button after 2-second delay
    LaunchedEffect(Unit) {
        delay(2000)
        showCloseButton = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 200.dp) // space for fixed bottom section
                .verticalScroll(rememberScrollState())
        ) {
            // Hero image with gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.paywall_hero),
                    contentDescription = "TrackSpeed Pro",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Gradient overlay fading into background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    BackgroundGradientTop.copy(alpha = 0.8f),
                                    BackgroundGradientTop
                                )
                            )
                        )
                )

                // Close button (top-right, appears after 2s)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showCloseButton,
                    enter = fadeIn(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 48.dp, end = 16.dp)
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(BackgroundDark.copy(alpha = 0.4f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Title at bottom of hero
                Text(
                    text = "TrackSpeed Pro",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp
                    ),
                    color = TextPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )
            }

            // Legal links below title
            LegalLinksSection(
                onRestore = onRestore,
                onHaveCode = onShowPromoSheet,
                modifier = Modifier.padding(top = 8.dp)
            )

            // Features section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 40.dp)
            ) {
                ProFeature.entries.forEach { feature ->
                    FeatureRow(
                        title = feature.displayName,
                        description = feature.description
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        // Fixed bottom section
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // Gradient fade above fixed section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, BackgroundGradientBottom)
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BackgroundGradientBottom)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Error message
                if (purchaseState is PurchaseState.Error) {
                    Text(
                        text = (purchaseState as PurchaseState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClearError() }
                            .padding(bottom = 8.dp)
                    )
                }

                // No commitment badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "No commitment, cancel anytime",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Get Started button (yearly, with chevron)
                Button(
                    onClick = {
                        onSelectPlan(PlanType.YEARLY)
                        onPurchase()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = Color.White,
                        disabledContainerColor = AccentBlue.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    enabled = purchaseState !is PurchaseState.Loading && !isLoadingOfferings
                ) {
                    if (purchaseState is PurchaseState.Loading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "Get Started",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "\u203A", // right angle bracket
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 20.sp
                                ),
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Billing info
                Text(
                    text = "billed ${yearlyPlan.priceDisplay} per year",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // See all plans link
                Text(
                    text = "See all plans",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable { showAllPlansSheet = true }
                )
            }
        }
    }

    // All Plans bottom sheet
    if (showAllPlansSheet) {
        AllPlansSheet(
            monthlyPlan = monthlyPlan,
            yearlyPlan = yearlyPlan,
            selectedPlan = selectedPlan,
            purchaseState = purchaseState,
            onSelectPlan = onSelectPlan,
            onPurchase = {
                showAllPlansSheet = false
                onPurchase()
            },
            onDismiss = { showAllPlansSheet = false },
            onRestore = onRestore
        )
    }

    // Promo code bottom sheet
    if (promoSheetState is PromoSheetState.Visible) {
        PromoCodeSheet(
            state = promoSheetState as PromoSheetState.Visible,
            onDismiss = onHidePromoSheet,
            onCodeChanged = onPromoCodeChanged,
            onRedeem = onRedeemPromoCode
        )
    }
}

@Composable
private fun LegalLinksSection(
    onRestore: () -> Unit,
    onHaveCode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Privacy",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
            }
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "Restore",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.clickable { onRestore() }
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "Have a code?",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.clickable { onHaveCode() }
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "Terms",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL)))
            }
        )
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
            tint = AccentBlue,
            modifier = Modifier.size(28.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = TextPrimary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

// ---------- All Plans Bottom Sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllPlansSheet(
    monthlyPlan: PlanInfo,
    yearlyPlan: PlanInfo,
    selectedPlan: PlanType,
    purchaseState: PurchaseState,
    onSelectPlan: (PlanType) -> Unit,
    onPurchase: () -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextMuted)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header
            Text(
                text = "TrackSpeed Pro",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Not ready to commit for a year?\nWe have plans for everyone.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Yearly plan card
            AllPlansPlanCard(
                title = "Yearly",
                subtitle = "billed ${yearlyPlan.priceDisplay} per year",
                trailingPrice = yearlyPlan.monthlyEquivalent ?: yearlyPlan.priceDisplay,
                trailingLabel = "per month",
                isSelected = selectedPlan == PlanType.YEARLY,
                onClick = { onSelectPlan(PlanType.YEARLY) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Monthly plan card
            AllPlansPlanCard(
                title = "Monthly",
                subtitle = null,
                trailingPrice = monthlyPlan.priceDisplay,
                trailingLabel = "per month",
                isSelected = selectedPlan == PlanType.MONTHLY,
                onClick = { onSelectPlan(PlanType.MONTHLY) }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // No commitment badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "No commitment, cancel anytime",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Get Started button
            Button(
                onClick = onPurchase,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color.White,
                    disabledContainerColor = AccentBlue.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(28.dp),
                enabled = purchaseState !is PurchaseState.Loading
            ) {
                if (purchaseState is PurchaseState.Loading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = "Get Started",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Not now
            Text(
                text = "Not now, thanks",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.clickable { onDismiss() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Legal links
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Privacy",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
                    }
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Restore",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable { onRestore() }
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Terms",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL)))
                    }
                )
            }
        }
    }
}

@Composable
private fun AllPlansPlanCard(
    title: String,
    subtitle: String?,
    trailingPrice: String,
    trailingLabel: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBackground)
            .then(
                if (isSelected) {
                    Modifier.border(
                        border = BorderStroke(2.dp, AccentBlue),
                        shape = RoundedCornerShape(20.dp)
                    )
                } else Modifier
            )
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection indicator
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = AccentBlue,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .border(1.5.dp, TextMuted, CircleShape)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Plan name + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = TextPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Price
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = trailingPrice,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )
            Text(
                text = trailingLabel,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

// ---------- Promo Code Bottom Sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromoCodeSheet(
    state: PromoSheetState.Visible,
    onDismiss: () -> Unit,
    onCodeChanged: (String) -> Unit,
    onRedeem: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextMuted)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Enter Promo Code",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Enter your promo or influencer code below",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = state.code,
                onValueChange = { onCodeChanged(it.uppercase()) },
                placeholder = { Text("PROMO CODE", color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = when {
                        state.error != null -> ErrorRed
                        state.result != null -> SuccessGreen
                        else -> AccentBlue
                    },
                    unfocusedBorderColor = when {
                        state.error != null -> ErrorRed
                        state.result != null -> SuccessGreen
                        else -> TextMuted
                    },
                    focusedContainerColor = CardBackground,
                    unfocusedContainerColor = CardBackground,
                    cursorColor = AccentBlue
                ),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                enabled = !state.isLoading && state.result == null
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Status messages
            when {
                state.isLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = AccentBlue,
                            strokeWidth = 2.dp
                        )
                        Text("Verifying code...", fontSize = 14.sp, color = TextSecondary)
                    }
                }
                state.result != null -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = SuccessGreen
                        )
                        Text(
                            text = if (state.result.type == "free") {
                                "Pro Activated!"
                            } else {
                                "30-day trial unlocked! Tap Get Started"
                            },
                            fontSize = 14.sp,
                            color = SuccessGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                state.error != null -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = ErrorRed
                        )
                        Text(
                            text = state.error,
                            fontSize = 14.sp,
                            color = ErrorRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onRedeem,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.result != null) SuccessGreen else AccentBlue,
                    contentColor = Color.White,
                    disabledContainerColor = AccentBlue.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(26.dp),
                enabled = state.code.isNotBlank() && !state.isLoading && state.result == null
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = "Apply Code",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Cancel",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.clickable { onDismiss() }
            )
        }
    }
}

// ---------- Previews ----------

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PaywallScreenPreview() {
    TrackSpeedTheme() {
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
                monthlyEquivalent = "$4.17",
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
