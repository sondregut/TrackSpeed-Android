package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
    onSignIn: () -> Unit,
    onDebugSkip: () -> Unit = {},
    onDebugPaywall: () -> Unit = {}
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(getCurrentLanguageTag()) }
    var appeared by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "welcome_fade"
    )
    val offsetY by animateFloatAsState(
        targetValue = if (appeared) 0f else 20f,
        animationSpec = tween(durationMillis = 600),
        label = "welcome_offset"
    )

    LaunchedEffect(Unit) { appeared = true }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background image with blur (matching iOS SprintFinishBackground fallback)
        Image(
            painter = painterResource(R.drawable.sprint_finish_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(6.dp)
        )

        // Dark gradient overlay matching iOS: 0.4 -> 0.6 -> 0.85
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            BackgroundGradientTop.copy(alpha = 0.4f),
                            BackgroundGradientBottom.copy(alpha = 0.6f),
                            BackgroundGradientBottom.copy(alpha = 0.85f)
                        )
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar: debug skip (left) + language picker (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Debug skip (subtle, matching iOS forward.fill at 0.3 opacity)
                IconButton(onClick = onDebugSkip) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Language button (matching iOS ultraThinMaterial pill)
                FilledTonalButton(
                    onClick = { showLanguagePicker = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        getLanguageDisplayName(currentLanguage).take(12),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Logo and title (matching iOS VStack spacing: lg)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.offset(y = offsetY.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.app_logo),
                    contentDescription = stringResource(R.string.onboarding_welcome_logo_description),
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(22.dp)),
                    alpha = alpha
                )
                Spacer(Modifier.height(20.dp))
                // iOS: "Welcome to" in title2, textSecondary
                Text(
                    text = "Welcome to",
                    fontSize = 22.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.offset(y = offsetY.dp)
                )
                Spacer(Modifier.height(4.dp))
                // iOS: "TrackSpeed" in title1, text
                Text(
                    text = stringResource(R.string.onboarding_welcome_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.offset(y = offsetY.dp)
                )
                Spacer(Modifier.height(12.dp))
                // iOS: "Professional sprint timing using your iPhone" in body, textSecondary
                Text(
                    text = stringResource(R.string.onboarding_welcome_tagline),
                    fontSize = 16.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.offset(y = offsetY.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // Buttons (matching iOS: centered 260dp width, pill shape)
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .offset(y = offsetY.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Get Started - iOS: width 260, height 50, full rounded, onboardingAccent
                Button(
                    onClick = onGetStarted,
                    modifier = Modifier
                        .width(260.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(
                        stringResource(R.string.onboarding_welcome_get_started),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Join Session - iOS: outlined pill with antenna icon
                OutlinedButton(
                    onClick = onJoinSession,
                    modifier = Modifier
                        .width(260.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(2.dp, AccentBlue)
                ) {
                    Icon(
                        Icons.Default.SensorsOff,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.onboarding_welcome_join_session),
                        color = AccentBlue,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Sign in link - iOS: underlined, textSecondary
                TextButton(onClick = onSignIn) {
                    Text(
                        stringResource(R.string.onboarding_welcome_sign_in),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textDecoration = TextDecoration.Underline
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
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
