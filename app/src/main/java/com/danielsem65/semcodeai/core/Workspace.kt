package com.danielsem65.semcodeai.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Resolves the folders the app lives in.
 *
 * Durable home: /storage/emulated/0/.semcode-ai — survives "clear app data"
 * and uninstalls (Sketchware-Pro style). Used for the agent workspace,
 * saved projects and AI models whenever storage permission is granted.
 *
 * The Linux rootfs deliberately does NOT live here: shared storage is
 * mounted noexec, so guest binaries could never run from it.
 */
object Workspace {

    const val HOME_ROOT = "/storage/emulated/0/.semcode-ai"
    const val FULL_ROOT = "$HOME_ROOT/workspace"

    /**
     * The app targets SDK 28 (Termux-style), so it runs in Android's legacy
     * storage mode: the classic WRITE_EXTERNAL_STORAGE runtime permission
     * grants full read/write to shared storage.
     */
    fun isFullAvailable(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) return true
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    /** Durable home on shared storage, or null when storage isn't granted. */
    fun home(context: Context): File? =
        if (isFullAvailable(context)) File(HOME_ROOT).apply { mkdirs() } else null

    fun root(context: Context, settings: SettingsStore): File {
        val useFull = settings.fullStorage && isFullAvailable(context)
        return if (useFull) {
            File(FULL_ROOT).apply { mkdirs() }
        } else {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(base, "workspace").apply { mkdirs() }
        }
    }

    /** True if path lives inside root (or equals it). */
    fun contains(root: File, f: File): Boolean =
        f.canonicalPath == root.canonicalPath || f.canonicalPath.startsWith(root.canonicalPath + File.separator)
}
