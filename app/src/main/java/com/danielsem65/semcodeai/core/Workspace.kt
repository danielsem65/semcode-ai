package com.danielsem65.semcodeai.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Resolves the folder the agent lives in.
 * - Out of the box: app-private dir (works with ZERO permissions granted).
 * - Optional upgrade: /storage/emulated/0/semcode once the user grants
 *   "All files access" AND enables full storage in Settings.
 */
object Workspace {

    const val FULL_ROOT = "/storage/emulated/0/semcode"

    fun root(context: Context, settings: SettingsStore): File {
        val useFull = settings.fullStorage && Environment.isExternalStorageManager()
        return if (useFull) {
            File(FULL_ROOT).apply { mkdirs() }
        } else {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(base, "workspace").apply { mkdirs() }
        }
    }

    fun isFullAvailable(): Boolean = Environment.isExternalStorageManager()

    /** True if path lives inside root (or equals it). */
    fun contains(root: File, f: File): Boolean =
        f.canonicalPath == root.canonicalPath || f.canonicalPath.startsWith(root.canonicalPath + File.separator)
}
