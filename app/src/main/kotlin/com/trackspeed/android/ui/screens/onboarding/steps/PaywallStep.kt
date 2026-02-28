package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.delay

private val AccentBlue = Color(0xFF0A84FF)
private val SurfaceColor = Color(0xFF1C1C1E)

@Composable
fun PaywallStep(
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    var canSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(3000); canSkip = true }

    val features = listOf(
        R.string.onboarding_paywall_feature1,
        R.string.onboarding_paywall_feature2,
        R.string.onboarding_paywall_feature3,
        R.string.onboarding_paywall_feature4,
        R.string.onboarding_paywall_feature5
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // Phone mockup image
            Image(
                painter = painterResource(R.drawable.onboarding_photofinish_edit),
                contentDescription = stringResource(R.string.onboarding_paywall_image_description),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .shadow(elevation = 20.dp, shape = RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit
            )

            Spacer(Modifier.height(24.dp))

            // "No Payment Due Now" badge
            Surface(
                color = AccentBlue.copy(alpha = 0.12f),
                shape = RoundedCornerShape(50)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = AccentBlue
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.onboarding_paywall_badge),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentBlue
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Pricing card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceColor),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.onboarding_paywall_trial_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            stringResource(R.string.onboarding_paywall_price),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            stringResource(R.string.onboarding_paywall_price_period),
                            fontSize = 16.sp,
                            color = Color(0xFF8E8E93),
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.onboarding_paywall_billed),
                        fontSize = 14.sp,
                        color = Color(0xFF8E8E93)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Feature checklist
            features.forEach { featureRes ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF30D158)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(featureRes), fontSize = 16.sp, color = Color.White)
                }
            }

            // Bottom padding for floating button
            Spacer(Modifier.height(120.dp))
        }

        // Floating bottom section
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0x000A0A0A), Color(0xFF0A0A0A))
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A))
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(stringResource(R.string.onboarding_paywall_start_trial), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                if (canSkip) {
                    TextButton(onClick = onSkip) {
                        Text(stringResource(R.string.onboarding_paywall_limited_access), color = Color(0xFF8E8E93), fontSize = 14.sp)
                    }
                } else {
                    Spacer(Modifier.height(40.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
