package com.trackspeed.android.ui.screens.paywall

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.billing.PromoCodeType
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Currency

private val SuccessGreen = Color(0xFF30D158)
private val ErrorRed = Color(0xFFFF5252)
private val PaywallHeroBlue = Color(0xFF087BFF)
private val PaywallPageBackground = Color(0xFFF8F9FC)
private val PaywallCardBorder = Color.Black.copy(alpha = 0.10f)
private val PaywallTextDark = Color(0xFF090B10)
private val PaywallTextSecondary = Color.Black.copy(alpha = 0.64f)
private val PaywallStarGold = Color(0xFFF5C542)

private const val PRIVACY_URL = "https://mytrackspeed.com/privacy"
private const val TERMS_URL = "https://mytrackspeed.com/terms"

@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    preferDiscountPackage: Boolean = false,
    showPromoSheetOnLaunch: Boolean = false,
    viewModel: PaywallViewModel = hiltViewModel()
) {
    val selectedPlan by viewModel.selectedPlan.collectAsStateWithLifecycle()
    val purchaseState by viewModel.purchaseState.collectAsStateWithLifecycle()
    val isLoadingOfferings by viewModel.isLoadingOfferings.collectAsStateWithLifecycle()
    val offeringsError by viewModel.offeringsError.collectAsStateWithLifecycle()
    val isProUser by viewModel.isProUser.collectAsStateWithLifecycle()
    val promoSheetState by viewModel.promoSheetState.collectAsStateWithLifecycle()
    val effectivePreferDiscountPackage by viewModel.preferDiscountPackage.collectAsStateWithLifecycle()

    val activity = LocalContext.current as? Activity
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(preferDiscountPackage) {
        viewModel.setAnalyticsSource(
            if (preferDiscountPackage) PaywallViewModel.SOURCE_DISCOUNT else PaywallViewModel.SOURCE_PAYWALL
        )
    }
    LaunchedEffect(showPromoSheetOnLaunch) {
        if (showPromoSheetOnLaunch) viewModel.showPromoSheet()
    }
    LaunchedEffect(preferDiscountPackage, isLoadingOfferings) {
        if (!preferDiscountPackage) {
            viewModel.setPreferDiscountPackage(false)
        } else if (!isLoadingOfferings) {
            viewModel.preparePostOnboardingDiscountPaywall()
        }
    }
    LaunchedEffect(isLoadingOfferings, effectivePreferDiscountPackage) {
        if (!isLoadingOfferings) {
            viewModel.trackPaywallViewedIfNeeded()
        }
    }
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

    val weeklyPlan = viewModel.getWeeklyPlan()
    val yearlyPlan = viewModel.getYearlyPlan()
    val handleManualClose: () -> Unit = {
        coroutineScope.launch {
            viewModel.handleCloseTapped()
            onClose()
        }
    }

    TrackSpeedProPaywallContent(
        selectedPlan = selectedPlan,
        purchaseState = purchaseState,
        isLoadingOfferings = isLoadingOfferings,
        offeringsError = offeringsError,
        weeklyPlan = weeklyPlan,
        yearlyPlan = yearlyPlan,
        onClose = handleManualClose,
        onSelectPlan = { viewModel.selectPlan(it) },
        onPurchase = { activity?.let { viewModel.purchase(it) } },
        onRestore = { viewModel.restorePurchases() },
        onRetry = { viewModel.loadOfferings() },
        onClearError = { viewModel.clearError() },
        closeRevealDelayMillis = 2_000L,
        promoSheetState = promoSheetState,
        onShowPromoSheet = { viewModel.showPromoSheet() },
        onHidePromoSheet = { viewModel.hidePromoSheet() },
        onPromoCodeChanged = { viewModel.setPromoCodeInput(it) },
        onRedeemPromoCode = { viewModel.redeemPromoCode() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSpeedProPaywallContent(
    selectedPlan: PlanType,
    purchaseState: PurchaseState,
    isLoadingOfferings: Boolean,
    offeringsError: String?,
    weeklyPlan: PlanInfo,
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
    onRedeemPromoCode: () -> Unit = {},
    closeRevealDelayMillis: Long = 2_000L
) {
    var isCloseProminent by remember { mutableStateOf(false) }
    var expandedTestimonial by remember { mutableStateOf<PaywallTestimonial?>(null) }

    LaunchedEffect(Unit) {
        delay(closeRevealDelayMillis)
        isCloseProminent = true
    }

    val selectedPlanInfo = when (selectedPlan) {
        PlanType.YEARLY -> yearlyPlan
        PlanType.WEEKLY -> weeklyPlan
        PlanType.MONTHLY -> weeklyPlan
    }
    val hasSelectedPackage = selectedPlanInfo.rcPackage != null
    val primaryButtonText = when {
        !hasSelectedPackage && !isLoadingOfferings -> stringResource(R.string.paywall_load_pricing)
        !hasSelectedPackage -> stringResource(R.string.paywall_loading)
        selectedPlan == PlanType.YEARLY && yearlyPlan.freeTrialDays != null ->
            stringResource(R.string.paywall_try_for_free)
        else -> stringResource(R.string.common_continue)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaywallPageBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 240.dp)
                .verticalScroll(rememberScrollState())
        ) {
            PaywallHeroSection()

            PaywallSocialProofSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                onTestimonialClick = { expandedTestimonial = it }
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 10.dp, end = 16.dp)
                .size(44.dp)
                .alpha(if (isCloseProminent) 1f else 0.35f)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.paywall_close_cd),
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        StickyPurchasePanel(
            modifier = Modifier.align(Alignment.BottomCenter),
            selectedPlan = selectedPlan,
            weeklyPlan = weeklyPlan,
            yearlyPlan = yearlyPlan,
            purchaseState = purchaseState,
            isLoadingOfferings = isLoadingOfferings,
            offeringsError = offeringsError,
            primaryButtonText = primaryButtonText,
            billingSummary = selectedPlanInfo.billingSummary(),
            onSelectPlan = onSelectPlan,
            onPrimaryAction = {
                if (hasSelectedPackage) {
                    onPurchase()
                } else {
                    onRetry()
                }
            },
            onClearError = onClearError,
            onShowPromoSheet = onShowPromoSheet,
            onRestore = onRestore
        )
    }

    if (promoSheetState is PromoSheetState.Visible) {
        PromoCodeSheet(
            state = promoSheetState as PromoSheetState.Visible,
            onDismiss = onHidePromoSheet,
            onCodeChanged = onPromoCodeChanged,
            onRedeem = onRedeemPromoCode
        )
    }

    expandedTestimonial?.let { testimonial ->
        ModalBottomSheet(
            onDismissRequest = { expandedTestimonial = null },
            containerColor = PaywallPageBackground,
            dragHandle = null
        ) {
            TestimonialDetailSheet(testimonial)
        }
    }
}

