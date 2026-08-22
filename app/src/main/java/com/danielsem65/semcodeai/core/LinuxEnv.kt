package com.danielsem65.semcodeai.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Proot-based Linux environment (no root required).
 * proot is shipped as a "native library" so Android allows exec() from the
 * read-only nativeLibraryDir; a user-chosen distro rootfs is downloaded at
 * runtime and mounted with /workspace bound to the SemCode workspace.
 *
 * Extraction is pure Kotlin (GZIP + manual tar parser) — device toybox tar
 * builds differ across OEMs and have been observed silently skipping symlinks,
 * which leaves the rootfs without /bin/sh and proot unable to start a shell.
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
        );

        companion object {
            fun fromLabel(l: String): Distro? = values().firstOrNull { it.label == l }
        }
    }

    private val linuxDir get() = File(context.filesDir, "linux")
    private val rootfs get() = File(linuxDir, "rootfs")

    // ---------------- health ----------------

    /** Null when the environment can actually boot a shell; otherwise why not. */
    fun healthCheck(): String? {
        val marker = File(linuxDir, ".distro")
        if (!marker.exists()) return "not installed"
        val reason = healthReasonFor(rootfs) ?: return null
        return reason
    }

    private fun needsRepair(): Boolean =
        File(linuxDir, ".distro").exists() && healthCheck() != null

    fun isInstalled(): Boolean = healthCheck() == null

    fun installedLabel(): String =
        if (isInstalled()) File(linuxDir, ".distro").takeIf { it.exists() }?.readText()?.trim() ?: "Linux"
        else ""

    /** Human-readable facts about the install — shown in Settings so the user
     *  (and we, remotely) can see exactly what is on disk and where. */
    fun diagnose(): String = buildString {
        appendLine("Path: ${rootfs.absolutePath}")
        val shFile = File(rootfs, "bin/sh")
        append("sh: ")
        appendLine(
            runCatching {
                val p = Paths.get(shFile.absolutePath)
                when {
                    !Files.exists(p, LinkOption.NOFOLLOW_LINKS) -> "MISSING"
                    else -> {
                        var cur = p
                        var hops = 0
                        while (Files.isSymbolicLink(cur) && hops++ < 8) {
                            val t = Files.readSymbolicLink(cur)
                            cur = if (t.isAbsolute)
                                Paths.get(rootfs.absolutePath, t.toString().trimStart('/'))
                            else cur.parent.resolve(t).normalize()
                        }
                        val f = cur.toFile()
                        if (f.isFile && f.length() > 0L)
                            "-> ${cur.toFile().name} (${f.length()} bytes) ✓"
                        else "broken chain"
                    }
                }
            }.getOrDefault("unreadable")
        )
        for (extra in listOf("bin/busybox", "bin/bash", "usr/bin/dash")) {
            val f = File(rootfs, extra)
            if (f.isFile) appendLine("$extra: ${f.length()} bytes")
        }
        append("marker: ")
        append(File(linuxDir, ".distro").takeIf { it.exists() }?.readText()?.trim() ?: "none")
    }.trimEnd()

    /** Generic boot test: /bin/sh must exist and resolve (through any number
     *  of symlinks, including merged-usr dir links) to a real non-empty file.
     *  Works for Alpine (busybox) and Ubuntu (dash) alike — do NOT test for
     *  specific binaries like busybox, other distros don't ship them. */
    private fun healthReasonFor(base: File): String? {
        val sh = File(base, "bin/sh")
        val shExists = runCatching {
            Files.exists(Paths.get(sh.absolutePath), LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
        if (!shExists) return "bin/sh missing"

        val resolves = runCatching {
            var cur = Paths.get(sh.absolutePath)
            var hops = 0
            while (Files.isSymbolicLink(cur) && hops++ < 8) {
                val t = Files.readSymbolicLink(cur)
                cur = if (t.isAbsolute)
                    Paths.get(base.absolutePath, t.toString().trimStart('/'))
                else cur.parent.resolve(t).normalize()
            }
            val f = cur.toFile()
            f.isFile && f.length() > 0L
        }.getOrDefault(false)
        if (!resolves) return "/bin/sh does not resolve to a real binary"
        return null
    }

    // ---------------- install / repair ----------------

    /**
     * Downloads + extracts the rootfs; onProgress runs on an arbitrary thread.
     * Safe to call when already installed (no-op) or when the existing install
     * is broken (wipes and reinstalls).
     *
     * Termux-style: extraction goes into a staging directory that is verified
     * BEFORE it becomes the live rootfs via rename — a failed or partial
     * install can never leave a broken environment behind.
     */
    suspend fun install(d: Distro, onProgress: (Int) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (healthCheck() == null) return@withContext
        linuxDir.mkdirs()
        val staging = File(linuxDir, "rootfs.staging")
        staging.deleteRecursively()
        staging.mkdirs()

        val tar = File(linuxDir, "${d.name}.tar.gz")
        download(d.url, tar, onProgress)

        try {
            extractTarGz(tar, staging)
        } finally {
            tar.delete()
        }

        val reason = healthReasonFor(staging)
        if (reason != null) {
            staging.deleteRecursively()
            throw RuntimeException("rootfs extraction incomplete ($reason)")
        }

        File(staging, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(staging, "etc/hosts").writeText("127.0.0.1 localhost\n")

        // Atomic swap: staging becomes the live rootfs only after passing checks.
        rootfs.deleteRecursively()
        if (!staging.renameTo(rootfs)) {
            staging.copyRecursively(rootfs, overwrite = true)
            staging.deleteRecursively()
        }
        File(linuxDir, ".distro").writeText(d.label)
    }

    /** Marker exists but environment is unusable → wipe + fresh download. */
    suspend fun repair(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val label = File(linuxDir, ".distro").takeIf { it.exists() }?.readText()?.trim()
        val d = Distro.values().firstOrNull { it.label == label } ?: Distro.ALPINE
        if (healthCheck() == null) return@withContext false
        runCatching { remove() }
        install(d, onProgress)
        true
    }

    /** True when a previous install exists but no longer boots a shell. */
    fun detectBroken(): Boolean = needsRepair()

    fun remove() {
        linuxDir.deleteRecursively()
    }

    // ---------------- pure-Kotlin tar.gz extractor ----------------

    private fun extractTarGz(gz: File, dst: File) {
        FileInputStream(gz).use { fin ->
            val magic = ByteArray(2)
            if (readFully(fin, magic) != 2 || magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) {
                throw RuntimeException("downloaded archive is not gzip (truncated download?)")
            }
        }
        GZIPInputStream(FileInputStream(gz), 65536).use { gin ->
            parseTar(gin, dst)
        }
    }

    private fun readFully(ins: java.io.InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
        var total = 0
        while (total < len) {
            val n = ins.read(buf, off + total, len - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun parseTar(ins: java.io.InputStream, dst: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var pendingPaxPath: String? = null

        while (true) {
            val got = readFully(ins, header)
            if (got < 512) break
            if (header.all { it == 0.toByte() }) break // end-of-archive blocks

            val name = tarString(header, 0, 100)
            val modeStr = tarString(header, 100, 8)
            val size = tarSize(header, 124, 12)
            val type = header[156]
            val linkName = tarString(header, 157, 100)
            val prefix = tarString(header, 345, 155)

            var entryName = pendingLongName ?: pendingPaxPath
                ?: (if (prefix.isNotBlank()) "$prefix/$name" else name)
            pendingLongName = null
            pendingPaxPath = null

            when (type) {
                'L'.code.toByte() -> { // GNU long name
                    val buf = ByteArray(size.toInt())
                    readFully(ins, buf)
                    pendingLongName = String(buf, 0, buf.size).trimEnd('\u0000')
                    skipPadding(ins, size)
                    continue
                }
                'x'.code.toByte(), 'g'.code.toByte() -> { // PAX extended header
                    val buf = ByteArray(size.toInt())
                    readFully(ins, buf)
                    val text = String(buf, 0, buf.size)
                    Regex("path=([^\\n]+)").find(text)?.let { pendingPaxPath = it.groupValues[1] }
                    skipPadding(ins, size)
                    continue
                }
            }

            val rel = entryName.removePrefix("./").trimStart('/')
            if (rel.isBlank()) { drainEntry(ins, size, null); continue }
            // Never let archive entries escape the rootfs.
            val safeRel = rel.split('/').filter { it != ".." }.joinToString("/")
            val outFile = File(dst, safeRel)

            when (type) {
                '5'.code.toByte() -> {
                    drainEntry(ins, size, null)
                    outFile.mkdirs()
                }
                '2'.code.toByte() -> { // symlink
                    drainEntry(ins, size, null)
                    outFile.parentFile?.mkdirs()
                    outFile.delete()
                    createSymLink(outFile, linkName)
                }
                '1'.code.toByte() -> { // hardlink → materialize as copy
                    drainEntry(ins, size, null)
                    outFile.parentFile?.mkdirs()
                    val src = File(dst, linkName.removePrefix("./").trimStart('/'))
                    if (src.isFile) src.copyTo(outFile, overwrite = true)
                }
                '0'.code.toByte(), 0.toByte(), '7'.code.toByte() -> { // regular file
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { drainEntry(ins, size, it) }
                    if (modeStr.isNotBlank()) {
                        val m = modeStr.trim('\u0000', ' ').toIntOrNull(8) ?: 0
                        if (m and 0b001_001_001 != 0) outFile.setExecutable(true, false)
                    }
                }
                else -> drainEntry(ins, size, null)
            }
        }
    }

    /** Reads an entry's payload (writing to out if given) and then skips the
     *  512-byte block padding every tar data section is rounded up to.
     *  Missing this desynced the whole archive after the first odd-sized file. */
    private fun drainEntry(ins: java.io.InputStream, size: Long, out: FileOutputStream?) {
        val buf = ByteArray(65536)
        var remaining = size
        while (remaining > 0) {
            val n = ins.read(buf, 0, if (remaining < buf.size) remaining.toInt() else buf.size)
            if (n <= 0) throw RuntimeException("unexpected end of archive")
            out?.write(buf, 0, n)
            remaining -= n
        }
        skipPadding(ins, size)
    }

    private fun skipPadding(ins: java.io.InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) {
            val junk = ByteArray(pad)
            readFully(ins, junk)
        }
    }

    /** Symlink creation with a busybox-safe fallback: copying the target works
     *  because busybox dispatches on argv[0], and directory links fall back to
     *  a recursive copy. Guarantees /bin/sh exists even where symlink(2) fails. */
    private fun createSymLink(link: File, rawTarget: String) {
        val target = rawTarget.trim()
        try {
            Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(target))
            return
        } catch (_: Exception) {
        }
        val resolved = if (target.startsWith("/")) File(rootfs, target.trimStart('/'))
        else File(link.parentFile, target)
        if (resolved.isDirectory) {
            resolved.copyRecursively(link, overwrite = true)
        } else if (resolved.isFile) {
            resolved.copyTo(link, overwrite = true)
            link.setExecutable(resolved.canExecute(), false)
        }
    }

    private fun tarString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && h[end] != 0.toByte()) end++
        return String(h, off, end - off)
    }

    private fun tarSize(h: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        var started = false
        for (i in off until off + len) {
            val c = h[i].toInt() and 0xFF
            if (c == 0 || c == ' '.code) {
                if (started) break else continue
            }
            started = true
            v = v * 8 + (c - '0'.code)
        }
        return v
    }

    // ---------------- boot ----------------

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

    /** Extra environment for the guest shell process (proot + guest). */
    fun shellEnv(): Map<String, String> {
        val tmp = File(linuxDir, "tmp").apply { mkdirs() }
        return mapOf("PROOT_TMP_DIR" to tmp.absolutePath)
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
                    if (total > 0 && read != total) {
                        throw RuntimeException("download truncated ($read of $total bytes)")
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
