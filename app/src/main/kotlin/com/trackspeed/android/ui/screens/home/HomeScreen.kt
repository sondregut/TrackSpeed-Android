package com.trackspeed.android.ui.screens.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.analytics.AnalyticsEvent
import com.trackspeed.android.analytics.AnalyticsService
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.data.model.SportCategory
import com.trackspeed.android.data.local.dao.SessionSummary
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.referral.ReferralService
import com.trackspeed.android.ui.components.BillingIssueBanner
import com.trackspeed.android.ui.screens.history.SessionHistoryScreen
import com.trackspeed.android.ui.screens.profile.ProfileScreen
import com.trackspeed.android.model.TestPreset
import com.trackspeed.android.model.TestPresetCategory
import com.trackspeed.android.ui.screens.templates.TemplatesScreen
import com.trackspeed.android.ui.theme.*
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.formatSessionMode
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.random.Random

// Theme colors are imported from com.trackspeed.android.ui.theme.*

data class RepeatSessionConfiguration(
    val distance: Double,
    val startType: String,
    val numberOfGates: Int,
    val athleteIds: Set<String>
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    subscriptionManager: SubscriptionManager,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val referralService: ReferralService,
    private val analyticsService: AnalyticsService
) : ViewModel() {
    private val quickPresetShuffleSeed = System.nanoTime().toInt()

    val isProUser: StateFlow<Boolean> = subscriptionManager.isProUser

    val recentSessions: StateFlow<List<TrainingSessionEntity>> =
        sessionRepository.getRecentSessions(5)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val allSessions: StateFlow<List<TrainingSessionEntity>> =
        sessionRepository.getAllSessions()
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

    val totalSessionCount: StateFlow<Int> =
        sessionRepository.getTotalSessionCount()
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

    val quickStartPresets: StateFlow<List<TestPreset>> =
        combine(
            settingsRepository.sportCategory,
            settingsRepository.presetLaunchCounts
        ) { sportCategory, launchCounts ->
            adaptiveFeaturedPresets(sportCategory, launchCounts).shuffledForHome()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TestPreset.featured.shuffledForHome()
        )

    val shouldShowHomeInviteCard: StateFlow<Boolean> =
        referralService.shouldShowHomeInviteCard
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = true
            )

    val newlyEarnedReferralBonusDays: StateFlow<Int> = referralService.newlyEarnedBonusDays

    val onboardingCompleted: StateFlow<Boolean> =
        settingsRepository.onboardingCompleted
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    val hasDismissedFirstSessionTutorial: StateFlow<Boolean> =
        settingsRepository.hasDismissedFirstSessionTutorial
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    val forceShowFirstSessionTutorial: StateFlow<Boolean> =
        settingsRepository.forceShowFirstSessionTutorial
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    val pendingDiscountMilestone: StateFlow<Int> =
        settingsRepository.pendingDiscountMilestone
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    val lastDiscountMilestoneFired: StateFlow<Int> =
        settingsRepository.lastDiscountMilestoneFired
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    init {
        viewModelScope.launch {
            runCatching {
                referralService.getOrCreateReferralCode()
                referralService.refreshStats()
            }
        }
    }

    fun dismissHomeInviteCard() {
        analyticsService.track(AnalyticsEvent.INVITE_CARD_DISMISSED)
        viewModelScope.launch {
            referralService.dismissHomeInviteCard()
        }
    }

    fun trackHomeInviteCardViewed() {
        analyticsService.track(AnalyticsEvent.INVITE_CARD_VIEWED)
    }

    fun trackHomeInviteCardTapped() {
        analyticsService.track(AnalyticsEvent.INVITE_CARD_TAPPED)
    }

    fun recordPresetLaunch(presetId: String) {
        viewModelScope.launch {
            settingsRepository.recordPresetLaunch(presetId)
        }
    }

    fun repeatSession(
        session: TrainingSessionEntity,
        onReady: (RepeatSessionConfiguration) -> Unit
    ) {
        viewModelScope.launch {
            val athleteIds = sessionRepository.getRunsForSession(session.id)
                .first()
                .mapNotNull { it.athleteId }
                .toSet()
            onReady(
                RepeatSessionConfiguration(
                    distance = session.distance,
                    startType = session.startType,
                    numberOfGates = if (session.numberOfPhones == 1) {
                        1
                    } else {
                        session.numberOfGates.coerceAtLeast(2)
                    },
                    athleteIds = athleteIds
                )
            )
        }
    }

    fun acknowledgeReferralBonusCelebration() {
        referralService.acknowledgeBonusCelebration()
    }

    fun trackReferralBonusCelebrationShown(daysEarned: Int) {
        analyticsService.track(
            AnalyticsEvent.REFERRAL_BONUS_CELEBRATION_SHOWN,
            mapOf("days_earned" to daysEarned)
        )
        referralService.acknowledgeBonusCelebration()
    }

    fun trackFirstSessionTutorialShown() {
        analyticsService.track(
            AnalyticsEvent.FIRST_SESSION_TUTORIAL_SHOWN,
            mapOf(
                "source" to "multi_step_tour",
                "step_count" to FirstSessionTutorialStep.entries.size
            )
        )
    }

    fun dismissFirstSessionTutorial(source: String) {
        analyticsService.track(
            AnalyticsEvent.FIRST_SESSION_TUTORIAL_DISMISSED,
            mapOf("source" to source)
        )
        viewModelScope.launch {
            settingsRepository.setForceShowFirstSessionTutorial(false)
            settingsRepository.setHasDismissedFirstSessionTutorial(true)
        }
    }

    fun startFirstSessionTutorialSetup() {
        analyticsService.track(
            AnalyticsEvent.FIRST_SESSION_TUTORIAL_STARTED,
            mapOf("source" to "tour_final_start_setup")
        )
        viewModelScope.launch {
            settingsRepository.setForceShowFirstSessionTutorial(false)
            settingsRepository.setHasDismissedFirstSessionTutorial(true)
        }
    }

    fun consumePendingDiscountMilestone(milestone: Int) {
        if (milestone <= 0) return
        viewModelScope.launch {
            settingsRepository.setLastDiscountMilestoneFired(milestone)
            settingsRepository.setPendingDiscountMilestone(0)
        }
    }

    fun clearPendingDiscountMilestone() {
        viewModelScope.launch {
            settingsRepository.setPendingDiscountMilestone(0)
        }
    }

    private fun adaptiveFeaturedPresets(
        sportCategory: SportCategory?,
        launchCounts: Map<String, Int>
    ): List<TestPreset> {
        val defaults = sportCategory?.let(TestPreset::defaultPresets) ?: TestPreset.featured
        if (launchCounts.isEmpty()) return defaults

        val topUsed = TestPreset.all
            .filter { preset -> (launchCounts[preset.id] ?: 0) > 0 }
            .sortedByDescending { preset -> launchCounts[preset.id] ?: 0 }
            .take(4)
            .toMutableList()

        val usedIds = topUsed.mapTo(mutableSetOf()) { it.id }
        defaults.forEach { preset ->
            if (topUsed.size < 4 && preset.id !in usedIds) {
                topUsed += preset
                usedIds += preset.id
            }
        }

        return topUsed
    }

    private fun List<TestPreset>.shuffledForHome(): List<TestPreset> {
        if (size <= 1) return this
        return shuffled(Random(quickPresetShuffleSeed))
    }
}