private data class PaywallTestimonial(
    val initials: String,
    val photoResId: Int? = null,
    val name: String,
    @StringRes val ageRes: Int,
    @StringRes val roleRes: Int,
    @StringRes val previewQuoteRes: Int,
    @StringRes val quoteRes: Int
)

private val paywallTestimonials = listOf(
    PaywallTestimonial(
        initials = "SG",
        photoResId = R.drawable.sondre_profile,
        name = "Sondre Guttormsen",
        ageRes = R.string.paywall_testimonial_olympian,
        roleRes = R.string.paywall_testimonial_olympic_pole_vaulter,
        previewQuoteRes = R.string.paywall_testimonial_sondre_preview,
        quoteRes = R.string.paywall_testimonial_sondre_quote
    ),
    PaywallTestimonial(
        initials = "ER",
        name = "Ethan R.",
        ageRes = R.string.paywall_testimonial_sprinter,
        roleRes = R.string.paywall_testimonial_track_athlete,
        previewQuoteRes = R.string.paywall_testimonial_ethan_preview,
        quoteRes = R.string.paywall_testimonial_ethan_quote
    ),
    PaywallTestimonial(
        initials = "ML",
        name = "Marcus L.",
        ageRes = R.string.paywall_testimonial_jumper,
        roleRes = R.string.paywall_testimonial_horizontal_jumps,
        previewQuoteRes = R.string.paywall_testimonial_marcus_preview,
        quoteRes = R.string.paywall_testimonial_marcus_quote
    ),
    PaywallTestimonial(
        initials = "AV",
        name = "Ava V.",
        ageRes = R.string.paywall_testimonial_sprinter,
        roleRes = R.string.paywall_testimonial_sprinter,
        previewQuoteRes = R.string.paywall_testimonial_ava_preview,
        quoteRes = R.string.paywall_testimonial_ava_quote
    ),
    PaywallTestimonial(
        initials = "JL",
        name = "Jonas L.",
        ageRes = R.string.paywall_testimonial_coach,
        roleRes = R.string.paywall_testimonial_coach,
        previewQuoteRes = R.string.paywall_testimonial_jonas_preview,
        quoteRes = R.string.paywall_testimonial_jonas_quote
    )
)

