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

    /** Starts (or restarts) the server with the given .gguf and waits until healthy. */
    fun ensureStarted(context: Context, modelPath: String) {
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

            val f = File(modelPath)
            if (!f.exists()) throw RuntimeException("Model file not found: $modelPath")

            synchronized(logLines) { logLines.clear() }
            val pb = ProcessBuilder(
                bin.absolutePath,
                "-m", f.absolutePath,
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
                stopInternal()
                throw RuntimeException(
                    "The model did not load in time (or ran out of memory). " +
                        "Try a smaller .gguf (1–3B, Q4). Log:\n${logTail().take(400)}"
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