private enum class HomeTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.home_tab_home, Icons.Default.Home),
    TEMPLATES(R.string.home_tab_templates, Icons.Outlined.Style),
    HISTORY(R.string.home_tab_history, Icons.Default.Schedule),
    PROFILE(R.string.home_tab_profile, Icons.Default.Person)
}

private enum class FirstSessionTutorialTarget {
    CUSTOM_SESSION,
    TEMPLATES_SCREEN,
    JOIN_SESSION
}

private enum class FirstSessionTutorialStep(
    val target: FirstSessionTutorialTarget?,
    val tab: HomeTab,
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
    @StringRes val hintRes: Int
) {
    CUSTOM_SESSION(
        target = FirstSessionTutorialTarget.CUSTOM_SESSION,
        tab = HomeTab.HOME,
        icon = Icons.Default.Add,
        titleRes = R.string.home_tutorial_start_title,
        messageRes = R.string.home_tutorial_start_message,
        hintRes = R.string.home_tutorial_start_hint
    ),
    TEMPLATES(
        target = FirstSessionTutorialTarget.TEMPLATES_SCREEN,
        tab = HomeTab.TEMPLATES,
        icon = Icons.Outlined.Style,
        titleRes = R.string.home_tutorial_templates_title,
        messageRes = R.string.home_tutorial_templates_message,
        hintRes = R.string.home_tutorial_templates_hint
    ),
    JOIN_SESSION(
        target = FirstSessionTutorialTarget.JOIN_SESSION,
        tab = HomeTab.HOME,
        icon = Icons.Outlined.Groups,
        titleRes = R.string.home_tutorial_join_title,
        messageRes = R.string.home_tutorial_join_message,
        hintRes = R.string.home_tutorial_join_hint
    ),
    OTHER_PHONES(
        target = null,
        tab = HomeTab.HOME,
        icon = Icons.Outlined.PhoneAndroid,
        titleRes = R.string.home_tutorial_other_title,
        messageRes = R.string.home_tutorial_other_message,
        hintRes = R.string.home_tutorial_other_hint
    );

    val stepLabel: String
        get() = "${ordinal + 1} of ${entries.size}"

    val isFirst: Boolean
        get() = this == entries.first()

    val isFinal: Boolean
        get() = this == OTHER_PHONES

    val previous: FirstSessionTutorialStep?
        get() = entries.getOrNull(ordinal - 1)

    val next: FirstSessionTutorialStep?
        get() = entries.getOrNull(ordinal + 1)
}

