package com.danielsem65.semcodeai.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielsem65.semcodeai.MainActivity

object Notify {

    private const val CHANNEL = "agent_runs"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Agent runs",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Finishes when a background agent task completes" }
            )
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun post(context: Context, id: Int, title: String, body: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(500)))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    /** Ongoing notification shown by the foreground service while the agent works. */
    fun working(context: Context): android.app.Notification {
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("SemCode AI is working…")
            .setContentText("Agent run in progress — you'll be notified when it finishes.")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
