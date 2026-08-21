package com.danielsem65.semcodeai

import android.app.Application
import com.danielsem65.semcodeai.core.SettingsStore
import com.danielsem65.semcodeai.core.ShellSession
import com.danielsem65.semcodeai.core.Workspace
import com.danielsem65.semcodeai.fs.FileOps

class SemApp : Application() {

    lateinit var settings: SettingsStore
        private set

    val fileOps: FileOps by lazy { FileOps { Workspace.root(this, settings) } }

    private var shell: ShellSession? = null

    /** Lazily created; rebuilt automatically after the storage mode changes. */
    val session: ShellSession
        get() = synchronized(this) {
            shell ?: ShellSession(Workspace.root(this, settings)).also { shell = it }
        }

    /** Call after the storage mode changes so the shell lands in the new root. */
    fun onWorkspaceChanged() {
        synchronized(this) {
            shell?.interrupt()
            shell = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }
}
