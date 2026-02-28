package com.trackspeed.android.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.notifications.NotificationService
import com.trackspeed.android.notifications.NotificationTiming
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Notification Settings screen.
 */
data class NotificationSettingsUiState(
    val tryProReminderEnabled: Boolean = SettingsRepository.Defaults.TRY_PRO_REMINDER_ENABLED,
    val trainingReminderEnabled: Boolean = SettingsRepository.Defaults.TRAINING_REMINDER_ENABLED,
    val ratingPromptEnabled: Boolean = SettingsRepository.Defaults.RATING_PROMPT_ENABLED,
    val hasNotificationPermission: Boolean = false,
    val testNotificationScheduled: Boolean = false
)

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val notificationService: NotificationService
) : ViewModel() {

    private val _testNotificationScheduled = MutableStateFlow(false)

    val uiState: StateFlow<NotificationSettingsUiState> = combine(
        settingsRepository.tryProReminderEnabled,
        settingsRepository.trainingReminderEnabled,
        settingsRepository.ratingPromptEnabled,
        _testNotificationScheduled
    ) { tryPro, training, rating, testScheduled ->
        NotificationSettingsUiState(
            tryProReminderEnabled = tryPro,
            trainingReminderEnabled = training,
            ratingPromptEnabled = rating,
            hasNotificationPermission = checkNotificationPermission(),
            testNotificationScheduled = testScheduled
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotificationSettingsUiState(
            hasNotificationPermission = checkNotificationPermission()
        )
    )

    /**
     * Toggle the Try Pro Reminder setting.
     * When enabled, schedules the notification. When disabled, cancels it.
     */
    fun setTryProReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTryProReminderEnabled(enabled)
            if (enabled) {
                notificationService.scheduleTryProReminder()
            } else {
                notificationService.cancelTryProReminder()
            }
        }
    }

    /**
     * Toggle the Training Reminder setting.
     * When enabled, schedules the inactivity notification. When disabled, cancels it.
     */
    fun setTrainingReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTrainingReminderEnabled(enabled)
            if (enabled) {
                notificationService.scheduleTrainingReminder()
            } else {
                notificationService.cancelTrainingReminder()
            }
        }
    }

    /**
     * Toggle the Rating Prompt setting.
     * When disabled, cancels any pending rating prompt.
     */
    fun setRatingPromptEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRatingPromptEnabled(enabled)
            if (!enabled) {
                notificationService.cancelRatingPrompt()
            }
        }
    }

    /**
     * Fire a test notification in 5 seconds.
     */
    fun sendTestNotification() {
        notificationService.scheduleTestNotification()
        _testNotificationScheduled.value = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(6000L)
            _testNotificationScheduled.value = false
        }
    }

    /**
     * Refresh permission state (call after returning from system settings or permission dialog).
     */
    fun refreshPermissionState() {
        // The combine flow will pick up the new value on next emission.
        // Force a re-emission by toggling the test state.
        val current = _testNotificationScheduled.value
        _testNotificationScheduled.value = !current
        _testNotificationScheduled.value = current
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