@Composable
fun HomeScreen(
    onBasicModeClick: () -> Unit = {},
    onRaceModeClick: () -> Unit = {},
    onClockSyncClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSessionClick: (String) -> Unit = {},
    onRepeatSession: (RepeatSessionConfiguration) -> Unit = {},
    onTemplateClick: (Double, String, Int, String?) -> Unit = { _, _, _, _ -> },
    onPaywallClick: () -> Unit = {},
    onAthletesClick: () -> Unit = {},
    onAuthClick: () -> Unit = {},
    onStatsClick: () -> Unit = {},
    onReferralClick: () -> Unit = {},
    onWindAdjustmentClick: () -> Unit = {},
    onDistanceConverterClick: () -> Unit = {},
    onDiscountPaywallClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val isProUser by viewModel.isProUser.collectAsStateWithLifecycle()
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()
    val allSessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val sessionSummaries by viewModel.sessionSummaries.collectAsStateWithLifecycle()
    val totalSessionCount by viewModel.totalSessionCount.collectAsStateWithLifecycle()
    val quickStartPresets by viewModel.quickStartPresets.collectAsStateWithLifecycle()
    val rawUserName by viewModel.userName.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.home_default_user_name)
    val userName = rawUserName.ifEmpty { defaultName }
    val isInBillingGracePeriod by viewModel.isInBillingGracePeriod.collectAsStateWithLifecycle()
    val shouldShowHomeInviteCard by viewModel.shouldShowHomeInviteCard.collectAsStateWithLifecycle()
    val newlyEarnedReferralBonusDays by viewModel.newlyEarnedReferralBonusDays.collectAsStateWithLifecycle()
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val hasDismissedFirstSessionTutorial by viewModel.hasDismissedFirstSessionTutorial.collectAsStateWithLifecycle()
    val forceShowFirstSessionTutorial by viewModel.forceShowFirstSessionTutorial.collectAsStateWithLifecycle()
    val pendingDiscountMilestone by viewModel.pendingDiscountMilestone.collectAsStateWithLifecycle()
    val lastDiscountMilestoneFired by viewModel.lastDiscountMilestoneFired.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(HomeTab.HOME) }
    var firstSessionTutorialStep by rememberSaveable {
        mutableStateOf(FirstSessionTutorialStep.CUSTOM_SESSION)
    }
    var firstSessionTargetFrames by remember {
        mutableStateOf(emptyMap<FirstSessionTutorialTarget, Rect>())
    }
    var didTrackFirstSessionTutorialShown by rememberSaveable { mutableStateOf(false) }

    val shouldShowFirstSessionTutorial =
        forceShowFirstSessionTutorial ||
            (isProUser &&
                onboardingCompleted &&
                totalSessionCount == 0 &&
                !hasDismissedFirstSessionTutorial)

    fun setFirstSessionTutorialStep(step: FirstSessionTutorialStep) {
        firstSessionTutorialStep = step
        selectedTab = step.tab
    }

    fun completeFirstSessionTutorial(source: String) {
        viewModel.dismissFirstSessionTutorial(source)
    }

    fun advanceFirstSessionTutorial() {
        firstSessionTutorialStep.next?.let(::setFirstSessionTutorialStep)
            ?: completeFirstSessionTutorial("completed")
    }

    val recordTutorialTarget: (FirstSessionTutorialTarget, Rect) -> Unit = { target, frame ->
        if (frame.width > 0f && frame.height > 0f) {
            firstSessionTargetFrames = firstSessionTargetFrames + (target to frame)
        }
    }

    LaunchedEffect(shouldShowFirstSessionTutorial) {
        if (shouldShowFirstSessionTutorial) {
            setFirstSessionTutorialStep(FirstSessionTutorialStep.CUSTOM_SESSION)
            if (!didTrackFirstSessionTutorialShown) {
                didTrackFirstSessionTutorialShown = true
                viewModel.trackFirstSessionTutorialShown()
            }
        } else {
            didTrackFirstSessionTutorialShown = false
        }
    }

    LaunchedEffect(pendingDiscountMilestone, lastDiscountMilestoneFired, isProUser) {
        when {
            pendingDiscountMilestone <= 0 -> Unit
            isProUser || pendingDiscountMilestone <= lastDiscountMilestoneFired -> {
                viewModel.clearPendingDiscountMilestone()
            }
            else -> {
                viewModel.consumePendingDiscountMilestone(pendingDiscountMilestone)
                onDiscountPaywallClick()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        HomeTab.entries.take(2).forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                modifier = if (tab == HomeTab.TEMPLATES) {
                                    Modifier.onGloballyPositioned {
                                        recordTutorialTarget(
                                            FirstSessionTutorialTarget.TEMPLATES_SCREEN,
                                            it.boundsInRoot()
                                        )
                                    }
                                } else {
                                    Modifier
                                },
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

                        NavigationBarItem(
                            selected = false,
                            onClick = onBasicModeClick,
                            modifier = Modifier.onGloballyPositioned {
                                recordTutorialTarget(
                                    FirstSessionTutorialTarget.CUSTOM_SESSION,
                                    it.boundsInRoot()
                                )
                            },
                            icon = {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .shadow(8.dp, CircleShape)
                                        .clip(CircleShape)
                                        .background(AccentNavy),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(R.string.home_start_session),
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.Transparent
                            )
                        )

                        HomeTab.entries.drop(2).forEach { tab ->
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
                        onPresetClick = { preset ->
                            viewModel.recordPresetLaunch(preset.id)
                            onTemplateClick(
                                preset.distance,
                                preset.defaultStartType.rawValue,
                                preset.minPhones,
                                preset.id
                            )
                        },
                        onCustomSessionClick = onBasicModeClick,
                        onJoinSessionClick = onClockSyncClick,
                        onSeeAllClick = { selectedTab = HomeTab.HISTORY },
                        onSessionClick = onSessionClick,
                        onRepeatSessionClick = { session ->
                            viewModel.repeatSession(session, onRepeatSession)
                        },
                        onPaywallClick = onPaywallClick,
                        onInviteClick = {
                            viewModel.trackHomeInviteCardTapped()
                            onReferralClick()
                        },
                        onInviteCardViewed = viewModel::trackHomeInviteCardViewed,
                        onDismissInviteCard = viewModel::dismissHomeInviteCard,
                        onReferralBonusShown = viewModel::trackReferralBonusCelebrationShown,
                        onReferralBonusAction = onReferralClick,
                        onReferralBonusSeen = viewModel::acknowledgeReferralBonusCelebration,
                        recentSessions = recentSessions,
                        allSessions = allSessions,
                        sessionSummaries = sessionSummaries,
                        isProUser = isProUser,
                        shouldShowHomeInviteCard = shouldShowHomeInviteCard,
                        newlyEarnedReferralBonusDays = newlyEarnedReferralBonusDays,
                        quickStartPresets = quickStartPresets,
                        onTutorialTargetPositioned = recordTutorialTarget
                    )
                    HomeTab.TEMPLATES -> TemplatesScreen(
                        onTemplateClick = { distance, startType, minPhones, presetId ->
                            presetId?.let(viewModel::recordPresetLaunch)
                            onTemplateClick(distance, startType, minPhones, presetId)
                        },
                        modifier = Modifier.onGloballyPositioned {
                            recordTutorialTarget(
                                FirstSessionTutorialTarget.TEMPLATES_SCREEN,
                                it.boundsInRoot()
                            )
                        }
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

        if (shouldShowFirstSessionTutorial) {
            FirstSessionTutorialOverlay(
                step = firstSessionTutorialStep,
                targetFrame = firstSessionTutorialStep.target?.let(firstSessionTargetFrames::get),
                onTargetTap = ::advanceFirstSessionTutorial,
                onBack = {
                    firstSessionTutorialStep.previous?.let(::setFirstSessionTutorialStep)
                },
                onNext = ::advanceFirstSessionTutorial,
                onSkip = {
                    completeFirstSessionTutorial("skip_${firstSessionTutorialStep.name.lowercase()}")
                },
                onDone = {
                    completeFirstSessionTutorial("completed")
                },
                onStartSetup = {
                    viewModel.startFirstSessionTutorialSetup()
                    onBasicModeClick()
                }
            )
        }
    }
}

private val FirstSessionTutorialAccent = Color(0xFFF4B642)
private val FirstSessionTutorialPanel = Color(0xFF11131F)
private val FirstSessionTutorialButtonText = Color(0xFF181200)

private enum class FirstSessionBubbleArrowEdge {
    TOP,
    BOTTOM,
    NONE
}

private data class FirstSessionBubblePlacement(
    val widthPx: Float,
    val topPx: Float,
    val leftPx: Float,
    val arrowEdge: FirstSessionBubbleArrowEdge
)

@Composable
private fun FirstSessionTutorialOverlay(
    step: FirstSessionTutorialStep,
    targetFrame: Rect?,
    onTargetTap: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
    onStartSetup: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val spotlightFrame = if (step.isFinal) {
            null
        } else {
            targetFrame?.let {
                paddedTutorialFrame(
                    frame = it,
                    maxWidthPx = widthPx,
                    maxHeightPx = heightPx,
                    paddingPx = with(density) { 10.dp.toPx() },
                    boundsInsetPx = with(density) { 8.dp.toPx() }
                )
            }
        }

        FirstSessionDimLayer(
            spotlightFrame = spotlightFrame,
            widthPx = widthPx,
            heightPx = heightPx
        )

        if (step.isFinal) {
            FirstSessionFinalCard(
                onBack = onBack,
                onDone = onDone,
                onStartSetup = onStartSetup,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 368.dp)
                    .heightIn(max = maxHeight - 32.dp)
            )
        } else {
            val placement = firstSessionBubblePlacement(
                targetFrame = spotlightFrame,
                widthPx = widthPx,
                heightPx = heightPx,
                density = density
            )

            spotlightFrame?.let { frame ->
                FirstSessionSpotlight(frame = frame, onClick = onTargetTap)
            }

            FirstSessionStepBubble(
                step = step,
                onBack = onBack,
                onNext = onNext,
                onSkip = onSkip,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            placement.leftPx.roundToInt(),
                            placement.topPx.roundToInt()
                        )
                    }
                    .width(with(density) { placement.widthPx.toDp() })
            )
        }
    }
}

