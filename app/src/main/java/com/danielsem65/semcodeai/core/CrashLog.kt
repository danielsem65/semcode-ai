package com.danielsem65.semcodeai.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Captures uncaught exceptions to files so crashes can be reviewed/copied in-app. */
object CrashLog {

    private const val LATEST = "crash-latest.txt"
    private const val PREV = "crash-prev.txt"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(appCtx.filesDir)
                File(dir, PREV).delete()
                File(dir, LATEST).renameTo(File(dir, PREV))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(dir, LATEST).writeText(
                    buildString {
                        appendLine("SemCode AI crash report — $ts")
                        appendLine("thread: ${thread.name}")
                        appendLine()
                        appendLine(throwable.stackTraceToString())
                        var cause = throwable.cause
                        var depth = 0
                        while (cause != null && depth < 5) {
                            appendLine()
                            appendLine("Caused by: ")
                            appendLine(cause.stackTraceToString())
                            cause = cause.cause
                            depth++
                        }
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun latest(context: Context): String? =
        runCatching { File(context.applicationContext.filesDir, LATEST) }
            .getOrNull()?.takeIf { it.exists() }?.readText()?.take(20000)

    fun clear(context: Context) {
        runCatching {
            File(context.applicationContext.filesDir, LATEST).delete()
            File(context.applicationContext.filesDir, PREV).delete()
        }
    }
}