@Composable
private fun PaywallHeroSection() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.paywall_hero),
            contentDescription = stringResource(R.string.settings_trackspeed_pro),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.78f),
                            Color.Black.copy(alpha = 0.24f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 24.dp)
                .padding(top = 86.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy((-2).dp)) {
                Text(
                    text = stringResource(R.string.paywall_hero_professional),
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 31.sp
                )
                Text(
                    text = stringResource(R.string.paywall_hero_timing_in),
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 31.sp
                )
                Text(
                    text = stringResource(R.string.paywall_hero_your_pocket),
                    color = PaywallHeroBlue,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 31.sp
                )
            }

            Text(
                text = stringResource(R.string.paywall_hero_subtitle),
                color = Color.White.copy(alpha = 0.86f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
                modifier = Modifier.width(190.dp)
            )
        }
    }
}

@Composable
private fun PaywallSocialProofSection(
    modifier: Modifier = Modifier,
    onTestimonialClick: (PaywallTestimonial) -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TrustedByOlympiansCard()

        paywallTestimonials.forEach { testimonial ->
            TestimonialCard(
                testimonial = testimonial,
                onClick = { onTestimonialClick(testimonial) }
            )
        }
    }
}

@Composable
private fun TrustedByOlympiansCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = Color.White,
        shadowElevation = 7.dp,
        border = BorderStroke(1.dp, PaywallCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.paywall_trusted_by_olympians),
                color = PaywallTextDark,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AvatarStack()

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(5) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = PaywallStarGold,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.paywall_social_proof),
                        color = Color.Black.copy(alpha = 0.68f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarStack() {
    Box(
        modifier = Modifier
            .width(122.dp)
            .height(48.dp)
    ) {
        paywallTestimonials.take(4).forEachIndexed { index, testimonial ->
            TestimonialAvatar(
                testimonial = testimonial,
                size = 44,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = (index * 26).dp)
            )
        }
    }
}

