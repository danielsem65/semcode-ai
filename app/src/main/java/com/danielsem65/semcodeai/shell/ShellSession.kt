package com.danielsem65.semcodeai.shell

import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A persistent shell session backed by a single /system/bin/sh (mksh/toybox) process,
 * so `cd`, env vars and state survive across commands - like a real terminal.
 */
class ShellSession(private val preferredDir: File) {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: java.io.BufferedReader? = null

    private val buffer = StringBuilder()
    private val signal = Semaphore(0)
    private val counter = AtomicLong()

    @Volatile private var currentToken = ""
    @Volatile private var alive = false

    private val lock = Any()
    var lastCwd: String = preferredDir.path
        private set

    private fun ensureStarted() {
        if (alive && process?.isAlive == true) return

        val dir = when {
            preferredDir.isDirectory -> preferredDir
            Environment.getExternalStorageDirectory()?.isDirectory == true ->
                Environment.getExternalStorageDirectory()!!
            else -> File("/")
        }
        dir.mkdirs()

        val pb = ProcessBuilder("/system/bin/sh")
        pb.redirectErrorStream(true)
        pb.directory(dir)
        pb.environment().apply {
            put("HOME", dir.path)
            put("TMPDIR", File(dir, ".tmp").apply { mkdirs() }.path)
            put("PATH", "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin")
        }

        val p = pb.start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()
        alive = true

        Thread({
            try {
                val r = reader
                while (alive) {
                    val line = r?.readLine() ?: break
                    synchronized(buffer) { buffer.append(line).append('\n') }
                    if (currentToken.isNotEmpty() && line.contains(currentToken)) signal.release()
                }
            } catch (_: Exception) { }
            alive = false
        }, "semcode-shell-reader").start()
    }

    /**
     * Runs one command in the persistent shell and returns combined output.
     * stdin is redirected from /dev/null so interactive programs can't hang us.
     */
    fun exec(command: String, timeoutSec: Long = 30): String = synchronized(lock) {
        ensureStarted()
        val token = "___SEM_DONE_${counter.incrementAndGet()}___"
        synchronized(buffer) { buffer.setLength(0) }
        currentToken = token

        val wrapped = "( $command ) < /dev/null\n" +
            "echo \"$token exit=\\$? cwd=\$(pwd)\"\n"
        try {
            writer?.write(wrapped)
            writer?.flush()
        } catch (e: Exception) {
            // Process died mid-write; restart and retry once.
            alive = false
            ensureStarted()
            writer?.write(wrapped)
            writer?.flush()
        }

        val finished = signal.tryAcquire(timeoutSec.coerceIn(1, 300), TimeUnit.SECONDS)
        currentToken = ""
        val out = synchronized(buffer) { buffer.toString() }

        if (!finished) {
            return "TIMEOUT after ${timeoutSec}s. Partial output:\n${out.take(4000)}\n" +
                "(session may be busy; use 'kill' via interrupt or run another command)"
        }

        val tokenLine = out.lineSequence().lastOrNull { it.contains(token) } ?: ""
        val exit = Regex("exit=(-?\\d+)").find(tokenLine)?.groupValues?.get(1) ?: "?"
        val cwd = Regex("cwd=(.+)$").find(tokenLine)?.groupValues?.get(1)?.trim()
        if (!cwd.isNullOrBlank()) lastCwd = cwd
        val body = out.lineSequence().filter { !it.contains(token) }.joinToString("\n")

        return (if (body.isBlank()) "(no output)" else body.trimEnd()) +
            "\n[exit=$exit cwd=${lastCwd}]"
    }

    /** Kills the whole shell; next exec starts a fresh session at the workspace. */
    fun interrupt() = synchronized(lock) {
        runCatching { process?.destroy() }
        alive = false
        process = null
    }

    fun isAlive(): Boolean = alive && process?.isAlive == true
}
