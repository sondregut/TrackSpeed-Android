package com.trackspeed.android.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.trackspeed.android.MainActivity
import com.trackspeed.android.R

/**
 * BroadcastReceiver that handles alarm-triggered notifications.
 *
 * When an AlarmManager alarm fires, this receiver builds and displays
 * the appropriate notification. The notification type, title, and body
 * are passed as Intent extras from [NotificationService].
 */
class NotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationReceiver"
        const val ACTION_SHOW_NOTIFICATION = "com.trackspeed.android.SHOW_NOTIFICATION"
        const val EXTRA_NOTIFICATION_TYPE = "notification_type"
        const val EXTRA_TITLE = "notification_title"
        const val EXTRA_BODY = "notification_body"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOW_NOTIFICATION) return

        val notificationType = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(EXTRA_BODY) ?: return

        Log.d(TAG, "Received alarm for notification type: $notificationType")

        showNotification(context, notificationType, title, body)
    }

    private fun showNotification(
        context: Context,
        notificationType: String,
        title: String,
        body: String
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Tap action opens the app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, notificationType)
        }

        val pendingContentIntent = PendingIntent.getActivity(
            context,
            notificationType.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingContentIntent)
            .build()

        // Use a unique notification ID derived from the type so each type can be shown
        val notificationId = notificationType.hashCode()

        try {
            notificationManager.notify(notificationId, notification)
            Log.d(TAG, "Displayed notification: $title")
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to post notification: ${e.message}")
        }
    }
}