@Composable
private fun TestimonialCard(
    testimonial: PaywallTestimonial,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(26.dp),
        color = Color.White,
        shadowElevation = 7.dp,
        border = BorderStroke(1.dp, PaywallCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TestimonialAvatar(testimonial = testimonial, size = 46)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = testimonial.name,
                        color = PaywallTextDark,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(testimonial.roleRes),
                        color = Color.Black.copy(alpha = 0.58f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(5) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = PaywallStarGold,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            Text(
                text = "\"${stringResource(testimonial.previewQuoteRes)}\"",
                color = Color.Black.copy(alpha = 0.86f),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 23.sp,
                maxLines = 4
            )

            Text(
                text = stringResource(R.string.paywall_tap_to_read_more),
                color = PaywallHeroBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TestimonialAvatar(
    testimonial: PaywallTestimonial,
    size: Int,
    modifier: Modifier = Modifier
) {
    val avatarModifier = modifier
        .size(size.dp)
        .clip(CircleShape)
        .border(3.dp, Color.White, CircleShape)

    if (testimonial.photoResId != null) {
        Image(
            painter = painterResource(testimonial.photoResId),
            contentDescription = testimonial.name,
            modifier = avatarModifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = avatarModifier.background(
                Brush.linearGradient(
                    colors = listOf(
                        PaywallHeroBlue.copy(alpha = 0.36f),
                        Color(0xFF0E1A2A)
                    )
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = testimonial.initials,
                color = Color.White,
                fontSize = (size * 0.43f).sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun StickyPurchasePanel(
    modifier: Modifier,
    selectedPlan: PlanType,
    weeklyPlan: PlanInfo,
    yearlyPlan: PlanInfo,
    purchaseState: PurchaseState,
    isLoadingOfferings: Boolean,
    offeringsError: String?,
    primaryButtonText: String,
    billingSummary: String,
    onSelectPlan: (PlanType) -> Unit,
    onPrimaryAction: () -> Unit,
    onClearError: () -> Unit,
    onShowPromoSheet: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val selectedPlanInfo = when (selectedPlan) {
        PlanType.YEARLY -> yearlyPlan
        PlanType.WEEKLY -> weeklyPlan
        PlanType.MONTHLY -> weeklyPlan
    }
    val canTapPrimary = purchaseState !is PurchaseState.Loading &&
        (selectedPlanInfo.rcPackage != null || !isLoadingOfferings)

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.White)
                    )
                )
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            shadowElevation = 14.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(top = 18.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlanChoiceCard(
                    title = stringResource(R.string.paywall_yearly_plan),
                    detail = yearlyPlan.yearlyDetailText(offeringsError, isLoadingOfferings),
                    subtitle = yearlyPlan.yearlySubtitleText(offeringsError, isLoadingOfferings),
                    trailingPrice = yearlyPlan.yearlyTrailingPrice(offeringsError, isLoadingOfferings),
                    isMostPopular = true,
                    isSelected = selectedPlan == PlanType.YEARLY,
                    onClick = { onSelectPlan(PlanType.YEARLY) }
                )

                PlanChoiceCard(
                    title = stringResource(R.string.paywall_weekly),
                    detail = null,
                    subtitle = weeklyPlan.weeklySubtitleText(offeringsError, isLoadingOfferings),
                    trailingPrice = weeklyPlan.weeklyTrailingPrice(offeringsError, isLoadingOfferings),
                    isMostPopular = false,
                    isSelected = selectedPlan == PlanType.WEEKLY,
                    onClick = { onSelectPlan(PlanType.WEEKLY) }
                )

                if (purchaseState is PurchaseState.Error) {
                    Text(
                        text = purchaseState.message,
                        color = ErrorRed,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClearError() }
                    )
                } else if (offeringsError != null) {
                    Text(
                        text = offeringsError,
                        color = ErrorRed,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PaywallHeroBlue,
                        contentColor = Color.White,
                        disabledContainerColor = PaywallHeroBlue.copy(alpha = 0.38f),
                        disabledContentColor = Color.White.copy(alpha = 0.72f)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    enabled = canTapPrimary
                ) {
                    if (purchaseState is PurchaseState.Loading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Text(
                            text = primaryButtonText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(16.dp)
                ) {
                    Text(
                        text = billingSummary,
                        color = Color.Black.copy(alpha = 0.56f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = "\u2022",
                        color = Color.Black.copy(alpha = 0.56f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.paywall_cancel_anytime),
                        color = Color.Black.copy(alpha = 0.56f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PaywallLegalLink(stringResource(R.string.paywall_have_code), onClick = onShowPromoSheet)
                    PaywallLegalLink(stringResource(R.string.paywall_restore), onClick = onRestore)
                    PaywallLegalLink(stringResource(R.string.paywall_terms)) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL)))
                    }
                    PaywallLegalLink(stringResource(R.string.paywall_privacy)) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanChoiceCard(
    title: String,
    detail: String?,
    subtitle: String?,
    trailingPrice: String,
    isMostPopular: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) PaywallHeroBlue else PaywallCardBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .padding(top = if (isMostPopular) 5.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    color = PaywallTextDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        color = PaywallTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = if (isMostPopular) PaywallHeroBlue else PaywallTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = trailingPrice,
                color = PaywallTextDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
        }

        if (isMostPopular) {
            Text(
                text = stringResource(R.string.paywall_most_popular),
                color = Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 68.dp)
                    .background(PaywallHeroBlue, RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PaywallLegalLink(
    text: String,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = Color.Black.copy(alpha = 0.56f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
private fun TestimonialDetailSheet(testimonial: PaywallTestimonial) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        TestimonialAvatar(testimonial = testimonial, size = 58)

        Text(
            text = "\"${stringResource(testimonial.quoteRes)}\"",
            color = Color.Black.copy(alpha = 0.88f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = testimonial.name,
                color = PaywallTextDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(Color.Black.copy(alpha = 0.18f))
            )
            Text(
                text = stringResource(testimonial.ageRes),
                color = PaywallTextDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
        }

        Text(
            text = stringResource(testimonial.roleRes),
            color = Color.Black.copy(alpha = 0.62f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PlanInfo.yearlyDetailText(
    offeringsError: String?,
    isLoadingOfferings: Boolean
): String? {
    return when {
        rcPackage != null -> stringResource(R.string.paywall_price_per_year, priceDisplay)
        isLoadingOfferings -> null
        offeringsError != null -> null
        else -> null
    }
}

@Composable
private fun PlanInfo.yearlySubtitleText(
    offeringsError: String?,
    isLoadingOfferings: Boolean
): String {
    return when {
        rcPackage != null && freeTrialDays != null -> stringResource(
            R.string.paywall_free_trial_days,
            freeTrialDays
        )
        rcPackage != null -> stringResource(R.string.paywall_billed_annually)
        isLoadingOfferings -> stringResource(R.string.paywall_pricing_loading)
        offeringsError != null -> stringResource(R.string.paywall_price_unavailable)
        else -> stringResource(R.string.paywall_price_unavailable)
    }
}

@Composable
private fun PlanInfo.yearlyTrailingPrice(
    offeringsError: String?,
    isLoadingOfferings: Boolean
): String {
    return when {
        rcPackage != null -> yearlyWeeklyPriceDisplay()
        isLoadingOfferings -> stringResource(R.string.paywall_loading)
        offeringsError != null -> stringResource(R.string.paywall_retry)
        else -> stringResource(R.string.paywall_retry)
    }
}

@Composable
private fun PlanInfo.weeklySubtitleText(
    offeringsError: String?,
    isLoadingOfferings: Boolean
): String? {
    return when {
        rcPackage != null -> null
        isLoadingOfferings -> stringResource(R.string.paywall_pricing_loading)
        offeringsError != null -> stringResource(R.string.paywall_price_unavailable)
        else -> stringResource(R.string.paywall_price_unavailable)
    }
}

@Composable
private fun PlanInfo.weeklyTrailingPrice(
    offeringsError: String?,
    isLoadingOfferings: Boolean
): String {
    return when {
        rcPackage != null -> stringResource(R.string.paywall_price_per_week, priceDisplay)
        isLoadingOfferings -> stringResource(R.string.paywall_loading)
        offeringsError != null -> stringResource(R.string.paywall_retry)
        else -> stringResource(R.string.paywall_retry)
    }
}

@Composable
private fun PlanInfo.billingSummary(): String {
    val period = when (type) {
        PlanType.YEARLY -> stringResource(R.string.paywall_period_year)
        PlanType.WEEKLY -> stringResource(R.string.paywall_period_week)
        PlanType.MONTHLY -> stringResource(R.string.paywall_period_month)
    }
    return stringResource(R.string.paywall_billed_summary, priceDisplay, period)
}

@Composable
private fun PlanInfo.yearlyWeeklyPriceDisplay(): String {
    val price = rcPackage?.product?.price
        ?: return stringResource(R.string.paywall_price_per_week, "\$0.96")
    val weekly = price.amountMicros / 52.0 / 1_000_000.0
    val formatter = NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance(price.currencyCode)
    }
    return stringResource(R.string.paywall_price_per_week, formatter.format(weekly))
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
                text = stringResource(R.string.settings_trackspeed_pro),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.paywall_other_plans_title),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Yearly plan card
            AllPlansPlanCard(
                title = stringResource(R.string.paywall_yearly),
                subtitle = stringResource(
                    R.string.paywall_billed_per_year,
                    yearlyPlan.priceDisplay
                ),
                trailingPrice = yearlyPlan.monthlyEquivalent ?: yearlyPlan.priceDisplay,
                trailingLabel = stringResource(R.string.paywall_per_month),
                isSelected = selectedPlan == PlanType.YEARLY,
                onClick = { onSelectPlan(PlanType.YEARLY) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Monthly plan card
            AllPlansPlanCard(
                title = stringResource(R.string.paywall_monthly),
                subtitle = null,
                trailingPrice = monthlyPlan.priceDisplay,
                trailingLabel = stringResource(R.string.paywall_per_month),
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
                    text = stringResource(R.string.paywall_no_commitment),
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
                        text = stringResource(R.string.onboarding_welcome_get_started),
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
                text = stringResource(R.string.paywall_not_now),
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
                    text = stringResource(R.string.paywall_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
                    }
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.paywall_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable { onRestore() }
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.paywall_terms),
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
                contentDescription = stringResource(R.string.paywall_selected_cd),
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
                text = stringResource(R.string.paywall_enter_promo_code),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.paywall_enter_promo_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = state.code,
                onValueChange = { onCodeChanged(it.uppercase()) },
                placeholder = {
                    Text(stringResource(R.string.paywall_promo_placeholder), color = TextMuted)
                },
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
                        Text(
                            stringResource(R.string.paywall_verifying_code),
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
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
                            text = when (state.result.type) {
                                PromoCodeType.FREE -> stringResource(R.string.paywall_pro_activated)
                                PromoCodeType.TRIAL -> stringResource(R.string.paywall_offer_code_accepted)
                                PromoCodeType.DISCOUNT -> stringResource(
                                    R.string.paywall_discount_unlocked_loading
                                )
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
                        text = stringResource(R.string.paywall_apply_code),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.common_cancel),
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
        TrackSpeedProPaywallContent(
            selectedPlan = PlanType.YEARLY,
            purchaseState = PurchaseState.Idle,
            isLoadingOfferings = false,
            offeringsError = null,
            weeklyPlan = PlanInfo(
                type = PlanType.WEEKLY,
                priceDisplay = "$7.99",
                periodDisplay = "week"
            ),
            yearlyPlan = PlanInfo(
                type = PlanType.YEARLY,
                priceDisplay = "$59.99",
                periodDisplay = "year",
                monthlyEquivalent = "$5.00",
                savingsPercent = 86
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
