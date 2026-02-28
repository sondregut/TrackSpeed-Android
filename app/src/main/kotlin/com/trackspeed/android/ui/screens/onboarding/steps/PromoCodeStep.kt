package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R

private val AccentBlue = Color(0xFF0A84FF)
private val SuccessGreen = Color(0xFF30D158)
private val SurfaceColor = Color(0xFF1C1C1E)

@Composable
fun PromoCodeStep(
    promoCode: String,
    onPromoCodeChanged: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    var codeApplied by remember { mutableStateOf(false) }

    // Reset applied state when code is cleared
    LaunchedEffect(promoCode) {
        if (promoCode.isEmpty() && codeApplied) {
            codeApplied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Title section
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.onboarding_promo_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onboarding_promo_subtitle),
                fontSize = 17.sp,
                color = Color(0xFFAEAEB2)
            )
        }

        Spacer(Modifier.weight(1f))

        // Text field card
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = stringResource(R.string.onboarding_promo_field_label),
                    fontSize = 12.sp,
                    color = Color(0xFF8E8E93),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = promoCode,
                    onValueChange = { onPromoCodeChanged(it.uppercase()) },
                    placeholder = { Text(stringResource(R.string.onboarding_promo_field_placeholder), color = Color(0xFF48484A)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = if (isFocused) AccentBlue else Color(0xFF3A3A3C),
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        focusedContainerColor = SurfaceColor,
                        unfocusedContainerColor = SurfaceColor,
                        cursorColor = AccentBlue
                    ),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        color = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            // Applied confirmation
            if (codeApplied) {
                Spacer(Modifier.height(12.dp))
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
                        text = stringResource(R.string.onboarding_promo_applied),
                        fontSize = 14.sp,
                        color = SuccessGreen
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Continue / Skip button
        Button(
            onClick = {
                focusManager.clearFocus()
                if (promoCode.isBlank()) {
                    onSkip()
                } else {
                    if (!codeApplied) {
                        codeApplied = true
                    }
                    onContinue()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(
                text = if (promoCode.isBlank()) stringResource(R.string.common_skip) else stringResource(R.string.common_continue),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}
