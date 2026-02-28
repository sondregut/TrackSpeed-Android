package com.trackspeed.android.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification identifiers matching iOS NotificationIdentifier.
 */
object NotificationIds {
    const val TRY_PRO_REMINDER = "com.trackspeed.tryProReminder"
    const val TRAINING_REMINDER = "com.trackspeed.trainingReminder"
    const val RATING_PROMPT = "com.trackspeed.ratingPrompt"
    const val TEST_NOTIFICATION = "com.trackspeed.testNotification"

    // Request codes for PendingIntent (must be unique ints)
    const val TRY_PRO_REQUEST_CODE = 1001
    const val TRAINING_REMINDER_REQUEST_CODE = 1002
    const val RATING_PROMPT_REQUEST_CODE = 1003
    const val TEST_REQUEST_CODE = 1099
}

/**
 * Notification timing constants matching iOS behavior.
 */
object NotificationTiming {
    /** Days after install before showing Try Pro reminder */
    const val TRY_PRO_DELAY_DAYS = 14

    /** Days of inactivity before sending training reminder */
    const val INACTIVITY_THRESHOLD_DAYS = 7

    /** Number of completed sessions before showing rating prompt */
    const val RATING_PROMPT_SESSION_COUNT = 5

    /** Delay in seconds for test notification */
    const val TEST_DELAY_SECONDS = 5L
}

/**
 * Service for scheduling and managing local notifications.
 *
 * Uses AlarmManager for precise scheduling of notifications when the app
 * is not running. Notification preferences are stored via DataStore in
 * SettingsRepository.
 */
