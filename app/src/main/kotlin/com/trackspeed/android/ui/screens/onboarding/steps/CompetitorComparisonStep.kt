package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private val AccentBlue = Color(0xFF0A84FF)

@Composable
fun CompetitorComparisonStep(onContinue: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // Header
            Text(
                stringResource(R.string.onboarding_comparison_title_top),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.onboarding_comparison_title_bottom),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = AccentBlue,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // TrackSpeed card with accent border
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, AccentBlue)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Logo + name + price + save badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            painter = painterResource(R.drawable.app_logo),
                            contentDescription = stringResource(R.string.onboarding_comparison_trackspeed_logo_description),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.onboarding_comparison_trackspeed_name),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                stringResource(R.string.onboarding_comparison_price),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentBlue
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = AccentBlue,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                stringResource(R.string.onboarding_comparison_save_badge),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Feature pills
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            stringResource(R.string.onboarding_comparison_pill_photo_finish),
                            stringResource(R.string.onboarding_comparison_pill_setup),
                            stringResource(R.string.onboarding_comparison_pill_phone_only)
                        ).forEach { label ->
                            Surface(
                                color = AccentBlue.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AccentBlue,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Divider with "vs" label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF3A3A3C))
                Text(
                    "  ${stringResource(R.string.onboarding_comparison_vs_divider)}  ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF8E8E93)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF3A3A3C))
            }

            Spacer(Modifier.height(20.dp))

            // Competitor hardware image
            Image(
                painter = painterResource(R.drawable.onboarding_competitor_hardware),
                contentDescription = stringResource(R.string.onboarding_comparison_hardware_image_description),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit
            )

            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.onboarding_comparison_hardware_price),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                stringResource(R.string.onboarding_comparison_hardware_subtitle),
                fontSize = 15.sp,
                color = Color(0xFF8E8E93)
            )

            // Bottom padding for button overlay
            Spacer(Modifier.height(100.dp))
        }

        // Floating continue button with gradient fade
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A))
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 32.dp)
            ) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
