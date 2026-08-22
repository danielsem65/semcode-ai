package com.danielsem65.semcodeai.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages the bundled llama.cpp server (shipped as fake JNI lib
 * libllama-server.so so Android allows exec from nativeLibraryDir).
 * Serves an OpenAI-compatible API on localhost for the On-device provider.
 */
object LlamaServer {

    const val PORT = 8756
    private const val CTX_SIZE = 4096

    private val lock = Any()
    private var process: Process? = null

    @Volatile private var currentModel: String? = null
    @Volatile private var healthClient: OkHttpClient? = null

    private val logLines = ArrayDeque<String>()

    fun binaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libllama-server.so")

    fun isBinaryAvailable(context: Context): Boolean = binaryFile(context).exists()

    fun isRunning(): Boolean {
        val p = process ?: return false
        return p.isAlive
    }

    fun logTail(): String = synchronized(logLines) { logLines.joinToString("\n").take(1500) }

    fun modelInUse(): String = currentModel ?: ""

    /**
     * Starts (or restarts) the server with the given .gguf and waits until healthy.
     * onProgress receives human-readable status ("copying model 42%", "loading…").
     */
    fun ensureStarted(context: Context, modelPath: String, onProgress: (String) -> Unit = {}) {
        synchronized(lock) {
            val bin = binaryFile(context)
            if (!bin.exists())
                throw RuntimeException("On-device engine not present in this build.")

            // A previous app instance may have left an orphan server behind.
            if (!isRunning() && healthy()) {
                runCatching {
                    ProcessBuilder("pkill", "-f", "libllama-server").start().waitFor()
                }
                Thread.sleep(1200)
            }

            if (isRunning() && currentModel == modelPath && healthy()) return

            stopInternal()

            val src = File(modelPath)
            if (!src.exists()) throw RuntimeException("Model file not found: $modelPath")
            if (!src.canRead()) throw RuntimeException("Model file is not readable: $modelPath")

            // llama.cpp mmaps the model; mmap over /storage FUSE is unreliable and
            // crashes the engine instantly. Copy into real-filesystem storage first
            // (durable .semcode-ai/models when available, private fallback).
            val localFile = if (src.absolutePath.startsWith(context.filesDir.absolutePath) ||
                src.absolutePath.startsWith(Workspace.HOME_ROOT)
            ) {
                src
            } else {
                onProgress("copying model 0%")
                val dstDir = Workspace.home(context)?.let { File(it, "models") }
                    ?: File(context.filesDir, "models").apply { mkdirs() }
                val dst = File(dstDir, src.name)
                if (!dst.exists() || dst.length() != src.length()) {
                    val tmp = File(dstDir, src.name + ".part")
                    src.inputStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(1 shl 23) // 8 MB chunks
                            var copied = 0L
                            val total = src.length()
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                val pct = if (total > 0) (copied * 100 / total).toInt() else 100
                                onProgress("copying model $pct%")
                            }
                        }
                    }
                    if (!tmp.renameTo(dst)) {
                        tmp.copyTo(dst, overwrite = true)
                        tmp.delete()
                    }
                }
                dst
            }

            onProgress("loading model…")
            synchronized(logLines) { logLines.clear() }
            val pb = ProcessBuilder(
                bin.absolutePath,
                "-m", localFile.absolutePath,
                "--host", "127.0.0.1",
                "--port", PORT.toString(),
                "-c", CTX_SIZE.toString()
            ).redirectErrorStream(true)

            val proc = try {
                pb.start()
            } catch (e: Exception) {
                throw RuntimeException("Could not launch engine: ${e.message}")
            }
            process = proc
            currentModel = modelPath

            Thread {
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            synchronized(logLines) {
                                logLines.addLast(line)
                                while (logLines.size > 40) logLines.removeFirst()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            if (!awaitHealthy(180_000)) {
                val exitCode = runCatching { proc.exitValue() }.getOrElse { -1 }
                stopInternal()
                val log = logTail().take(400)
                throw RuntimeException(
                    when {
                        exitCode == 139 || exitCode == 135 ->
                            "The engine crashed while loading (signal $exitCode). " +
                                "This build may be incompatible — report this."
                        exitCode == 137 || exitCode == 9 ->
                            "The system killed the engine — out of memory. Use a smaller .gguf."
                        log.isBlank() ->
                            "The engine exited silently (code $exitCode) before loading the model."
                        else -> "The model did not load in time. Log:\n$log"
                    }
                )
            }
        }
    }

    fun stop() = synchronized(lock) { stopInternal() }

    private fun stopInternal() {
        runCatching { process?.destroy() }
        process = null
        currentModel = null
    }

    private fun client(): OkHttpClient =
        healthClient ?: OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
            .also { healthClient = it }

    private fun healthy(): Boolean = try {
        client().newCall(
            Request.Builder().url("http://127.0.0.1:$PORT/health").build()
        ).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    private fun awaitHealthy(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) return false
            if (healthy()) return true
            Thread.sleep(600)
        }
        return healthy()
    }
}
