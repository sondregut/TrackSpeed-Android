package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

private data class AttributionSource(
    val value: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun AttributionStep(
    onAttributionSelected: (String) -> Unit,
    promoCode: String,
    onPromoCodeChanged: (String) -> Unit,
    onSubmitPromoCode: (String, String) -> Unit,
    isLoading: Boolean = false,
    onContinue: () -> Unit
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var isAffiliateExpanded by remember { mutableStateOf(false) }

    val attributionSources = listOf(
        AttributionSource("instagram", stringResource(R.string.onboarding_attribution_instagram), Icons.Default.CameraAlt),
        AttributionSource("facebook", stringResource(R.string.onboarding_attribution_facebook), Icons.Default.Person),
        AttributionSource("tiktok", stringResource(R.string.onboarding_attribution_tiktok), Icons.Default.MusicNote),
        AttributionSource("x", stringResource(R.string.onboarding_attribution_x), Icons.Default.AlternateEmail),
        AttributionSource("reddit", stringResource(R.string.onboarding_attribution_reddit), Icons.Default.Forum),
        AttributionSource("youtube", stringResource(R.string.onboarding_attribution_youtube), Icons.Default.PlayArrow),
        AttributionSource("google", stringResource(R.string.onboarding_attribution_google), Icons.Default.Search),
        AttributionSource("google_play", stringResource(R.string.onboarding_attribution_appstore), Icons.Default.Shop),
        AttributionSource("website", stringResource(R.string.onboarding_attribution_website), Icons.Default.Language),
        AttributionSource("friend_family", stringResource(R.string.onboarding_attribution_friend_family), Icons.Default.People),
        AttributionSource("other", stringResource(R.string.onboarding_attribution_other), Icons.Default.MoreHoriz)
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title at top
        Text(
            stringResource(R.string.onboarding_attribution_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp, start = 32.dp, end = 32.dp)
        )

        Spacer(Modifier.height(16.dp))

        // Scrollable source list
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Affiliate code row (expandable)
            AffiliateCodeRow(
                isExpanded = isAffiliateExpanded,
                code = promoCode,
                onCodeChanged = onPromoCodeChanged,
                onToggle = {
                    isAffiliateExpanded = !isAffiliateExpanded
                    if (isAffiliateExpanded) selected = null
                }
            )

            // Attribution source rows
            attributionSources.forEach { source ->
                AttributionSourceRow(
                    source = source,
                    isSelected = selected == source.value,
                    onClick = {
                        selected = source.value
                        isAffiliateExpanded = false
                        onAttributionSelected(source.value)
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Continue button
        Button(
            onClick = { onContinue() },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp
                )
            } else {
                Text(stringResource(R.string.common_continue), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AffiliateCodeRow(
    isExpanded: Boolean,
    code: String,
    onCodeChanged: (String) -> Unit,
    onToggle: () -> Unit
) {
    val isActive = isExpanded || code.isNotBlank()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) AccentBlue.copy(alpha = 0.1f) else SurfaceDark,
        border = BorderStroke(1.dp, if (isActive) AccentBlue else BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (isActive) AccentBlue else TextSecondary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_attribution_affiliate), fontSize = 16.sp, color = TextPrimary)
                    Text(stringResource(R.string.onboarding_attribution_affiliate_body), fontSize = 12.sp, color = TextSecondary)
                }
                if (code.isNotBlank()) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = AccentBlue
                    )
                } else {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = TextSecondary
                    )
                }
            }

            if (isExpanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = BorderSubtle
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChanged,
                    placeholder = { Text(stringResource(R.string.onboarding_attribution_affiliate_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextSecondary
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun AttributionSourceRow(
    source: AttributionSource,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) AccentBlue.copy(alpha = 0.1f) else SurfaceDark,
        border = BorderStroke(1.dp, if (isSelected) AccentBlue else BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                source.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isSelected) AccentBlue else TextSecondary
            )
            Text(
                source.label,
                fontSize = 16.sp,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = AccentBlue
                )
            }
        }
    }
}
