package com.danielsem65.semcodeai

import android.app.Application
import com.danielsem65.semcodeai.shell.ShellSession
import java.io.File

class SemApp : Application() {

    lateinit var session: ShellSession
        private set

    val workspace: File by lazy {
        val ws = File(Environment.workspaceRoot())
        ws.mkdirs()
        ws
    }

    override fun onCreate() {
        super.onCreate()
        session = ShellSession(workspace)
    }
}

private object Environment {
    fun workspaceRoot(): String = "/storage/emulated/0/semcode"
}
