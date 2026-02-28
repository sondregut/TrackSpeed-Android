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
import androidx.compose.ui.graphics.Brush
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
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.ui.components.BillingIssueBanner
import com.trackspeed.android.ui.screens.history.SessionHistoryScreen
import com.trackspeed.android.ui.screens.profile.ProfileScreen
import com.trackspeed.android.ui.screens.templates.TemplatesScreen
import com.trackspeed.android.ui.theme.*
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// Theme colors are imported from com.trackspeed.android.ui.theme.*

@HiltViewModel
class HomeViewModel @Inject constructor(
    subscriptionManager: SubscriptionManager,
    sessionRepository: SessionRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {
    val isProUser: StateFlow<Boolean> = subscriptionManager.isProUser

    val recentSessions: StateFlow<List<TrainingSessionEntity>> =
        sessionRepository.getRecentSessions(3)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val totalRunCount: StateFlow<Int> =
        sessionRepository.getTotalRunCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    val userName: StateFlow<String> =
        settingsRepository.userName
            .map { name ->
                if (name.isNotBlank()) {
                    // Extract first name only (matching iOS behavior)
                    name.split(" ").firstOrNull() ?: name
                } else {
                    "" // Empty string signals composable to use default resource
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ""
            )

    val isInBillingGracePeriod: StateFlow<Boolean> = subscriptionManager.isInBillingGracePeriod
}

private enum class HomeTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.home_tab_home, Icons.Default.Home),
    TEMPLATES(R.string.home_tab_templates, Icons.Outlined.Style),
    HISTORY(R.string.home_tab_history, Icons.Default.Schedule),
    PROFILE(R.string.home_tab_profile, Icons.Default.Person)
}

@Composable
fun HomeScreen(
    onBasicModeClick: () -> Unit = {},
    onRaceModeClick: () -> Unit = {},
    onClockSyncClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSessionClick: (String) -> Unit = {},
    onTemplateClick: (Double, String, Int) -> Unit = { _, _, _ -> },
    onPaywallClick: () -> Unit = {},
    onAthletesClick: () -> Unit = {},
    onAuthClick: () -> Unit = {},
    onStatsClick: () -> Unit = {},
    onReferralClick: () -> Unit = {},
    onWindAdjustmentClick: () -> Unit = {},
    onDistanceConverterClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val isProUser by viewModel.isProUser.collectAsStateWithLifecycle()
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()
    val totalRunCount by viewModel.totalRunCount.collectAsStateWithLifecycle()
    val rawUserName by viewModel.userName.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.home_default_user_name)
    val userName = rawUserName.ifEmpty { defaultName }
    val isInBillingGracePeriod by viewModel.isInBillingGracePeriod.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(HomeTab.HOME) }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Column {
                // Top border line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(BorderSubtle)
                )
                NavigationBar(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    BackgroundGradientBottom.copy(alpha = 0.85f),
                                    BackgroundGradientBottom
                                )
                            )
                        )
                ) {
                    HomeTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(tab.labelRes)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(tab.labelRes),
                                    fontSize = 10.sp
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentNavy,
                                selectedTextColor = AccentNavy,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .gradientBackground()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                HomeTab.HOME -> HomeContent(
                    userName = userName,
                    totalRunCount = totalRunCount,
                    isInBillingGracePeriod = isInBillingGracePeriod,
                    onSoloModeClick = { onTemplateClick(60.0, "standing", 2) },
                    onAccelerationClick = { onTemplateClick(10.0, "standing", 2) },
                    onSprintClick = { onTemplateClick(60.0, "standing", 2) },
                    onTakeOffClick = { onTemplateClick(20.0, "flying", 2) },
                    onCustomSessionClick = onBasicModeClick,
                    onJoinSessionClick = onClockSyncClick,
                    onSeeAllClick = { selectedTab = HomeTab.HISTORY },
                    onSessionClick = onSessionClick,
                    onPaywallClick = onPaywallClick,
                    recentSessions = recentSessions,
                    isProUser = isProUser
                )
                HomeTab.TEMPLATES -> TemplatesScreen(
                    onTemplateClick = onTemplateClick
                )
                HomeTab.HISTORY -> SessionHistoryScreen(
                    onSessionClick = onSessionClick
                )
                HomeTab.PROFILE -> ProfileScreen(
                    onPaywallClick = onPaywallClick,
                    onAthletesClick = onAthletesClick,
                    onSettingsClick = onSettingsClick,
                    onReferralClick = onReferralClick,
                    onWindAdjustmentClick = onWindAdjustmentClick,
                    onDistanceConverterClick = onDistanceConverterClick
                )
            }
        }
    }
}

