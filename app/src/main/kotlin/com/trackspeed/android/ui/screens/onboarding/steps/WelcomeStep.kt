package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.settings.LanguagePickerDialog
import com.trackspeed.android.ui.screens.settings.applyLanguage
import com.trackspeed.android.ui.screens.settings.getCurrentLanguageTag
import com.trackspeed.android.ui.screens.settings.getLanguageDisplayName
import com.trackspeed.android.ui.theme.*

@Composable
fun WelcomeStep(
    onGetStarted: () -> Unit,
    onJoinSession: () -> Unit = {},
    onSignIn: () -> Unit
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(getCurrentLanguageTag()) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background image with blur
        Image(
            painter = painterResource(R.drawable.sprint_finish_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(4.dp)
        )

        // Dark gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            BackgroundGradientTop.copy(alpha = 0.55f),
                            BackgroundGradientBottom.copy(alpha = 0.75f),
                            BackgroundGradientBottom.copy(alpha = 0.95f)
                        )
                    )
                )
        )

        // Language button (top-right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 12.dp, end = 20.dp),
            horizontalArrangement = Arrangement.End
        ) {
            FilledTonalButton(
                onClick = { showLanguagePicker = true },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor = TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    getLanguageDisplayName(currentLanguage).take(12),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.weight(1f))

            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = stringResource(R.string.onboarding_welcome_logo_description),
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(22.dp))
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_welcome_subtitle),
                fontSize = 18.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))
            Text(
                text = stringResource(R.string.onboarding_welcome_tagline),
                fontSize = 20.sp,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                lineHeight = 28.sp
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
            ) {
                Text(stringResource(R.string.onboarding_welcome_get_started), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onJoinSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(),
                border = BorderStroke(1.dp, AccentNavy)
            ) {
                Icon(
                    Icons.Default.Sensors,
                    contentDescription = null,
                    tint = AccentNavy,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.onboarding_welcome_join_session), color = AccentNavy, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onSignIn) {
                Text(stringResource(R.string.onboarding_welcome_sign_in), color = AccentNavy)
            }
            Spacer(Modifier.height(32.dp))
        }

        // Language picker dialog
        if (showLanguagePicker) {
            LanguagePickerDialog(
                currentLanguage = currentLanguage,
                onLanguageSelected = { tag ->
                    applyLanguage(tag)
                    currentLanguage = tag
                },
                onDismiss = { showLanguagePicker = false }
            )
        }
    }
}