@Composable
private fun FirstSessionDimLayer(
    spotlightFrame: Rect?,
    widthPx: Float,
    heightPx: Float
) {
    if (spotlightFrame == null) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {}
                )
                .background(Color.Black.copy(alpha = 0.72f))
        )
        return
    }

    FirstSessionDimBlock(
        xPx = 0f,
        yPx = 0f,
        widthPx = widthPx,
        heightPx = spotlightFrame.top
    )
    FirstSessionDimBlock(
        xPx = 0f,
        yPx = spotlightFrame.bottom,
        widthPx = widthPx,
        heightPx = heightPx - spotlightFrame.bottom
    )
    FirstSessionDimBlock(
        xPx = 0f,
        yPx = spotlightFrame.top,
        widthPx = spotlightFrame.left,
        heightPx = spotlightFrame.height
    )
    FirstSessionDimBlock(
        xPx = spotlightFrame.right,
        yPx = spotlightFrame.top,
        widthPx = widthPx - spotlightFrame.right,
        heightPx = spotlightFrame.height
    )
}

@Composable
private fun FirstSessionDimBlock(
    xPx: Float,
    yPx: Float,
    widthPx: Float,
    heightPx: Float
) {
    if (widthPx <= 0f || heightPx <= 0f) return

    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
            .size(
                width = with(density) { widthPx.toDp() },
                height = with(density) { heightPx.toDp() }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
            .background(Color.Black.copy(alpha = 0.72f))
    )
}