@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "NotificationService"
        const val CHANNEL_ID = "trackspeed_reminders"
        private const val CHANNEL_NAME = "TrackSpeed Reminders"
        private const val CHANNEL_DESCRIPTION = "Training reminders and tips"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Create the notification channel. Must be called on app startup (API 26+).
     */
    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION
            enableVibration(true)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel '$CHANNEL_ID' created")
    }

    /**
     * Check if notification permission is granted (Android 13+).
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Pre-Android 13 doesn't require runtime permission
        }
    }

    // ------------------------------------------------------------------
    // Try Pro Reminder
    // ------------------------------------------------------------------

    /**
     * Schedule "Try Pro" reminder 14 days after install for non-subscribers.
     * Title: "Ready to unlock your full potential?"
     * Body: "Try TrackSpeed Pro free for 7 days - precision timing, 2-phone sync, and more."
     */
    suspend fun scheduleTryProReminder() {
        val enabled = settingsRepository.tryProReminderEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Try Pro reminder disabled in settings")
            return
        }

        cancelTryProReminder()

        val delayMillis = NotificationTiming.TRY_PRO_DELAY_DAYS * 24L * 60 * 60 * 1000
        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMillis

        val intent = createAlarmIntent(
            notificationType = NotificationIds.TRY_PRO_REMINDER,
            title = "Ready to unlock your full potential?",
            body = "Try TrackSpeed Pro free for 7 days - precision timing, 2-phone sync, and more."
        )

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NotificationIds.TRY_PRO_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(triggerAtMillis, pendingIntent)
        Log.d(TAG, "Scheduled Try Pro reminder for ${NotificationTiming.TRY_PRO_DELAY_DAYS} days from now")
    }

    /**
     * Cancel the Try Pro reminder. Call when user subscribes.
     */
    fun cancelTryProReminder() {
        cancelAlarm(NotificationIds.TRY_PRO_REQUEST_CODE)
        Log.d(TAG, "Cancelled Try Pro reminder")
    }

    // ------------------------------------------------------------------
    // Training Reminder (Inactivity)
    // ------------------------------------------------------------------

    /**
     * Schedule training reminder that fires after 7 days of inactivity.
     * Should be rescheduled on each session completion to push the reminder forward.
     * Title: "Ready to Train?"
     * Body: "It's been a while! Time to hit the track?"
     */
    suspend fun scheduleTrainingReminder() {
        val enabled = settingsRepository.trainingReminderEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Training reminder disabled in settings")
            return
        }

        cancelTrainingReminder()

        val delayMillis = NotificationTiming.INACTIVITY_THRESHOLD_DAYS * 24L * 60 * 60 * 1000
        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMillis

        val intent = createAlarmIntent(
            notificationType = NotificationIds.TRAINING_REMINDER,
            title = "Ready to Train?",
            body = "It's been a while! Time to hit the track?"
        )

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NotificationIds.TRAINING_REMINDER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(triggerAtMillis, pendingIntent)
        Log.d(TAG, "Scheduled training reminder for ${NotificationTiming.INACTIVITY_THRESHOLD_DAYS} days from now")
    }

    /**
     * Cancel the training reminder. Call when user starts a session
     * (then reschedule after session completion).
     */
    fun cancelTrainingReminder() {
        cancelAlarm(NotificationIds.TRAINING_REMINDER_REQUEST_CODE)
        Log.d(TAG, "Cancelled training reminder")
    }

    // ------------------------------------------------------------------
    // Rating Prompt
    // ------------------------------------------------------------------

    /**
     * Schedule a rating prompt notification after the 5th completed session.
     * This fires 2 hours after the qualifying session to avoid interrupting the user.
     * Title: "Enjoying TrackSpeed?"
     * Body: "You've completed 5 sessions! Take a moment to rate the app."
     */
    suspend fun scheduleRatingPrompt() {
        val enabled = settingsRepository.ratingPromptEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Rating prompt disabled in settings")
            return
        }

        cancelRatingPrompt()

        // Fire 2 hours after the qualifying session
        val delayMillis = 2L * 60 * 60 * 1000
        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMillis

        val intent = createAlarmIntent(
            notificationType = NotificationIds.RATING_PROMPT,
            title = "Enjoying TrackSpeed?",
            body = "You've completed 5 sessions! Take a moment to rate the app."
        )

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NotificationIds.RATING_PROMPT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(triggerAtMillis, pendingIntent)
        Log.d(TAG, "Scheduled rating prompt for 2 hours from now")
    }

    /**
     * Cancel the rating prompt.
     */
    fun cancelRatingPrompt() {
        cancelAlarm(NotificationIds.RATING_PROMPT_REQUEST_CODE)
        Log.d(TAG, "Cancelled rating prompt")
    }

    // ------------------------------------------------------------------
    // Test Notification
    // ------------------------------------------------------------------

    /**
     * Schedule a test notification that fires in 5 seconds.
     * Used from the Notification Settings screen for testing.
     */
    fun scheduleTestNotification() {
        cancelAlarm(NotificationIds.TEST_REQUEST_CODE)

        val delayMillis = NotificationTiming.TEST_DELAY_SECONDS * 1000
        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMillis

        val intent = createAlarmIntent(
            notificationType = NotificationIds.TEST_NOTIFICATION,
            title = "TrackSpeed Test",
            body = "Notifications are working correctly!"
        )

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NotificationIds.TEST_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(triggerAtMillis, pendingIntent)
        Log.d(TAG, "Scheduled test notification for ${NotificationTiming.TEST_DELAY_SECONDS} seconds from now")
    }

    // ------------------------------------------------------------------
    // Cancel All
    // ------------------------------------------------------------------

    /**
     * Cancel all scheduled notifications.
     */
    fun cancelAllNotifications() {
        cancelTryProReminder()
        cancelTrainingReminder()
        cancelRatingPrompt()
        cancelAlarm(NotificationIds.TEST_REQUEST_CODE)
        notificationManager.cancelAll()
        Log.d(TAG, "Cancelled all scheduled notifications")
    }

    // ------------------------------------------------------------------
    // Private Helpers
    // ------------------------------------------------------------------

    private fun createAlarmIntent(
        notificationType: String,
        title: String,
        body: String
    ): Intent {
        return Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_SHOW_NOTIFICATION
            putExtra(NotificationReceiver.EXTRA_NOTIFICATION_TYPE, notificationType)
            putExtra(NotificationReceiver.EXTRA_TITLE, title)
            putExtra(NotificationReceiver.EXTRA_BODY, body)
        }
    }

    private fun scheduleAlarm(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        try {
            // Use setAndAllowWhileIdle for reliable delivery even in Doze mode.
            // We use ELAPSED_REALTIME_WAKEUP so the device wakes to deliver.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            // On Android 12+ exact alarms may require SCHEDULE_EXACT_ALARM permission.
            // Fall back to inexact alarm which is fine for notifications with day-scale delays.
            Log.w(TAG, "Exact alarm not permitted, using inexact: ${e.message}")
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    private fun cancelAlarm(requestCode: Int) {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_SHOW_NOTIFICATION
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }
}
