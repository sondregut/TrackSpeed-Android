package com.trackspeed.android.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.notifications.NotificationTiming
import com.trackspeed.android.ui.theme.TrackSpeedTheme

// Colors matching the app's dark theme (from SettingsScreen.kt)
private val ScreenBackground = Color(0xFF000000)
private val CardBackground = Color(0xFF1C1C1E)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF8E8E93)
private val TextTertiary = Color(0xFF636366)
private val DividerColor = Color(0xFF38383A)
private val AccentBlue = Color(0xFF0A84FF)
private val AccentGreen = Color(0xFF00E676)
private val AccentOrange = Color(0xFFFF9F0A)
private val WarningYellow = Color(0xFFFFD60A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission launcher for Android 13+
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissionState()
    }

    NotificationSettingsContent(
        state = state,
        onBack = onBack,
        onTryProReminderChanged = { enabled ->
            if (enabled && !state.hasNotificationPermission) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                viewModel.setTryProReminderEnabled(enabled)
            }
        },
        onTrainingReminderChanged = { enabled ->
            if (enabled && !state.hasNotificationPermission) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                viewModel.setTrainingReminderEnabled(enabled)
            }
        },
        onRatingPromptChanged = { enabled ->
            if (enabled && !state.hasNotificationPermission) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                viewModel.setRatingPromptEnabled(enabled)
            }
        },
        onTestNotification = {
            if (!state.hasNotificationPermission) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                viewModel.sendTestNotification()
            }
        },
        onOpenSettings = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationSettingsContent(
    state: NotificationSettingsUiState,
    onBack: () -> Unit,
    onTryProReminderChanged: (Boolean) -> Unit,
    onTrainingReminderChanged: (Boolean) -> Unit,
    onRatingPromptChanged: (Boolean) -> Unit,
    onTestNotification: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.notification_settings_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = AccentBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ScreenBackground
                )
            )
        },
        containerColor = ScreenBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Permission warning banner
            if (!state.hasNotificationPermission) {
                PermissionWarningBanner(onOpenSettings = onOpenSettings)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // System Notifications Section
            SectionHeader(stringResource(R.string.notification_settings_section_system))
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    // Try Pro Reminder
                    NotificationToggleRow(
                        icon = Icons.Outlined.Star,
                        iconTint = AccentGreen,
                        title = stringResource(R.string.notification_settings_try_pro_title),
                        description = stringResource(R.string.notification_settings_try_pro_description, NotificationTiming.TRY_PRO_DELAY_DAYS),
                        checked = state.tryProReminderEnabled,
                        onCheckedChange = onTryProReminderChanged
                    )

                    HorizontalDivider(
                        color = DividerColor,
                        modifier = Modifier.padding(start = 56.dp)
                    )

                    // Training Reminder
                    NotificationToggleRow(
                        icon = Icons.Outlined.FitnessCenter,
                        iconTint = AccentOrange,
                        title = stringResource(R.string.notification_settings_training_title),
                        description = stringResource(R.string.notification_settings_training_description, NotificationTiming.INACTIVITY_THRESHOLD_DAYS),
                        checked = state.trainingReminderEnabled,
                        onCheckedChange = onTrainingReminderChanged
                    )

                    HorizontalDivider(
                        color = DividerColor,
                        modifier = Modifier.padding(start = 56.dp)
                    )

                    // Rating Prompt
                    NotificationToggleRow(
                        icon = Icons.Outlined.ThumbUp,
                        iconTint = AccentBlue,
                        title = stringResource(R.string.notification_settings_rating_title),
                        description = stringResource(R.string.notification_settings_rating_description, NotificationTiming.RATING_PROMPT_SESSION_COUNT),
                        checked = state.ratingPromptEnabled,
                        onCheckedChange = onRatingPromptChanged
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // In-App Notifications Info Section
            SectionHeader(stringResource(R.string.notification_settings_section_in_app))
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InfoRow(
                        icon = Icons.Outlined.Notifications,
                        iconTint = AccentGreen,
                        title = stringResource(R.string.notification_settings_personal_bests_title),
                        description = stringResource(R.string.notification_settings_personal_bests_description)
                    )

                    InfoRow(
                        icon = Icons.Outlined.Notifications,
                        iconTint = AccentOrange,
                        title = stringResource(R.string.notification_settings_session_summary_title),
                        description = stringResource(R.string.notification_settings_session_summary_description)
                    )
                }
            }

            Text(
                text = stringResource(R.string.notification_settings_in_app_footer),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Test Notification Section
            SectionHeader(stringResource(R.string.notification_settings_section_test))
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.notification_settings_test_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onTestNotification,
                        enabled = !state.testNotificationScheduled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = Color.White,
                            disabledContainerColor = AccentBlue.copy(alpha = 0.4f),
                            disabledContentColor = Color.White.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (state.testNotificationScheduled) {
                                stringResource(R.string.notification_settings_test_sent)
                            } else {
                                stringResource(R.string.notification_settings_test_button)
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PermissionWarningBanner(onOpenSettings: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WarningYellow.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "!",
                color = WarningYellow,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(WarningYellow.copy(alpha = 0.2f))
                    .wrapContentSize(Alignment.Center)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.notification_settings_permission_disabled_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.notification_settings_permission_disabled_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            TextButton(onClick = onOpenSettings) {
                Text(
                    text = stringResource(R.string.notification_settings_open_settings),
                    color = AccentBlue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun NotificationToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = TextTertiary,
                uncheckedBorderColor = TextTertiary
            )
        )
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NotificationSettingsPreview() {
    TrackSpeedTheme(darkTheme = true) {
        NotificationSettingsContent(
            state = NotificationSettingsUiState(
                tryProReminderEnabled = true,
                trainingReminderEnabled = true,
                ratingPromptEnabled = false,
                hasNotificationPermission = true,
                testNotificationScheduled = false
            ),
            onBack = {},
            onTryProReminderChanged = {},
            onTrainingReminderChanged = {},
            onRatingPromptChanged = {},
            onTestNotification = {},
            onOpenSettings = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NotificationSettingsNoPermissionPreview() {
    TrackSpeedTheme(darkTheme = true) {
        NotificationSettingsContent(
            state = NotificationSettingsUiState(
                hasNotificationPermission = false
            ),
            onBack = {},
            onTryProReminderChanged = {},
            onTrainingReminderChanged = {},
            onRatingPromptChanged = {},
            onTestNotification = {},
            onOpenSettings = {}
        )
    }
}
