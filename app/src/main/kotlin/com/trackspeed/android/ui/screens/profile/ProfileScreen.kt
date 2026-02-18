package com.trackspeed.android.ui.screens.profile

import android.content.res.Configuration
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.ui.theme.TrackSpeedTheme

// Design tokens
private val PureBlack = Color(0xFF000000)
private val CardBg = Color(0xFF2C2C2E)
private val TextSecondary = Color(0xFF8E8E93)
private val AccentBlue = Color(0xFF0A84FF)
private val AccentGreen = Color(0xFF30D158)
private val ProGreen = Color(0xFF00E676)

@Composable
fun ProfileScreen(
    onPaywallClick: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    ProfileScreenContent(
        uiState = uiState,
        onPaywallClick = onPaywallClick
    )
}

@Composable
private fun ProfileScreenContent(
    uiState: ProfileUiState,
    onPaywallClick: () -> Unit = {}
) {
    var selectedDistance by remember { mutableStateOf("60m") }
    var selectedStartType by remember { mutableStateOf("Flying") }
    var selectedSpeedUnit by remember { mutableStateOf("m/s") }
    var darkModeEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // -- Profile Header --
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(CardBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = "Profile avatar",
                    modifier = Modifier.size(40.dp),
                    tint = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Athlete",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Set up your profile",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // -- Subscription Status --
        if (uiState.isProUser) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = ProGreen.copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = ProGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "TrackSpeed Pro",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = ProGreen
                    )
                }
            }
        } else {
            Card(
                onClick = onPaywallClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = ProGreen)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Upgrade to Pro",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.Black
                        )
                        Text(
                            text = "Unlock multi-phone sync, unlimited history & more",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- Stats Row --
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                label = "Sessions",
                value = uiState.totalSessions.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Runs",
                value = uiState.totalRuns.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Best Time",
                value = uiState.bestTimeSeconds?.let { formatTime(it) } ?: "--",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- Personal Bests --
        SectionHeader("Personal Bests")

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.personalBests.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEvents,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = TextSecondary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No personal bests yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Complete runs to record your times",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    uiState.personalBests.forEachIndexed { index, pb ->
                        PersonalBestRow(
                            distanceLabel = pb.distanceLabel,
                            timeSeconds = pb.timeSeconds
                        )
                        if (index < uiState.personalBests.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = Color(0xFF3A3A3C)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- Settings --
        SectionHeader("Settings")

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column {
                // Distance preference
                SettingsRow(
                    icon = Icons.Outlined.Straighten,
                    label = "Distance",
                    content = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("40yd", "60m", "100m").forEach { d ->
                                CompactChip(
                                    label = d,
                                    selected = selectedDistance == d,
                                    onClick = { selectedDistance = d }
                                )
                            }
                        }
                    }
                )

                SettingsDivider()

                // Start type preference
                SettingsRow(
                    icon = Icons.Outlined.Timer,
                    label = "Start type",
                    content = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Flying", "Standing").forEach { st ->
                                CompactChip(
                                    label = st,
                                    selected = selectedStartType == st,
                                    onClick = { selectedStartType = st }
                                )
                            }
                        }
                    }
                )

                SettingsDivider()

                // Speed unit
                SettingsRow(
                    icon = Icons.Outlined.Speed,
                    label = "Speed unit",
                    content = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("m/s", "km/h", "mph").forEach { unit ->
                                CompactChip(
                                    label = unit,
                                    selected = selectedSpeedUnit == unit,
                                    onClick = { selectedSpeedUnit = unit }
                                )
                            }
                        }
                    }
                )

                SettingsDivider()

                // Dark mode toggle
                SettingsRow(
                    icon = Icons.Outlined.DarkMode,
                    label = "Dark mode",
                    content = {
                        Switch(
                            checked = darkModeEnabled,
                            onCheckedChange = { darkModeEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AccentGreen,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFF48484A)
                            )
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- About --
        SectionHeader("About")

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column {
                AboutRow(
                    icon = Icons.Outlined.Info,
                    label = "Version",
                    value = "TrackSpeed v1.0.0"
                )

                SettingsDivider()

                AboutRow(
                    icon = null,
                    label = "Build",
                    value = "Android"
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "TrackSpeed - Precision Sprint Timing",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// -- Components --

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun PersonalBestRow(
    distanceLabel: String,
    timeSeconds: Double
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = distanceLabel,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Medium
            ),
            color = Color.White
        )
        Text(
            text = formatTime(timeSeconds),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            ),
            color = AccentGreen
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        }
        content()
    }
}

@Composable
private fun AboutRow(
    icon: ImageVector?,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = TextSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Spacer(modifier = Modifier.width(32.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun CompactChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentBlue,
            selectedLabelColor = Color.White,
            containerColor = Color(0xFF3A3A3C),
            labelColor = TextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true,
            selected = selected
        )
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = Color(0xFF3A3A3C)
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        ),
        color = TextSecondary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

// -- Helpers --

private fun formatTime(seconds: Double): String {
    return when {
        seconds < 60.0 -> String.format("%.2fs", seconds)
        else -> {
            val min = (seconds / 60).toInt()
            val sec = seconds % 60
            String.format("%d:%05.2f", min, sec)
        }
    }
}

// -- Previews --

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000
)
@Composable
private fun ProfileScreenPreview() {
    TrackSpeedTheme(darkTheme = true) {
        ProfileScreenContent(
            uiState = ProfileUiState(
                totalSessions = 12,
                totalRuns = 47,
                bestTimeSeconds = 7.34,
                personalBests = listOf(
                    PersonalBest(60.0, 7.34),
                    PersonalBest(100.0, 11.89),
                    PersonalBest(200.0, 24.12)
                )
            )
        )
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000,
    name = "Profile - Empty State"
)
@Composable
private fun ProfileScreenEmptyPreview() {
    TrackSpeedTheme(darkTheme = true) {
        ProfileScreenContent(
            uiState = ProfileUiState()
        )
    }
}
