package com.danielsem65.semcodeai.core

import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent shell: one /system/bin/sh (mksh + toybox) process stays alive so
 * `cd`, exported vars and state survive between commands — like a real terminal.
 * A watchdog kills and restarts the session if a command overruns its timeout.
 */
class ShellSession(
    startDir: File,
    private val command: List<String> = listOf("/system/bin/sh"),
    private val envExtra: Map<String, String> = emptyMap()
) {

    private var homeDir: File = startDir

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    private val buffer = StringBuilder()
    private val signal = Semaphore(0)
    private val counter = AtomicLong()

    @Volatile private var token = ""
    @Volatile private var alive = false

    private val lock = Any()

    var cwd: String = startDir.path
        private set

    fun isAlive(): Boolean = alive && process?.isAlive == true

    private fun ensureStarted() {
        if (isAlive()) return
        val dir = if (homeDir.isDirectory) homeDir else File("/").also { homeDir = it }
        runCatching { dir.mkdirs() }

        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        pb.directory(dir)
        pb.environment().apply {
            put("HOME", envExtra["HOME"] ?: dir.path)
            put("TMPDIR", envExtra["TMPDIR"] ?: File(dir, ".tmp").apply { runCatching { mkdirs() } }.path)
            put("PATH", PATH_VALUE)
            put("LANG", "en_US.UTF-8")
            putAll(envExtra)
        }

        val p = pb.start()
        process = p
        writer = p.outputStream.bufferedWriter()
        alive = true

        Thread({
            try {
                val r = p.inputStream.bufferedReader()
                while (alive) {
                    val line = r.readLine() ?: break
                    val clean = stripAnsi(line)
                    synchronized(buffer) { buffer.append(clean).append('\n') }
                    if (token.isNotEmpty() && clean.contains(token)) signal.release()
                }
            } catch (_: Exception) {
            }
            alive = false
        }, "semcode-shell").start()
    }

    /** Runs one command; stdin is closed so nothing can hang waiting for input. */
    fun exec(command: String, timeoutSec: Long = 30): String = synchronized(lock) {
        ensureStarted()
        val done = "___SEM_${counter.incrementAndGet()}___"
        synchronized(buffer) { buffer.setLength(0) }
        token = done

        val script = "( $command ) < /dev/null\necho \"$done rc=\$? pwd=\$(pwd)\"\n"
        try {
            writer?.write(script)
            writer?.flush()
        } catch (e: Exception) {
            kill()
            ensureStarted()
            writer?.write(script)
            writer?.flush()
        }

        val finished = signal.tryAcquire(timeoutSec.coerceIn(1, 600), TimeUnit.SECONDS)
        token = ""
        val out = synchronized(buffer) { buffer.toString() }

        if (!finished) {
            kill() // command wedged the shell — restart fresh rather than hang forever
            return "TIMEOUT after ${timeoutSec}s — session was reset. Partial output:\n${out.take(4000)}"
        }

        val marker = out.lineSequence().lastOrNull { it.contains(done) } ?: ""
        val rc = Regex("rc=(-?\\d+)").find(marker)?.groupValues?.get(1) ?: "?"
        Regex("pwd=(.+)$").find(marker)?.groupValues?.get(1)?.trim()?.let { if (it.isNotBlank()) cwd = it }

        val body = out.lineSequence().filter { !it.contains(done) }.joinToString("\n").trimEnd()
        return (body.ifBlank { "(no output)" }) + "\n[rc=$rc cwd=$cwd]"
    }

    /** Kill current command/session. */
    fun interrupt() = synchronized(lock) { kill() }

    private fun kill() {
        runCatching { process?.destroy() }
        alive = false
        process = null
        writer = null
    }

    private fun stripAnsi(s: String): String =
        ANSI_REGEX.replace(s, "")

    companion object {
        private const val PATH_VALUE =
            "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin"
        private val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[A-Za-z]|\u001B\\][^\u0007]*\u0007")
    }
}
