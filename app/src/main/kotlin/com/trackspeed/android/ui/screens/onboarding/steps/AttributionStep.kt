package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

@Composable
fun AttributionStep(
    onAttributionSelected: (String) -> Unit,
    promoCode: String,
    onPromoCodeChanged: (String) -> Unit,
    onContinue: () -> Unit
) {
    var selected by remember { mutableStateOf<String?>(null) }

    val attributionOptions = listOf(
        stringResource(R.string.onboarding_attribution_instagram),
        stringResource(R.string.onboarding_attribution_tiktok),
        stringResource(R.string.onboarding_attribution_youtube),
        stringResource(R.string.onboarding_attribution_google),
        stringResource(R.string.onboarding_attribution_friend),
        stringResource(R.string.onboarding_attribution_appstore),
        stringResource(R.string.onboarding_attribution_trackclub),
        stringResource(R.string.onboarding_attribution_other)
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.onboarding_attribution_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            items(attributionOptions) { option ->
                OutlinedButton(
                    onClick = { selected = option; onAttributionSelected(option) },
                    modifier = Modifier.height(44.dp),
                    border = BorderStroke(1.dp, if (selected == option) AccentNavy else BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected == option) AccentNavy.copy(alpha = 0.15f) else Color.Transparent
                    )
                ) {
                    Text(option, color = if (selected == option) AccentNavy else TextPrimary, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = promoCode,
            onValueChange = onPromoCodeChanged,
            label = { Text(stringResource(R.string.onboarding_attribution_promo_label)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentNavy, unfocusedBorderColor = BorderSubtle,
                focusedLabelColor = AccentNavy, unfocusedLabelColor = TextSecondary
            ),
            singleLine = true
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }
}
