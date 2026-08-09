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
import com.trackspeed.android.R
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification identifiers matching iOS NotificationIdentifier.
 */
object NotificationIds {
    const val TRY_PRO_REMINDER = "com.trackspeed.tryProReminder"
    const val TRAINING_REMINDER = "com.trackspeed.trainingReminder"
    const val BILLING_ISSUE_REMINDER = "com.trackspeed.billingIssue"
    const val PROMO_OFFER_REMINDER = "com.trackspeed.promoOffer"
    const val FIRST_SESSION_NUDGE = "com.trackspeed.firstSessionNudge"
    const val MILESTONE_NUDGE = "com.trackspeed.milestoneNudge"
    const val DAY_14_FOLLOW_UP = "com.trackspeed.day14FollowUp"
    const val DAY_30_FINAL_NUDGE = "com.trackspeed.day30FinalNudge"
    const val TRIAL_ENDING_REMINDER = "com.trackspeed.trialEnding"
    const val RATING_PROMPT = "com.trackspeed.ratingPrompt"
    const val TEST_NOTIFICATION = "com.trackspeed.testNotification"

    // Request codes for PendingIntent (must be unique ints)
    const val TRY_PRO_REQUEST_CODE = 1001
    const val TRAINING_REMINDER_REQUEST_CODE = 1002
    const val BILLING_ISSUE_REQUEST_CODE = 1003
    const val PROMO_OFFER_REQUEST_CODE = 1004
    const val FIRST_SESSION_NUDGE_REQUEST_CODE = 1005
    const val MILESTONE_NUDGE_REQUEST_CODE = 1006
    const val DAY_14_FOLLOW_UP_REQUEST_CODE = 1007
    const val DAY_30_FINAL_NUDGE_REQUEST_CODE = 1008
    const val TRIAL_ENDING_REQUEST_CODE = 1009
    const val RATING_PROMPT_REQUEST_CODE = 1010
    const val TEST_REQUEST_CODE = 1099
}

/**
 * Notification timing constants matching iOS behavior.
 */
object NotificationTiming {
    /** Days after signup before showing the value-first Try Pro reminder */
    const val TRY_PRO_DELAY_DAYS = 3

    /** Days of inactivity before sending training reminder */
    const val INACTIVITY_THRESHOLD_DAYS = 7

    /** Days after install before the promotional offer reminder */
    const val PROMO_OFFER_DELAY_DAYS = 7

