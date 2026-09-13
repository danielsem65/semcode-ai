package com.danielsem65.semcodeai.core

import com.danielsem65.semcodeai.SemApp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs and configures the official opencode CLI inside the proot Linux env.
 * The binary is a single ~180 MB arm64 executable shipped as a .tar.gz from the
 * opencode GitHub releases. It runs inside the guest (via proot) so the Zen
 * requests it makes carry the real opencode client identity → free tier works.
 */
object OpenCodeBridge {

    const val RELEASE_TAG = "v1.18.30"
    private const val DOWNLOAD_URL =
        "https://github.com/anomalyco/opencode/releases/download/$RELEASE_TAG/opencode-linux-arm64.tar.gz"
    const val SIZE_HINT_MB = 184

    const val GUEST_BIN = "/root/opencode/opencode"
    const val GUEST_CONFIG = "/root/.config/opencode/opencode.json"

    /**
     * Android's app seccomp filter (installed by Zygote, cannot be removed)
     * traps syscalls Bun uses (close_range, epoll_pwait2, openat2, …) as
     * SIGSYS before they can return, killing the CLI — even `--version`.
     * This LD_PRELOAD shim (built by CI from sysguard/sigsys-handler-arm64.c)
     * converts those traps into -ENOSYS so Bun's fallbacks run instead.
     */
    const val SIGSYS_ASSET = "sigsys/libsigsys-arm64.so"
    const val GUEST_SIGSYS = "/root/.semcode/libsigsys.so"

    fun guestBinHost(app: SemApp): File =
        File(app.linuxEnv.rootfsDir(), "root/opencode/opencode")

    fun guestConfigHost(app: SemApp): File =
        File(app.linuxEnv.rootfsDir(), "root/.config/opencode/opencode.json")

    fun guestSigsysHost(app: SemApp): File =
        File(app.linuxEnv.rootfsDir(), "root/.semcode/libsigsys.so")

    /** Puts the SIGSYS shim into the guest (once) so opencode can run at all. */
    fun ensureSigsysShim(app: SemApp) {
        val shim = guestSigsysHost(app)
        if (shim.isFile && shim.length() > 0) return
        runCatching {
            shim.parentFile?.mkdirs()
            app.assets.open(SIGSYS_ASSET).use { ins ->
                shim.outputStream().use { outs ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        outs.write(buf, 0, n)
                    }
                }
            }
            shim.setExecutable(true)
        }.getOrElse {
            throw RuntimeException("Cannot install SIGSYS shim into the Linux environment: ${it.message}")
        }
    }

    fun isInstalled(app: SemApp): Boolean =
        runCatching { app.linuxEnv.healthCheck() == null && guestBinHost(app).isFile }.getOrDefault(false)

    fun isKeyLinked(app: SemApp): Boolean =
        runCatching { guestConfigHost(app).isFile }.getOrDefault(false)

    fun isReady(app: SemApp): Boolean = isInstalled(app) && isKeyLinked(app)

    /** Downloads + extracts the CLI into the rootfs. onProgress runs on a worker thread. */
    fun install(app: SemApp, onProgress: (Int) -> Unit) {
        val bad = app.linuxEnv.healthCheck()
        require(bad == null) { "Install the Linux environment first (Settings → Linux) — $bad" }
        val dataDir = app.linuxEnv.linuxDataDir()
        dataDir.mkdirs()
        val tar = File(dataDir, "opencode.tar.gz")
        download(DOWNLOAD_URL, tar, onProgress)
        try {
            val dest = File(app.linuxEnv.rootfsDir(), "root/opencode")
            dest.mkdirs()
            TarGz.extract(tar, dest)
            val bin = guestBinHost(app)
            require(bin.isFile) { "extraction did not produce $bin" }
            onProgress(100)
            writeKeyConfig(app, app.settings.apiKey("zen"))
            ensureSigsysShim(app)
        } finally {
            tar.delete()
        }
    }

    /** Writes the Zen key into opencode's guest config. */
    fun writeKeyConfig(app: SemApp, zenKey: String) {
        if (zenKey.isBlank()) return
        val cfg = guestConfigHost(app)
        cfg.parentFile?.mkdirs()
        cfg.writeText(
            "{\"provider\":{\"opencode\":{\"apiKey\":${JSONEscape(zenKey)}}}}"
        )
    }

    private fun JSONEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun download(url: String, dst: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
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