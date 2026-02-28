package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

@Composable
fun MultiDeviceStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Headline at top
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.onboarding_multidevice_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_multidevice_subtitle),
            fontSize = 15.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        // Track layout card
        TrackLayoutCard()

        Spacer(Modifier.height(24.dp))

        // Feature pills
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FeaturePill(
                icon = { Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentNavy) },
                title = stringResource(R.string.onboarding_multidevice_bluetooth),
                subtitle = stringResource(R.string.onboarding_multidevice_bluetooth_subtitle),
                modifier = Modifier.weight(1f)
            )
            FeaturePill(
                icon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentNavy) },
                title = stringResource(R.string.onboarding_multidevice_synced),
                subtitle = stringResource(R.string.onboarding_multidevice_synced_subtitle),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
        ) {
            Text(
                stringResource(R.string.common_continue),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TrackLayoutCard() {
    val gateGreen = Color(0xFF34C759)
    val gateBlue = AccentNavy
    val gateOrange = Color(0xFFFF9500)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: TRACK LAYOUT + 3 phones badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.onboarding_multidevice_track_layout),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Text(
                    stringResource(R.string.onboarding_multidevice_phones_badge),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier
                        .background(
                            color = BorderSubtle.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // Track visualization: START -- runner --> LAP -- runner --> FINISH
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // START gate
                GateMarker(
                    label = stringResource(R.string.onboarding_multidevice_start),
                    distance = "0m",
                    color = gateGreen
                )

                // Track segment with runner
                TrackSegment(
                    distance = "30m",
                    modifier = Modifier.weight(1f)
                )

                // LAP gate
                GateMarker(
                    label = stringResource(R.string.onboarding_multidevice_lap),
                    distance = "30m",
                    color = gateBlue
                )

                // Track segment
                TrackSegment(
                    distance = "30m",
                    modifier = Modifier.weight(1f)
                )

                // FINISH gate
                GateMarker(
                    label = stringResource(R.string.onboarding_multidevice_finish),
                    distance = "60m",
                    color = gateOrange
                )
            }
        }
    }
}

@Composable
private fun GateMarker(
    label: String,
    distance: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Phone icon with colored glow
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f))
                    .border(1.dp, color.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = color
                )
            }
        }

        // Label
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 0.5.sp
        )

        // Distance
        Text(
            distance,
            fontSize = 10.sp,
            color = TextMuted
        )
    }
}

@Composable
private fun TrackSegment(
    distance: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 4.dp)
    ) {
        // Runner icon
        Icon(
            Icons.Default.DirectionsRun,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = TextSecondary
        )

        Spacer(Modifier.height(4.dp))

        // Track line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            BorderSubtle.copy(alpha = 0.3f),
                            BorderSubtle,
                            BorderSubtle.copy(alpha = 0.3f)
                        )
                    )
                )
        )

        Spacer(Modifier.height(4.dp))

        Text(
            distance,
            fontSize = 9.sp,
            color = TextMuted
        )
    }
}

@Composable
private fun FeaturePill(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderSubtle.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon()
            Column {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }
    }
}
