package com.danielsem65.semcodeai.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Proot-based Linux environment (no root required).
 * proot is shipped as a "native library" so Android allows exec() from the
 * read-only nativeLibraryDir; a user-chosen distro rootfs is downloaded at
 * runtime and mounted with /workspace bound to the SemCode workspace.
 */
class LinuxEnv(private val context: Context, private val workspaceProvider: () -> File) {

    enum class Distro(val label: String, val url: String, val sizeHint: String, val pkgManager: String) {
        ALPINE(
            "Alpine 3.20",
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
            "~4 MB", "apk"
        ),
        UBUNTU(
            "Ubuntu 22.04",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
            "~26 MB", "apt"
        )
    }

    private val linuxDir get() = File(context.filesDir, "linux")
    private val rootfs get() = File(linuxDir, "rootfs")

    fun isInstalled(): Boolean = File(rootfs, "bin/sh").exists()

    fun installedLabel(): String =
        if (isInstalled()) File(linuxDir, ".distro").takeIf { it.exists() }?.readText()?.trim() ?: "Linux"
        else ""

    /** Downloads + extracts the rootfs; onProgress runs on an arbitrary thread. */
    suspend fun install(d: Distro, onProgress: (Int) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext
        linuxDir.mkdirs()
        rootfs.deleteRecursively()
        rootfs.mkdirs()

        val tar = File(linuxDir, "${d.name}.tar.gz")
        download(d.url, tar, onProgress)

        val p = ProcessBuilder("/system/bin/toybox", "tar", "-xzf", tar.path, "-C", rootfs.path)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        tar.delete()
        if (rc != 0) throw RuntimeException("extract failed rc=$rc ${out.take(200)}")

        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n")
        File(linuxDir, ".distro").writeText(d.label)
    }

    fun remove() {
        linuxDir.deleteRecursively()
    }

    /**
     * Command that boots a persistent guest shell.
     * -0 fakes root (needed by apk/apt); /workspace is the shared SemCode workspace.
     */
    fun prootCommand(workspace: File): List<String> {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        require(proot.exists()) { "proot binary missing from nativeLibraryDir" }
        return listOf(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            "-0",
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${workspace.absolutePath}:/workspace",
            "/bin/sh"
        )
    }

    private fun download(url: String, dst: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode} fetching $url")
            val total = conn.contentLengthLong
            conn.inputStream.use { ins ->
                dst.outputStream().use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