@Composable
private fun FirstSessionSpotlight(
    frame: Rect,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    frame.left.roundToInt(),
                    frame.top.roundToInt()
                )
            }
            .size(
                width = with(density) { frame.width.toDp() },
                height = with(density) { frame.height.toDp() }
            )
            .border(
                width = 3.dp,
                color = FirstSessionTutorialAccent,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun FirstSessionStepBubble(
    step: FirstSessionTutorialStep,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .shadow(24.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(FirstSessionTutorialPanel)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = step.icon,
                contentDescription = null,
                tint = FirstSessionTutorialAccent,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(8.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = stringResource(step.titleRes),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = stringResource(step.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f)
                )
                Text(
                    text = stringResource(step.hintRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f)
                )
            }

            IconButton(
                onClick = onSkip,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.11f))
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Skip tutorial",
                    tint = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FirstSessionTutorialStep.entries.forEach { tutorialStep ->
                Box(
                    modifier = Modifier
                        .width(if (tutorialStep == step) 18.dp else 6.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (tutorialStep == step) {
                                FirstSessionTutorialAccent
                            } else {
                                Color.White.copy(alpha = 0.20f)
                            }
                        )
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = step.stepLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.56f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!step.isFirst) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = Color.White.copy(alpha = 0.86f)
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text(stringResource(R.string.common_back), fontWeight = FontWeight.SemiBold)
                }
            }

            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FirstSessionTutorialAccent,
                    contentColor = FirstSessionTutorialButtonText
                ),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .height(42.dp)
                    .weight(1f)
            ) {
                Text(stringResource(R.string.common_next), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun FirstSessionFinalCard(
    onBack: () -> Unit,
    onDone: () -> Unit,
    onStartSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .shadow(28.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(FirstSessionTutorialPanel)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = FirstSessionTutorialStep.OTHER_PHONES.icon,
                contentDescription = null,
                tint = FirstSessionTutorialAccent,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(9.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = FirstSessionTutorialStep.OTHER_PHONES.stepLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White.copy(alpha = 0.54f)
                )
                Text(
                    text = stringResource(R.string.home_tutorial_setup_each_phone),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.home_tutorial_setup_each_phone_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.74f)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FirstSessionRoleRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = stringResource(R.string.home_tutorial_host_phone),
                message = stringResource(R.string.home_tutorial_host_phone_message)
            )
            FirstSessionRoleRow(
                icon = Icons.Outlined.Groups,
                title = stringResource(R.string.home_tutorial_other_phones),
                message = stringResource(R.string.home_tutorial_other_phones_message)
            )
        }

        FirstSessionOnboardingJoinPreview()

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStartSetup,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FirstSessionTutorialAccent,
                    contentColor = FirstSessionTutorialButtonText
                ),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Setup", fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = Color.White.copy(alpha = 0.88f)
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .height(42.dp)
                        .weight(1f)
                ) {
                    Text(stringResource(R.string.common_back), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = Color.White.copy(alpha = 0.88f)
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .height(42.dp)
                        .weight(1f)
                ) {
                    Text(stringResource(R.string.race_pill_done), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun FirstSessionRoleRow(
    icon: ImageVector,
    title: String,
    message: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FirstSessionTutorialAccent,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .padding(7.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.66f)
            )
        }
    }
}

@Composable
private fun FirstSessionOnboardingJoinPreview() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            modifier = Modifier
                .width(96.dp)
                .height(190.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(BackgroundGradientBottom)
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Image(
                painter = painterResource(R.drawable.home_icon),
                contentDescription = null,
                modifier = Modifier.size(38.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(FirstSessionTutorialAccent)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .border(2.dp, FirstSessionTutorialAccent, RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.home_join_session),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Joining phones can start here",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Text(
                text = "A second phone can tap Join Session before making an account, or use Join Session later from Home.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.66f)
            )
        }
    }
}

private fun paddedTutorialFrame(
    frame: Rect,
    maxWidthPx: Float,
    maxHeightPx: Float,
    paddingPx: Float,
    boundsInsetPx: Float
): Rect {
    val left = (frame.left - paddingPx).coerceIn(boundsInsetPx, maxWidthPx - boundsInsetPx)
    val top = (frame.top - paddingPx).coerceIn(boundsInsetPx, maxHeightPx - boundsInsetPx)
    val right = (frame.right + paddingPx).coerceIn(boundsInsetPx, maxWidthPx - boundsInsetPx)
    val bottom = (frame.bottom + paddingPx).coerceIn(boundsInsetPx, maxHeightPx - boundsInsetPx)

    return if (right > left && bottom > top) {
        Rect(left, top, right, bottom)
    } else {
        Rect(maxWidthPx / 2f - 1f, maxHeightPx / 2f - 1f, maxWidthPx / 2f + 1f, maxHeightPx / 2f + 1f)
    }
}

