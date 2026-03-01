package com.trackspeed.android.ui.screens.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import com.trackspeed.android.data.local.dao.SessionSummary
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
import java.io.File
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
        sessionRepository.getRecentSessions(5)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val sessionSummaries: StateFlow<Map<String, SessionSummary>> =
        sessionRepository.getSessionSummaries()
            .map { list -> list.associateBy { it.sessionId } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap()
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
    val sessionSummaries by viewModel.sessionSummaries.collectAsStateWithLifecycle()
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
                    isInBillingGracePeriod = isInBillingGracePeriod,
                    onSoloModeClick = { onTemplateClick(60.0, "standing", 1) },
                    onAccelerationClick = { onTemplateClick(10.0, "standing", 2) },
                    onSprintClick = { onTemplateClick(60.0, "standing", 2) },
                    onTakeOffClick = { onTemplateClick(20.0, "flying", 2) },
                    onCustomSessionClick = onBasicModeClick,
                    onJoinSessionClick = onClockSyncClick,
                    onSeeAllClick = { selectedTab = HomeTab.HISTORY },
                    onSessionClick = onSessionClick,
                    onPaywallClick = onPaywallClick,
                    recentSessions = recentSessions,
                    sessionSummaries = sessionSummaries,
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
    sessionSummaries: Map<String, SessionSummary> = emptyMap(),
    isProUser: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // App logo + title (matching iOS DashboardHomeView header)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.home_icon),
                contentDescription = null,
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "TrackSpeed",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = TextPrimary
            )
        }

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
                letterSpacing = 0.5.sp
            ),
            color = TextMuted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        // 2x2 grid of mode cards (matching iOS PresetCardButton)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeCard(
                title = stringResource(R.string.home_solo_mode),
                icon = Icons.Outlined.Sync,
                iconColor = Color(0xFF30D158),
                onClick = onSoloModeClick,
                requiresPro = false,
                isProUser = isProUser,
                onPaywallClick = onPaywallClick,
                modifier = Modifier.weight(1f)
            )
            ModeCard(
                title = stringResource(R.string.home_10m_acceleration),
                icon = Icons.Outlined.LocalFireDepartment,
                iconColor = Color(0xFFFF9500),
                onClick = onAccelerationClick,
                requiresPro = !isProUser,
                isProUser = isProUser,
                onPaywallClick = onPaywallClick,
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
                iconColor = AccentNavy,
                onClick = onSprintClick,
                requiresPro = !isProUser,
                isProUser = isProUser,
                onPaywallClick = onPaywallClick,
                modifier = Modifier.weight(1f)
            )
            ModeCard(
                title = stringResource(R.string.home_take_off_velocity),
                icon = Icons.Outlined.RocketLaunch,
                iconColor = Color(0xFFBF8040),
                onClick = onTakeOffClick,
                requiresPro = !isProUser,
                isProUser = isProUser,
                onPaywallClick = onPaywallClick,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Custom Session card (full-width) — iOS: primaryAccent colored circle
        FullWidthActionCard(
            title = stringResource(R.string.home_custom_session),
            subtitle = stringResource(R.string.home_custom_session_subtitle),
            icon = Icons.Outlined.Tune,
            iconColor = AccentNavy,
            onClick = onCustomSessionClick
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Join Session card (full-width) — iOS: secondary (green) colored circle
        FullWidthActionCard(
            title = stringResource(R.string.home_join_session),
            subtitle = stringResource(R.string.home_join_session_subtitle),
            icon = Icons.Outlined.Groups,
            iconColor = AccentGreen,
            onClick = onJoinSessionClick
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
            if (recentSessions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.home_see_all),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentNavy,
                    modifier = Modifier.clickable { onSeeAllClick() }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (recentSessions.isEmpty()) {
            // Rich empty state (matching iOS ContentUnavailableView)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_no_sessions_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.home_no_sessions_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onCustomSessionClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentNavy,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.home_start_session),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentSessions.forEach { session ->
                    RecentSessionCard(
                        session = session,
                        summary = sessionSummaries[session.id],
                        onClick = { onSessionClick(session.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ── ModeCard (matching iOS PresetCardButton) ──────────────────────────────────

@Composable
private fun ModeCard(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    requiresPro: Boolean = false,
    isProUser: Boolean = false,
    onPaywallClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(20.dp)
    val colors = LocalAppColors.current

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 2.dp,
                    shape = cardShape,
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = Color.Black.copy(alpha = 0.06f)
                )
                .clip(cardShape)
                .background(colors.surface)
                .border(1.dp, colors.border, cardShape)
                .clickable(onClick = if (requiresPro) onPaywallClick else onClick)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (requiresPro) Modifier.blur(3.dp) else Modifier)
                    .padding(16.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Gradient circle icon (matching iOS LinearGradient 0.2→0.1 opacity)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    iconColor.copy(alpha = 0.2f),
                                    iconColor.copy(alpha = 0.1f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // PRO badge overlay (matching iOS ProGateOverlay)
            if (requiresPro) {
                Box(
                    modifier = Modifier.matchParentSize(),
                    contentAlignment = Alignment.Center
                ) {
                    ProBadge()
                }
            }
        }
    }
}

// ── Pro Badge (matching iOS ProBadge: crown + "PRO" pill) ──────────────────────

@Composable
private fun ProBadge() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        AccentNavy,
                        AccentNavy.copy(alpha = 0.85f)
                    )
                )
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "\uD83D\uDC51", // crown emoji
            fontSize = 10.sp
        )
        Text(
            text = "PRO",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp
            ),
            color = Color.White
        )
    }
}

