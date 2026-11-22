package com.guna.nova

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * System heads-up approval prompt. Buttons are explicit and tied to one pending action.
 * Android may display it as a heads-up pop-up depending on notification permission and
 * the user's notification/channel settings.
 */
object ApprovalNotification {
    private const val CHANNEL_ID = "nova_sensitive_approvals"
    private const val NOTIFICATION_ID = 7301
    private const val ACTION_ALLOW = "com.guna.nova.action.APPROVAL_ALLOW"
    private const val ACTION_DENY = "com.guna.nova.action.APPROVAL_DENY"

    fun show(context: Context, title: String, details: String, expiresInMillis: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sensitive action approvals",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Allow or deny one specific sensitive action requested by Nova"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(channel)
        }

        val allowIntent = PendingIntent.getBroadcast(
            context, 7302,
            Intent(context, ApprovalActionReceiver::class.java).setAction(ACTION_ALLOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyIntent = PendingIntent.getBroadcast(
            context, 7303,
            Intent(context, ApprovalActionReceiver::class.java).setAction(ACTION_DENY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(details.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(false)
            .setTimeoutAfter(expiresInMillis)
            .addAction(android.R.drawable.checkbox_on_background, "Allow", allowIntent)
            .addAction(android.R.drawable.ic_delete, "Deny", denyIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            NovaCommand.notifyApprovalUnavailable(context)
        }
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    internal const val ALLOW_ACTION = ACTION_ALLOW
    internal const val DENY_ACTION = ACTION_DENY
}

class ApprovalActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ApprovalNotification.ALLOW_ACTION -> NovaCommand.resolvePendingSend(context, true)
            ApprovalNotification.DENY_ACTION -> NovaCommand.resolvePendingSend(context, false)
        }
    }
}