private fun firstSessionBubblePlacement(
    targetFrame: Rect?,
    widthPx: Float,
    heightPx: Float,
    density: androidx.compose.ui.unit.Density
): FirstSessionBubblePlacement {
    val bubbleWidthPx = minOf(widthPx - with(density) { 32.dp.toPx() }, with(density) { 336.dp.toPx() })
        .coerceAtLeast(with(density) { 240.dp.toPx() })
    val estimatedHeightPx = with(density) { 230.dp.toPx() }
    val marginPx = with(density) { 16.dp.toPx() }

    if (targetFrame == null) {
        return FirstSessionBubblePlacement(
            widthPx = bubbleWidthPx,
            leftPx = (widthPx - bubbleWidthPx) / 2f,
            topPx = (heightPx - estimatedHeightPx) / 2f,
            arrowEdge = FirstSessionBubbleArrowEdge.NONE
        )
    }

    val showBelow = targetFrame.center.y < heightPx * 0.52f &&
        targetFrame.bottom + estimatedHeightPx + with(density) { 32.dp.toPx() } < heightPx

    val centerX = targetFrame.center.x
        .coerceIn(bubbleWidthPx / 2f + marginPx, widthPx - bubbleWidthPx / 2f - marginPx)
    val topPx = if (showBelow) {
        (targetFrame.bottom + with(density) { 18.dp.toPx() })
            .coerceAtMost(heightPx - estimatedHeightPx - marginPx)
    } else {
        (targetFrame.top - with(density) { 18.dp.toPx() } - estimatedHeightPx)
            .coerceAtLeast(marginPx)
    }

    return FirstSessionBubblePlacement(
        widthPx = bubbleWidthPx,
        leftPx = centerX - bubbleWidthPx / 2f,
        topPx = topPx,
        arrowEdge = if (showBelow) FirstSessionBubbleArrowEdge.TOP else FirstSessionBubbleArrowEdge.BOTTOM
    )
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
    onPresetClick: (TestPreset) -> Unit,
    onCustomSessionClick: () -> Unit,
    onJoinSessionClick: () -> Unit,
    onSeeAllClick: () -> Unit,
    onSessionClick: (String) -> Unit = {},
    onRepeatSessionClick: (TrainingSessionEntity) -> Unit = {},
    onPaywallClick: () -> Unit = {},
    onInviteClick: () -> Unit = {},
    onInviteCardViewed: () -> Unit = {},
    onDismissInviteCard: () -> Unit = {},
    onReferralBonusShown: (Int) -> Unit = {},
    onReferralBonusAction: () -> Unit = {},
    onReferralBonusSeen: () -> Unit = {},
    recentSessions: List<TrainingSessionEntity> = emptyList(),
    allSessions: List<TrainingSessionEntity> = emptyList(),
    sessionSummaries: Map<String, SessionSummary> = emptyMap(),
    isProUser: Boolean = false,
    shouldShowHomeInviteCard: Boolean = true,
    newlyEarnedReferralBonusDays: Int = 0,
    quickStartPresets: List<TestPreset> = TestPreset.featured,
    onTutorialTargetPositioned: (FirstSessionTutorialTarget, Rect) -> Unit = { _, _ -> }
) {
    var celebratedBonusDays by remember { mutableIntStateOf(0) }

    LaunchedEffect(newlyEarnedReferralBonusDays) {
        if (newlyEarnedReferralBonusDays > 0) {
            celebratedBonusDays = newlyEarnedReferralBonusDays
            onReferralBonusShown(newlyEarnedReferralBonusDays)
        }
    }

    if (celebratedBonusDays > 0) {
        AlertDialog(
            onDismissRequest = {
                celebratedBonusDays = 0
                onReferralBonusSeen()
            },
            title = {
                Text(
                    stringResource(
                        R.string.home_referral_bonus_title,
                        celebratedBonusDays
                    )
                )
            },
            text = { Text(stringResource(R.string.home_referral_bonus_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        celebratedBonusDays = 0
                        onReferralBonusSeen()
                        onReferralBonusAction()
                    }
                ) {
                    Text(stringResource(R.string.home_see_referrals))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        celebratedBonusDays = 0
                        onReferralBonusSeen()
                    }
                ) {
                    Text(stringResource(R.string.race_dismiss))
                }
            }
        )
    }

    val monthStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val monthSessions = remember(allSessions, monthStart) {
        allSessions.filter { it.date >= monthStart }
    }
    val monthRunCount = monthSessions.sumOf { sessionSummaries[it.id]?.runCount ?: 0 }
    val latestSession = recentSessions.firstOrNull()
    val recentBeforeLatest = recentSessions.drop(1).take(4)
    val personalBest = latestSession?.let { latest ->
        allSessions.asSequence()
            .filter { candidate ->
                abs(candidate.distance - latest.distance) <=
                    if (abs(latest.distance - 36.576) <= 0.5) 0.5 else 0.05
            }
            .filter { it.startType == latest.startType }
            .filter { it.numberOfPhones == latest.numberOfPhones }
            .mapNotNull { sessionSummaries[it.id]?.bestTime }
            .filter { it > 0.0 }
            .minOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            OutlinedButton(
                onClick = onJoinSessionClick,
                modifier = Modifier.onGloballyPositioned {
                    onTutorialTargetPositioned(
                        FirstSessionTutorialTarget.JOIN_SESSION,
                        it.boundsInRoot()
                    )
                },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.home_join_session),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (isInBillingGracePeriod) {
            BillingIssueBanner(
                onActionClick = onPaywallClick,
                onDismiss = {}
            )
        }

        HomeDashboardSectionTitle(stringResource(R.string.home_this_month))
        DashboardMonthlyStatsCard(
            sessionCount = monthSessions.size,
            runCount = monthRunCount,
            personalBest = personalBest,
            personalBestLabel = latestSession?.let {
                stringResource(R.string.home_distance_pr, formatDistance(it.distance))
            } ?: stringResource(R.string.home_personal_best)
        )

        HomeDashboardSectionTitle(stringResource(R.string.home_latest_session))
        if (latestSession == null) {
            DashboardEmptySessionCard(onClick = onCustomSessionClick)
        } else {
            RecentSessionCard(
                session = latestSession,
                summary = sessionSummaries[latestSession.id],
                onClick = { onSessionClick(latestSession.id) },
                onRepeat = { onRepeatSessionClick(latestSession) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HomeDashboardSectionTitle(stringResource(R.string.home_recent_sessions))
            if (recentSessions.isNotEmpty()) {
                TextButton(onClick = onSeeAllClick) {
                    Text(stringResource(R.string.home_see_all))
                }
            }
        }

        if (recentBeforeLatest.isEmpty()) {
            Text(
                text = stringResource(R.string.home_no_recent_sessions),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .gunmetalCard()
                    .padding(20.dp)
            )
        } else {
            recentBeforeLatest.forEach { session ->
                RecentSessionCard(
                    session = session,
                    summary = sessionSummaries[session.id],
                    onClick = { onSessionClick(session.id) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HomeDashboardSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = TextPrimary
    )
}

@Composable
private fun DashboardMonthlyStatsCard(
    sessionCount: Int,
    runCount: Int,
    personalBest: Double?,
    personalBestLabel: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .gunmetalCard()
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DashboardMetric(
            value = sessionCount.toString(),
            label = stringResource(R.string.home_sessions)
        )
        DashboardMetricDivider()
        DashboardMetric(
            value = runCount.toString(),
            label = stringResource(R.string.home_runs)
        )
        DashboardMetricDivider()
        DashboardMetric(
            value = personalBest?.let { String.format(Locale.getDefault(), "%.2fs", it) } ?: "—",
            label = personalBestLabel,
            highlighted = personalBest != null
        )
    }
}

@Composable
private fun RowScope.DashboardMetric(
    value: String,
    label: String,
    highlighted: Boolean = false
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = if (highlighted) SuccessGreen else TextPrimary,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DashboardMetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(38.dp)
            .background(BorderSubtle)
    )
}

@Composable
private fun DashboardEmptySessionCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gunmetalCard()
            .clickable(onClick = onClick)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(36.dp)
        )
        Text(
            text = stringResource(R.string.home_no_sessions_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary
        )
        Text(
            text = stringResource(R.string.home_no_sessions_description),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.home_start_session),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = AccentNavy
        )
    }
}

