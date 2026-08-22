package com.danielsem65.semcodeai

import android.app.Application
import android.content.Intent
import com.danielsem65.semcodeai.core.LinuxEnv
import com.danielsem65.semcodeai.core.ProjectStore
import com.danielsem65.semcodeai.core.SettingsStore
import com.danielsem65.semcodeai.core.ShellSession
import com.danielsem65.semcodeai.core.Workspace
import com.danielsem65.semcodeai.fs.FileOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class SemApp : Application() {

    lateinit var settings: SettingsStore
        private set

    /**
     * Application-scoped scope for agent runs. Runs launched here survive
     * Activity/ViewModel destruction and, with the foreground service active,
     * keep the process alive in deep background.
     */
    val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val runLock = Any()
    private var activeRuns = 0

    fun runStarted() {
        synchronized(runLock) {
            activeRuns++
            if (activeRuns == 1) {
                startForegroundService(Intent(this, com.danielsem65.semcodeai.core.AgentService::class.java))
            }
        }
    }

    fun runEnded() {
        synchronized(runLock) {
            if (activeRuns > 0) activeRuns--
            if (activeRuns == 0) {
                stopService(Intent(this, com.danielsem65.semcodeai.core.AgentService::class.java))
            }
        }
    }


    val fileOps: FileOps by lazy { FileOps { Workspace.root(this, settings) } }

    private var shell: ShellSession? = null
    private var linuxShellField: ShellSession? = null

    /** Lazily created; rebuilt automatically after the storage mode changes. */
    val session: ShellSession
        get() = synchronized(this) {
            shell ?: ShellSession(Workspace.root(this, settings)).also { shell = it }
        }

    val linuxEnv: LinuxEnv by lazy { LinuxEnv(this) { Workspace.root(this, settings) } }

    val projectStore: ProjectStore by lazy { ProjectStore(this) }

    /** Persistent guest shell inside proot; created on first use after install. */
    fun linuxShell(): ShellSession {
        check(linuxEnv.isInstalled()) { "Linux environment is not installed" }
        return synchronized(this) {
            linuxShellField ?: ShellSession(
                File("/"),
                linuxEnv.prootCommand(Workspace.root(this, settings)),
                mapOf(
                    "HOME" to "/root",
                    "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TMPDIR" to "/tmp",
                    "LANG" to "C.UTF-8"
                )
            ).also { linuxShellField = it }
        }
    }

    fun invalidateLinuxSession() {
        synchronized(this) {
            linuxShellField?.interrupt()
            linuxShellField = null
        }
    }

    /** Call after the storage mode changes so the shell lands in the new root. */
    fun onWorkspaceChanged() {
        synchronized(this) {
            shell?.interrupt()
            shell = null
            invalidateLinuxSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        com.danielsem65.semcodeai.core.CrashLog.install(this)
        settings = SettingsStore(this)
    }
}