// ── FullWidthActionCard (matching iOS Custom/Join Session cards) ───────────────

@Composable
private fun FullWidthActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(16.dp)
    val colors = LocalAppColors.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.06f)
            )
            .clip(cardShape)
            .background(colors.surface)
            .border(1.dp, colors.border, cardShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Colored circle behind icon (matching iOS 50pt circle)
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary
                )
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

// ── RecentSessionCard (matching iOS RecentSessionRow) ─────────────────────────

private val SuccessGreen = Color(0xFF30D158)

@Composable
private fun RecentSessionCard(
    session: TrainingSessionEntity,
    summary: SessionSummary?,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()) }
    val distanceLabel = when {
        session.distance % 1.0 == 0.0 -> "${session.distance.toInt()}m"
        else -> "${session.distance}m"
    }

    // Load thumbnail from session's first run (thumbnails stored at thumbnails/{sessionId}/lap_1.jpg)
    val thumbnail = remember(session.id) {
        try {
            // Try session thumbnail first, then fall back to first run's thumbnail
            val sessionPath = session.thumbnailPath
            if (sessionPath != null && File(sessionPath).exists()) {
                BitmapFactory.decodeFile(sessionPath)?.asImageBitmap()
            } else {
                // Look for first run thumbnail in the session directory
                val dir = File(
                    android.os.Environment.getDataDirectory(),
                    "data/com.trackspeed.android/files/thumbnails/${session.id}"
                )
                val firstLap = dir.listFiles()
                    ?.filter { it.extension == "jpg" }
                    ?.minByOrNull { it.name }
                firstLap?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
            }
        } catch (_: Exception) { null }
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
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 56x56 thumbnail (matching iOS RecentSessionRow)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Gradient placeholder with runner icon (matching iOS fallback)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        TextSecondary.copy(alpha = 0.3f),
                                        TextSecondary.copy(alpha = 0.1f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Details column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Date line (abbreviated date + shortened time, matching iOS)
                Text(
                    text = dateFormat.format(Date(session.date)),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Metadata pills row (matching iOS HStack with distance pill, runs pill, best time)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Distance pill
                    MetadataPill(text = distanceLabel, icon = "\uD83D\uDCCF") // ruler

                    // Runs pill
                    if (summary != null) {
                        MetadataPill(text = "${summary.runCount}", icon = "\u23F1") // stopwatch
                    }

                    // Best time (green, matching iOS AppColors.success)
                    if (summary != null && summary.bestTime > 0) {
                        Text(
                            text = "%.2fs".format(summary.bestTime),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = SuccessGreen
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataPill(text: String, icon: String) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surface.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            fontSize = 9.sp
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
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
