package com.trackspeed.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.ui.screens.history.SessionHistoryScreen
import com.trackspeed.android.ui.screens.profile.ProfileScreen
import com.trackspeed.android.ui.screens.templates.TemplatesScreen
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

private val CardBackground = Color(0xFF2C2C2E)
private val NavBarBackground = Color(0xFF1C1C1E)
private val AccentGreen = Color(0xFF00E676)

@HiltViewModel
class HomeViewModel @Inject constructor(
    subscriptionManager: SubscriptionManager
) : ViewModel() {
    val isProUser: StateFlow<Boolean> = subscriptionManager.isProUser
}

private enum class HomeTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    TEMPLATES("Templates", Icons.Outlined.Style),
    HISTORY("History", Icons.Default.Schedule),
    PROFILE("Profile", Icons.Default.Person)
}

@Composable
fun HomeScreen(
    onBasicModeClick: () -> Unit = {},
    onRaceModeClick: () -> Unit = {},
    onClockSyncClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSessionClick: (String) -> Unit = {},
    onTemplateClick: (Double, String) -> Unit = { _, _ -> },
    onPaywallClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val isProUser by viewModel.isProUser.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(HomeTab.HOME) }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(
                containerColor = NavBarBackground,
                contentColor = Color.White,
                tonalElevation = 0.dp
            ) {
                HomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 10.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF0A84FF),
                            selectedTextColor = Color(0xFF0A84FF),
                            unselectedIconColor = Color(0xFF8E8E93),
                            unselectedTextColor = Color(0xFF8E8E93),
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            when (selectedTab) {
                HomeTab.HOME -> HomeContent(
                    onSoloModeClick = onBasicModeClick,
                    onAccelerationClick = onBasicModeClick,
                    onSprintClick = onBasicModeClick,
                    onTakeOffClick = onBasicModeClick,
                    onCustomSessionClick = onBasicModeClick,
                    onJoinSessionClick = if (isProUser) onClockSyncClick else onPaywallClick,
                    onSeeAllClick = { selectedTab = HomeTab.HISTORY },
                    isProUser = isProUser
                )
                HomeTab.TEMPLATES -> TemplatesScreen(
                    onTemplateClick = onTemplateClick
                )
                HomeTab.HISTORY -> SessionHistoryScreen(
                    onSessionClick = onSessionClick
                )
                HomeTab.PROFILE -> ProfileScreen(
                    onPaywallClick = onPaywallClick
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    onSoloModeClick: () -> Unit,
    onAccelerationClick: () -> Unit,
    onSprintClick: () -> Unit,
    onTakeOffClick: () -> Unit,
    onCustomSessionClick: () -> Unit,
    onJoinSessionClick: () -> Unit,
    onSeeAllClick: () -> Unit,
    isProUser: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Title
        Text(
            text = "TrackSpeed",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            ),
            color = Color.White
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Precision sprint timing",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF8E8E93),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))

        // QUICK START section header
        Text(
            text = "QUICK START",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp
            ),
            color = Color(0xFF8E8E93),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        // 2x2 grid of mode cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeCard(
                title = "Solo Mode",
                icon = Icons.Outlined.Sync,
                iconBackgroundColor = Color(0xFF30D158),
                onClick = onSoloModeClick,
                modifier = Modifier.weight(1f)
            )
            ModeCard(
                title = "10m Acceleration",
                icon = Icons.Outlined.LocalFireDepartment,
                iconBackgroundColor = Color(0xFF0A84FF),
                onClick = onAccelerationClick,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeCard(
                title = "60m Sprint",
                icon = Icons.AutoMirrored.Outlined.DirectionsRun,
                iconBackgroundColor = Color(0xFF8E8E93),
                onClick = onSprintClick,
                modifier = Modifier.weight(1f)
            )
            ModeCard(
                title = "Take Off Velocity",
                icon = Icons.Outlined.RocketLaunch,
                iconBackgroundColor = Color(0xFFBF8040),
                onClick = onTakeOffClick,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Custom Session card (full-width)
        FullWidthActionCard(
            title = "Custom Session",
            subtitle = "Configure distance, start type & more",
            icon = Icons.Outlined.Tune,
            onClick = onCustomSessionClick
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Join Session card (full-width) - Pro feature
        FullWidthActionCard(
            title = "Join Session",
            subtitle = "Connect to another phone",
            icon = Icons.Outlined.Groups,
            onClick = onJoinSessionClick,
            showProBadge = !isProUser
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Recent Sessions section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent Sessions",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = Color.White
            )
            Text(
                text = "See All",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF0A84FF),
                modifier = Modifier.clickable { onSeeAllClick() }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Placeholder for recent sessions
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No recent sessions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8E8E93)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ModeCard(
    title: String,
    icon: ImageVector,
    iconBackgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(iconBackgroundColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconBackgroundColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FullWidthActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProBadge: Boolean = false
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White
                    )
                    if (showProBadge) {
                        Text(
                            text = "PRO",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.Black,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentGreen)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E8E93)
                )
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF48484A),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    TrackSpeedTheme(darkTheme = true) {
        HomeScreen()
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenDarkPreview() {
    TrackSpeedTheme(darkTheme = true) {
        HomeScreen()
    }
}