@Composable
private fun LegacyHomeContent(
    isInBillingGracePeriod: Boolean = false,
    onPresetClick: (TestPreset) -> Unit,
    onCustomSessionClick: () -> Unit,
    onJoinSessionClick: () -> Unit,
    onSeeAllClick: () -> Unit,
    onSessionClick: (String) -> Unit = {},
    onPaywallClick: () -> Unit = {},
    onInviteClick: () -> Unit = {},
    onInviteCardViewed: () -> Unit = {},
    onDismissInviteCard: () -> Unit = {},
    onReferralBonusShown: (Int) -> Unit = {},
    onReferralBonusAction: () -> Unit = {},
    onReferralBonusSeen: () -> Unit = {},
    recentSessions: List<TrainingSessionEntity> = emptyList(),
    sessionSummaries: Map<String, SessionSummary> = emptyMap(),
    isProUser: Boolean = false,
    shouldShowHomeInviteCard: Boolean = true,
    newlyEarnedReferralBonusDays: Int = 0,
    quickStartPresets: List<TestPreset> = TestPreset.featured,
    onTutorialTargetPositioned: (FirstSessionTutorialTarget, Rect) -> Unit = { _, _ -> }
) {
    var celebratedBonusDays by remember { mutableIntStateOf(0) }

    LaunchedEffect(newlyEarnedReferralBonusDays) {
        if (newlyEarnedReferralBonusDays > 0) {
            celebratedBonusDays = newlyEarnedReferralBonusDays
            onReferralBonusShown(newlyEarnedReferralBonusDays)
        }
    }

    if (celebratedBonusDays > 0) {
        AlertDialog(
            onDismissRequest = {
                celebratedBonusDays = 0
                onReferralBonusSeen()
            },
            title = { Text("You earned $celebratedBonusDays days of Pro!") },
            text = { Text("A friend signed up with your code. Enjoy Pro on us.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        celebratedBonusDays = 0
                        onReferralBonusSeen()
                        onReferralBonusAction()
                    }
                ) {
                    Text("See referrals")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        celebratedBonusDays = 0
                        onReferralBonusSeen()
                    }
                ) {
                    Text(stringResource(R.string.race_dismiss))
                }
            }
        )
    }

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
                text = stringResource(R.string.app_name),
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

        if (shouldShowHomeInviteCard) {
            HomeInviteCard(
                onClick = onInviteClick,
                onViewed = onInviteCardViewed,
                onDismiss = onDismissInviteCard
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

        quickStartPresets.chunked(2).forEachIndexed { rowIndex, rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowPresets.forEach { preset ->
                    PresetModeCard(
                        preset = preset,
                        onClick = { onPresetClick(preset) },
                        requiresPro = !preset.isSinglePhone && !isProUser,
                        isProUser = isProUser,
                        onPaywallClick = onPaywallClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowPresets.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            if (rowIndex < quickStartPresets.lastIndex / 2) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Custom Session card (full-width) — iOS: primaryAccent colored circle
        FullWidthActionCard(
            title = stringResource(R.string.home_custom_session),
            subtitle = stringResource(R.string.home_custom_session_subtitle),
            icon = Icons.Outlined.Tune,
            iconColor = AccentNavy,
            onClick = onCustomSessionClick,
            modifier = Modifier.onGloballyPositioned {
                onTutorialTargetPositioned(
                    FirstSessionTutorialTarget.CUSTOM_SESSION,
                    it.boundsInRoot()
                )
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Join Session card (full-width) — iOS: secondary (green) colored circle
        FullWidthActionCard(
            title = stringResource(R.string.home_join_session),
            subtitle = stringResource(R.string.home_join_session_subtitle),
            icon = Icons.Outlined.Groups,
            iconColor = AccentGreen,
            onClick = onJoinSessionClick,
            modifier = Modifier.onGloballyPositioned {
                onTutorialTargetPositioned(
                    FirstSessionTutorialTarget.JOIN_SESSION,
                    it.boundsInRoot()
                )
            }
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

@Composable
private fun HomeInviteCard(
    onClick: () -> Unit,
    onViewed: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppColors.current

    LaunchedEffect(Unit) {
        onViewed()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.EmojiEvents,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(28.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Invite & earn rewards",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Give a friend 30 days of Pro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Dismiss invite row",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── Preset card (matching iOS PresetCardButton) ───────────────────────────────

@Composable
private fun TestPreset.accentColor(): Color = when (category) {
    TestPresetCategory.ACCELERATION -> Color(0xFF30D158)
    TestPresetCategory.MAX_SPEED -> Color(0xFFFF9500)
    TestPresetCategory.AGILITY -> Color(0xFF30D158)
    TestPresetCategory.COMBINE -> AccentNavy
}

private fun TestPreset.icon(): ImageVector = when (iconKey) {
    "bolt" -> Icons.Outlined.ElectricBolt
    "pole-vault" -> Icons.Outlined.NorthEast
    "sportscourt" -> Icons.Outlined.EmojiEvents
    "swap", "triangle" -> Icons.Outlined.SwapHoriz
    "repeat" -> Icons.Outlined.Sync
    "figure.run" -> Icons.AutoMirrored.Outlined.DirectionsRun
    else -> Icons.Outlined.RocketLaunch
}

private fun TestPreset.phoneLabel(): String = when {
    minPhones == 1 && maxPhones == 1 -> "Solo"
    minPhones == maxPhones -> "${minPhones}P"
    else -> "$minPhones-${maxPhones}P"
}

@Composable
private fun PresetModeCard(
    preset: TestPreset,
    onClick: () -> Unit,
    requiresPro: Boolean = false,
    isProUser: Boolean = false,
    onPaywallClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(20.dp)
    val colors = LocalAppColors.current
    val iconColor = preset.accentColor()

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
                        imageVector = preset.icon(),
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (preset.distance > 0.0) {
                        Text(
                            text = preset.shortDistance,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = preset.phoneLabel(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                }
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
            text = stringResource(R.string.common_pro_badge),
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

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun RecentSessionCard(
    session: TrainingSessionEntity,
    summary: SessionSummary?,
    onClick: () -> Unit,
    onRepeat: (() -> Unit)? = null
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()) }
    val distanceLabel = formatDistance(session.distance)
    val modeLabel = formatSessionMode(session.numberOfPhones, session.numberOfGates)

    // Load thumbnail off the main thread (thumbnails stored at thumbnails/{sessionId}/lap_1.jpg)
    val context = LocalContext.current
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, session.id) {
        val loadedThumbnail = withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                // Try session thumbnail first, then fall back to first run's thumbnail
                val sessionPath = session.thumbnailPath
                val fromSession = sessionPath?.let { BitmapFactory.decodeFile(it, opts) }
                if (fromSession != null) {
                    fromSession.asImageBitmap()
                } else {
                    // Look for first run thumbnail in the session directory
                    val dir = File(context.filesDir, "thumbnails/${session.id}")
                    val firstLap = dir.listFiles()
                        ?.filter { it.extension == "jpg" }
                        ?.minByOrNull { it.name }
                    firstLap?.let { BitmapFactory.decodeFile(it.absolutePath, opts)?.asImageBitmap() }
                }
            } catch (_: Exception) { null }
        }
        value = loadedThumbnail
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
                val loadedThumbnail = thumbnail
                if (loadedThumbnail != null) {
                    Image(
                        bitmap = loadedThumbnail,
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

                    MetadataPill(text = modeLabel, icon = "\uD83D\uDCF1") // phone

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

                if (onRepeat != null) {
                    Text(
                        text = stringResource(R.string.home_repeat_setup),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = AccentNavy,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onRepeat)
                            .padding(vertical = 4.dp)
                    )
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