/**
 * Returns a time-of-day greeting string resource ID matching iOS DashboardHomeView.
 * Morning: 5-12, Afternoon: 12-17, Evening: 17-5.
 */
@StringRes
private fun getGreetingRes(): Int {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..16 -> R.string.home_greeting_afternoon
        in 17..21 -> R.string.home_greeting_evening
        else -> R.string.home_greeting_default
    }
}

@Composable
private fun HomeContent(
    userName: String = "",
    totalRunCount: Int = 0,
    isInBillingGracePeriod: Boolean = false,
    onSoloModeClick: () -> Unit,
    onAccelerationClick: () -> Unit,
    onSprintClick: () -> Unit,
    onTakeOffClick: () -> Unit,
    onCustomSessionClick: () -> Unit,
    onJoinSessionClick: () -> Unit,
    onSeeAllClick: () -> Unit,
    onSessionClick: (String) -> Unit = {},
    onPaywallClick: () -> Unit = {},
    recentSessions: List<TrainingSessionEntity> = emptyList(),
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

        // Time-of-day greeting (matching iOS DashboardHomeView)
        Text(
            text = stringResource(R.string.home_greeting_with_name, stringResource(getGreetingRes()), userName),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            ),
            color = TextPrimary,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Training run counter
        Text(
            text = if (totalRunCount == 0) {
                stringResource(R.string.home_start_first_sprint)
            } else {
                pluralStringResource(R.plurals.home_sprints_recorded, totalRunCount, totalRunCount)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Billing Issue Banner (dismissible, shown during grace period)
        if (isInBillingGracePeriod) {
            BillingIssueBanner(
                onActionClick = onPaywallClick,
                onDismiss = {}
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // QUICK START section header
        Text(
            text = stringResource(R.string.home_section_quick_start),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp
            ),
            color = TextSecondary,
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
                title = stringResource(R.string.home_solo_mode),
                icon = Icons.Outlined.Sync,
                iconBackgroundColor = Color(0xFF30D158),
                onClick = onSoloModeClick,
                modifier = Modifier.weight(1f)
            )
            ModeCard(
                title = stringResource(R.string.home_10m_acceleration),
                icon = Icons.Outlined.LocalFireDepartment,
                iconBackgroundColor = AccentNavy,
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
                title = stringResource(R.string.home_60m_sprint),
                icon = Icons.AutoMirrored.Outlined.DirectionsRun,
                iconBackgroundColor = TextSecondary,
                onClick = onSprintClick,
                modifier = Modifier.weight(1f)
            )
            ModeCard(
                title = stringResource(R.string.home_take_off_velocity),
                icon = Icons.Outlined.RocketLaunch,
                iconBackgroundColor = Color(0xFFBF8040),
                onClick = onTakeOffClick,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Custom Session card (full-width)
        FullWidthActionCard(
            title = stringResource(R.string.home_custom_session),
            subtitle = stringResource(R.string.home_custom_session_subtitle),
            icon = Icons.Outlined.Tune,
            onClick = onCustomSessionClick
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Join Session card (full-width) - free for everyone
        FullWidthActionCard(
            title = stringResource(R.string.home_join_session),
            subtitle = stringResource(R.string.home_join_session_subtitle),
            icon = Icons.Outlined.Groups,
            onClick = onJoinSessionClick,
            showProBadge = false
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Recent Sessions section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_recent_sessions),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = TextPrimary
            )
            Text(
                text = stringResource(R.string.home_see_all),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentNavy,
                modifier = Modifier.clickable { onSeeAllClick() }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (recentSessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .gunmetalCard()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_no_recent_sessions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentSessions.forEach { session ->
                    RecentSessionCard(
                        session = session,
                        onClick = { onSessionClick(session.id) }
                    )
                }
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
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .featureCard()
            .clickable(onClick = onClick)
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
                color = TextPrimary,
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .gunmetalCard()
            .clickable(onClick = onClick)
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
                tint = TextPrimary,
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
                        color = TextPrimary
                    )
                    if (showProBadge) {
                        Text(
                            text = stringResource(R.string.common_pro_badge),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentNavy)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun RecentSessionCard(
    session: TrainingSessionEntity,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val distanceLabel = when {
        session.distance % 1.0 == 0.0 -> "${session.distance.toInt()}m"
        else -> "${session.distance}m"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .gunmetalCard()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name ?: "$distanceLabel ${session.startType}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${dateFormat.format(Date(session.date))} - $distanceLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    TrackSpeedTheme() {
        HomeScreen()
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenDarkPreview() {
    TrackSpeedTheme() {
        HomeScreen()
    }
}
