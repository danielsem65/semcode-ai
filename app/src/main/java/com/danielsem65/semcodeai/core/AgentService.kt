package com.danielsem65.semcodeai.core

import android.app.Service
import android.content.Intent
import android.os.IBinder

class AgentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defensive: startForeground must happen within seconds of
        // startForegroundService(), no matter how the service was delivered.
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        runCatching { Notify.ensureChannel(this) }
        val n = runCatching { Notify.working(this) }.getOrElse {
            android.app.Notification.Builder(this, Notify.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("SemCode AI is working…")
                .build()
        }
        runCatching { startForeground(NOTIF_ID, n) }
    }

    companion object { const val NOTIF_ID = 42 }
}