    /** Number of completed sessions before showing rating prompt */
    const val RATING_PROMPT_SESSION_COUNT = 3

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
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_description)
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
     * Schedule the value-first "Try Pro" reminder 3 days after signup.
     * Copy matches iOS LocalNotificationService.
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
            title = context.getString(R.string.notification_try_pro_title),
            body = context.getString(R.string.notification_try_pro_body)
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
     * Body: "It's been a while - start a new timing session!"
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
            title = context.getString(R.string.notification_training_title),
            body = context.getString(R.string.notification_training_body)
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
    // Billing Issue Reminder
    // ------------------------------------------------------------------

    suspend fun scheduleBillingIssueReminder(gracePeriodExpiresAtMillis: Long) {
        val enabled = settingsRepository.billingIssueReminderEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Billing issue reminder disabled in settings")
            return
        }

        val fireAtMillis = gracePeriodExpiresAtMillis - TimeUnit.DAYS.toMillis(2)
        if (fireAtMillis <= System.currentTimeMillis()) {
            Log.d(TAG, "Grace period expires too soon, not scheduling billing reminder")
            return
        }

        scheduleNotificationAtWallClock(
            requestCode = NotificationIds.BILLING_ISSUE_REQUEST_CODE,
            notificationType = NotificationIds.BILLING_ISSUE_REMINDER,
            title = context.getString(R.string.notification_billing_title),
            body = context.getString(R.string.notification_billing_body),
            fireAtMillis = fireAtMillis
        )
    }

    fun cancelBillingIssueReminder() {
        cancelAlarm(NotificationIds.BILLING_ISSUE_REQUEST_CODE)
        Log.d(TAG, "Cancelled billing issue reminder")
    }

    // ------------------------------------------------------------------
    // Trial-End Reminder
    // ------------------------------------------------------------------

    fun scheduleTrialEndReminder(trialEndsAtMillis: Long) {
        val fireAtMillis = trialEndsAtMillis - TimeUnit.DAYS.toMillis(2)
        if (fireAtMillis <= System.currentTimeMillis()) {
            Log.d(TAG, "Trial ends too soon, not scheduling trial-end reminder")
            return
        }

        scheduleNotificationAtWallClock(
            requestCode = NotificationIds.TRIAL_ENDING_REQUEST_CODE,
            notificationType = NotificationIds.TRIAL_ENDING_REMINDER,
            title = context.getString(R.string.notification_trial_title),
            body = context.getString(R.string.notification_trial_body),
            fireAtMillis = fireAtMillis
        )
    }

    fun cancelTrialEndReminder() {
        cancelAlarm(NotificationIds.TRIAL_ENDING_REQUEST_CODE)
        Log.d(TAG, "Cancelled trial-end reminder")
    }

    // ------------------------------------------------------------------
    // Promotional Offer Reminder
    // ------------------------------------------------------------------

    suspend fun schedulePromoOfferReminder() {
        val enabled = settingsRepository.promoOfferReminderEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Promo offer reminder disabled in settings")
            return
        }

        scheduleNotificationAfter(
            requestCode = NotificationIds.PROMO_OFFER_REQUEST_CODE,
            notificationType = NotificationIds.PROMO_OFFER_REMINDER,
            title = context.getString(R.string.notification_promo_title),
            body = context.getString(R.string.notification_promo_body),
            delayMillis = TimeUnit.DAYS.toMillis(NotificationTiming.PROMO_OFFER_DELAY_DAYS.toLong())
        )
    }

    fun cancelPromoOfferReminder() {
        cancelAlarm(NotificationIds.PROMO_OFFER_REQUEST_CODE)
        Log.d(TAG, "Cancelled promo offer reminder")
    }

    // ------------------------------------------------------------------
    // Conversion Nudges
    // ------------------------------------------------------------------

    suspend fun scheduleFirstSessionNudge() {
        val enabled = settingsRepository.tryProReminderEnabled.first()
        if (!enabled) {
            Log.d(TAG, "First session nudge disabled")
            return
        }

        scheduleNotificationAfter(
            requestCode = NotificationIds.FIRST_SESSION_NUDGE_REQUEST_CODE,
            notificationType = NotificationIds.FIRST_SESSION_NUDGE,
            title = context.getString(R.string.notification_first_session_title),
            body = context.getString(R.string.notification_first_session_body),
            delayMillis = TimeUnit.HOURS.toMillis(2)
        )
    }

    fun cancelFirstSessionNudge() {
        cancelAlarm(NotificationIds.FIRST_SESSION_NUDGE_REQUEST_CODE)
        Log.d(TAG, "Cancelled first session nudge")
    }

    suspend fun scheduleMilestoneNudge() {
        val enabled = settingsRepository.tryProReminderEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Milestone nudge disabled")
            return
        }

        scheduleNotificationAfter(
            requestCode = NotificationIds.MILESTONE_NUDGE_REQUEST_CODE,
            notificationType = NotificationIds.MILESTONE_NUDGE,
            title = context.getString(R.string.notification_milestone_title),
            body = context.getString(R.string.notification_milestone_body),
            delayMillis = TimeUnit.DAYS.toMillis(1)
        )
    }

    fun cancelMilestoneNudge() {
        cancelAlarm(NotificationIds.MILESTONE_NUDGE_REQUEST_CODE)
        Log.d(TAG, "Cancelled milestone nudge")
    }

    suspend fun scheduleDay14FollowUp() {
        val enabled = settingsRepository.promoOfferReminderEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Day 14 follow-up disabled")
            return
        }

        val fireAtMillis = installTimeMillis() + TimeUnit.DAYS.toMillis(14)
        if (fireAtMillis <= System.currentTimeMillis()) {
            Log.d(TAG, "Day 14 already passed, skipping follow-up")
            return
        }

        scheduleNotificationAtWallClock(
            requestCode = NotificationIds.DAY_14_FOLLOW_UP_REQUEST_CODE,
            notificationType = NotificationIds.DAY_14_FOLLOW_UP,
            title = context.getString(R.string.notification_day_14_title),
            body = context.getString(R.string.notification_day_14_body),
            fireAtMillis = fireAtMillis
        )
    }

    fun cancelDay14FollowUp() {
        cancelAlarm(NotificationIds.DAY_14_FOLLOW_UP_REQUEST_CODE)
        Log.d(TAG, "Cancelled day 14 follow-up")
    }

    suspend fun scheduleDay30FinalNudge() {
        val enabled = settingsRepository.promoOfferReminderEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Day 30 final nudge disabled")
            return
        }

        val fireAtMillis = installTimeMillis() + TimeUnit.DAYS.toMillis(30)
        if (fireAtMillis <= System.currentTimeMillis()) {
            Log.d(TAG, "Day 30 already passed, skipping final nudge")
            return
        }

        scheduleNotificationAtWallClock(
            requestCode = NotificationIds.DAY_30_FINAL_NUDGE_REQUEST_CODE,
            notificationType = NotificationIds.DAY_30_FINAL_NUDGE,
            title = context.getString(R.string.notification_day_30_title),
            body = context.getString(R.string.notification_day_30_body),
            fireAtMillis = fireAtMillis
        )
    }

    fun cancelDay30FinalNudge() {
        cancelAlarm(NotificationIds.DAY_30_FINAL_NUDGE_REQUEST_CODE)
        Log.d(TAG, "Cancelled day 30 final nudge")
    }

    suspend fun scheduleStartupReminders(
        isProUser: Boolean,
        completedSessionCount: Int
    ) {
        if (isProUser) {
            cancelConversionNotifications()
            return
        }

        if (!hasPendingAlarm(NotificationIds.TRY_PRO_REQUEST_CODE)) {
            scheduleTryProReminder()
        }

        scheduleTrainingReminder()

        if (!hasPendingAlarm(NotificationIds.PROMO_OFFER_REQUEST_CODE)) {
            schedulePromoOfferReminder()
        }

        if (completedSessionCount >= 1 &&
            !hasPendingAlarm(NotificationIds.DAY_14_FOLLOW_UP_REQUEST_CODE)
        ) {
            scheduleDay14FollowUp()
        }

        // iOS intentionally does not schedule the day-30 final nudge on startup.
        cancelDay30FinalNudge()
    }

    fun cancelConversionNotifications() {
        cancelTryProReminder()
        cancelPromoOfferReminder()
        cancelFirstSessionNudge()
        cancelMilestoneNudge()
        cancelDay14FollowUp()
        cancelDay30FinalNudge()
    }

    // ------------------------------------------------------------------
    // Rating Prompt
    // ------------------------------------------------------------------

    /**
     * Schedule a rating prompt notification after the 3rd completed session.
     * This fires 2 hours after the qualifying session to avoid interrupting the user.
     * Title: "Enjoying TrackSpeed?"
     * Body: "You've completed 3 sessions! Take a moment to rate the app."
     */
    suspend fun scheduleRatingPrompt() {
        val enabled = settingsRepository.ratingPromptEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Rating prompt disabled in settings")
            return
        }
        if (settingsRepository.hasBeenAskedForReview.first()) {
            Log.d(TAG, "Rating prompt already shown")
            return
        }

        cancelRatingPrompt()

        // Fire 2 hours after the qualifying session
        val delayMillis = 2L * 60 * 60 * 1000
        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMillis

        val intent = createAlarmIntent(
            notificationType = NotificationIds.RATING_PROMPT,
            title = context.getString(R.string.notification_rating_title),
            body = context.getString(R.string.notification_rating_body)
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
            title = context.getString(R.string.notification_test_title),
            body = context.getString(R.string.notification_test_body)
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
        cancelBillingIssueReminder()
        cancelPromoOfferReminder()
        cancelFirstSessionNudge()
        cancelMilestoneNudge()
        cancelDay14FollowUp()
        cancelDay30FinalNudge()
        cancelTrialEndReminder()
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

    private fun scheduleNotificationAfter(
        requestCode: Int,
        notificationType: String,
        title: String,
        body: String,
        delayMillis: Long
    ) {
        cancelAlarm(requestCode)
        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMillis
        val intent = createAlarmIntent(notificationType, title, body)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleAlarm(triggerAtMillis, pendingIntent)
        Log.d(TAG, "Scheduled $notificationType in ${delayMillis / 1000}s")
    }

    private fun scheduleNotificationAtWallClock(
        requestCode: Int,
        notificationType: String,
        title: String,
        body: String,
        fireAtMillis: Long
    ) {
        val delayMillis = (fireAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        scheduleNotificationAfter(requestCode, notificationType, title, body, delayMillis)
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
        val pendingIntent = existingAlarm(requestCode)
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private fun hasPendingAlarm(requestCode: Int): Boolean {
        return existingAlarm(requestCode) != null
    }

    private fun existingAlarm(requestCode: Int): PendingIntent? {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_SHOW_NOTIFICATION
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun installTimeMillis(): Long {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(System.currentTimeMillis())
    }
}
