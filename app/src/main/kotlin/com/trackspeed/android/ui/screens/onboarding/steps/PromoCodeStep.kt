package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.onboarding.PromoRedemptionState
import com.trackspeed.android.ui.theme.*

private val ErrorRed = Color(0xFFFF5252)

@Composable
fun PromoCodeStep(
    promoCode: String,
    onPromoCodeChanged: (String) -> Unit,
    redemptionState: PromoRedemptionState,
    onSubmit: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }

    val isLoading = redemptionState is PromoRedemptionState.Loading
    val isSuccess = redemptionState is PromoRedemptionState.Success
    val isError = redemptionState is PromoRedemptionState.Error

    // Auto-advance after successful free code redemption
    LaunchedEffect(redemptionState) {
        if (redemptionState is PromoRedemptionState.Success && redemptionState.result.type == "free") {
            kotlinx.coroutines.delay(2000)
            onContinue()
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
                color = TextPrimary
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onboarding_promo_subtitle),
                fontSize = 17.sp,
                color = TextSecondary
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
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = promoCode,
                    onValueChange = { onPromoCodeChanged(it.uppercase()) },
                    placeholder = { Text(stringResource(R.string.onboarding_promo_field_placeholder), color = TextMuted) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = when {
                            isError -> ErrorRed
                            isSuccess -> AccentGreen
                            isFocused -> AccentBlue
                            else -> BorderSubtle
                        },
                        unfocusedBorderColor = when {
                            isError -> ErrorRed
                            isSuccess -> AccentGreen
                            else -> BorderSubtle
                        },
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        cursorColor = AccentBlue
                    ),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        color = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isLoading && !isSuccess
                )
            }

            // Status messages
            Spacer(Modifier.height(12.dp))

            when (redemptionState) {
                is PromoRedemptionState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = AccentBlue,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Verifying code...",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                    }
                }
                is PromoRedemptionState.Success -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = AccentGreen
                        )
                        Text(
                            text = if (redemptionState.result.type == "free") {
                                "Pro Activated!"
                            } else {
                                "30-day trial unlocked!"
                            },
                            fontSize = 14.sp,
                            color = AccentGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                is PromoRedemptionState.Error -> {
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
                            text = redemptionState.message,
                            fontSize = 14.sp,
                            color = ErrorRed
                        )
                    }
                }
                is PromoRedemptionState.Idle -> {
                    // Show nothing
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Apply / Continue / Skip button
        Button(
            onClick = {
                focusManager.clearFocus()
                when {
                    promoCode.isBlank() -> onSkip()
                    isSuccess -> onContinue()
                    else -> onSubmit(promoCode)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSuccess) AccentGreen else AccentBlue
            ),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp
                )
            } else {
                Text(
                    text = when {
                        promoCode.isBlank() -> stringResource(R.string.common_skip)
                        isSuccess -> stringResource(R.string.common_continue)
                        else -> "Apply Code"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSuccess) Color.Black else Color.White
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
