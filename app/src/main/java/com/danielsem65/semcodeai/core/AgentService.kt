package com.danielsem65.semcodeai.core

import android.app.Service
import android.content.Intent
import android.os.IBinder

class AgentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, Notify.working(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    companion object { const val NOTIF_ID = 42 }
}
