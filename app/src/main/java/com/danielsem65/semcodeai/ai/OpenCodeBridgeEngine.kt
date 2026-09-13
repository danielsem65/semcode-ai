package com.danielsem65.semcodeai.ai

import com.danielsem65.semcodeai.SemApp
import com.danielsem65.semcodeai.core.OpenCodeBridge
import com.danielsem65.semcodeai.core.SettingsStore
import com.danielsem65.semcodeai.core.Workspace
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uses the OFFICIAL opencode CLI (running inside the proot Linux env) as the AI
 * brain. This is the one legitimate way to use Zen's FREE tier from SemCode:
 * Zen locks free models to requests that come from the real opencode client, so
 * SemCode runs that client and feeds it prompts.
 *
 * Conventions (grounded in opencode's own source, cli/cmd/run.ts):
 *  - `opencode run --format json` prints one NDJSON line per event:
 *      {"type":"text","part":{"text":...}}                assistant text (part finished)
 *      {"type":"tool_use","part":{...}}                   opencode ran a tool itself
 *      {"type":"error","error":{...}}                     session error
 *    Process ends at the same time stdout closes (session idle breaks the loop).
 *  - Without `--auto`, the CLI auto-REJECTS every permission ask — opencode
 *    needs `--auto` here because it is supposed to edit the workspace itself.
 *
 * tool calls are deliberately NOT returned as SemCode tool calls: opencode
 * already executed them (its own bash/edit/… tools), and re-dispatching them
 * here would double-apply edits and break the agent loop.
 */
class OpenCodeBridgeEngine(
    app: SemApp,
    settings: SettingsStore,
    private val model: String
) : AiEngine {

    private val app = app
    private val settings = settings

    @Volatile private var proc: Process? = null

    private val opencodePathInGuest = "/root/opencode/opencode"

    private fun guestEnv(): Map<String, String> = mapOf(
        "HOME" to "/root",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TMPDIR" to "/tmp",
        "LANG" to "C.UTF-8",
        "TERM" to "dumb",
        "OPENCODE_DISABLE_AUTOUPDATE" to "true",
        "OPENCODE_CLIENT" to "cli",
        // Android's app seccomp filter SIGSYS-traps Bun syscalls; this shim
        // turns those traps into -ENOSYS so the CLI can actually run (see
        // OpenCodeBridge.GUEST_SIGSYS).
        "LD_PRELOAD" to OpenCodeBridge.GUEST_SIGSYS,
        "SIGSYS_LOG" to "/workspace/.semcode/sigsys.log",
        // Bun 1.2.10+ feature flag: never use epoll_pwait2 (syscall 441).
        "BUN_FEATURE_FLAG_DISABLE_EPOLL_PWAIT2" to "1"
    ).plus(
        if (settings.apiKey("zen").isNotBlank()) mapOf("OPENCODE_API_KEY" to settings.apiKey("zen"))
        else emptyMap()
    )

    private fun rootfs(): File {
        val bad = app.linuxEnv.healthCheck()
        if (bad != null) throw RuntimeException(
            "The OpenCode bridge needs the Linux environment — Settings → Linux environment → Install. ($bad)"
        )
        val bin = File(app.linuxEnv.rootfsDir(), "root/opencode/opencode")
        if (!bin.isFile) throw RuntimeException(
            "The opencode CLI isn't installed yet — Settings → OpenCode (Zen CLI) → tap Install (~176 MB)."
        )
        OpenCodeBridge.ensureSigsysShim(app)
        return app.linuxEnv.rootfsDir()
    }

    // ---------------- request building ----------------

    private fun buildPrompt(system: String, history: List<Msg>): String = buildString {
        appendLine("You are SemCode AI's coding engine. Act directly on the workspace (/workspace) to help the user.")
        if (system.isNotBlank()) {
            append("System: ").appendLine(system.trim())
        }
        appendLine()
        for (m in history) {
            when (m) {
                is Msg.User -> append("USER: ").appendLine(m.text.trim())
                is Msg.AssistantText -> append("ASSISTANT: ").appendLine(m.text.trim())
                is Msg.ToolUse -> append("TOOL USED: ").appendLine(m.name)
                is Msg.ToolResult -> append("TOOL RESULT: ")
                    .appendLine(m.result.take(1200).replace('\n', ' '))
            }
            appendLine()
        }
        append("— Continue as the coding agent now (the last USER line above is the current request).")
    }

    private fun scriptFor(promptFile: String, extra: String = ""): String =
        "mkdir -p /workspace/.semcode; " +
            "{ echo '== inherited env =='; env | sed 's/\\(OPENCODE_API_KEY=\\).*/\\1<redacted>/'; " +
            "export LD_PRELOAD=${OpenCodeBridge.GUEST_SIGSYS}; " +
            "echo '== exported LD_PRELOAD='\"\$LD_PRELOAD\"; " +
            "echo '== SIGSYS_LOG='\"\$SIGSYS_LOG\"; " +
            "ls -la ${OpenCodeBridge.GUEST_SIGSYS}; ls -la /root/.semcode/; " +
            "echo '== opencode dir =='; ls -la /root/opencode/ | head -5; " +
            "echo '== diag done =='; " +
            "} > /workspace/.semcode/diag.txt 2>&1; " +
            "cat /workspace/.semcode/diag.txt >&2 2>/dev/null; " +
            "cd /workspace; " +
            "echo '== LAUNCHING OPENCODE ==' >&2; " +
            "/root/opencode/opencode run --format json --auto $extra " +
            "-m '${model.replace("'", "")}' " +
            "\"$(cat '$promptFile')\""

    private fun buildProcess(script: String): Process {
        val workspace = Workspace.root(app, app.settings)
        val pb = ProcessBuilder(
            app.linuxEnv.prootCommand(workspace) + listOf("-c", script)
        )
        pb.environment().putAll(guestEnv() + app.linuxEnv.shellEnv())
        val p = pb.start()
        proc = p
        return p
    }

    // ---------------- AiEngine ----------------

    override fun chat(system: String, history: List<Msg>, tools: List<ToolDef>): EngineReply =
        chatStream(system, history, tools) {}

    override fun chatStream(
        system: String,
        history: List<Msg>,
        tools: List<ToolDef>,
        onDelta: (String) -> Unit
    ): EngineReply {
        val prompt = buildPrompt(system, history)
        val workspace = Workspace.root(app, app.settings)
        rootfs()
        val promptFile = File(File(workspace, ".semcode"), "oc_prompt")
        runCatching {
            promptFile.parentFile?.mkdirs()
            promptFile.writeText(prompt)
        }.getOrElse { throw RuntimeException("Cannot write prompt file: ${it.message}") }

        val text = StringBuilder()
        var bridgeError: String? = null
        var hadEvent = false

        try {
            val p = buildProcess(scriptFor("/workspace/.semcode/oc_prompt"))
            val errTail = StringBuilder()
            val errThread = Thread {
                runCatching { p.errorStream.bufferedReader().forEachLine { line ->
                    if (errTail.length < 60_000) errTail.appendLine(line)
                } }
            }.also { it.start() }

            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    val obj = runCatching { JSONObject(line.trim()) }.getOrNull() ?: return@forEachLine
                    when (obj.optString("type")) {
                        "text" -> {
                            hadEvent = true
                            val t = obj.optJSONObject("part")?.optString("text")
                            if (!t.isNullOrBlank()) {
                                synchronized(text) {
                                    text.append(t)
                                }
                                runCatching { onDelta(t) }
                            }
                        }
                        "error" -> {
                            hadEvent = true
                            val msg = errorMessage(obj.opt("error"))
                            if (msg != null) bridgeError = if (bridgeError.isNullOrBlank()) msg
                                else "$bridgeError\n$msg"
                        }
                        "step_start", "step_finish", "reasoning", "tool_use", "session" -> {}
                    }
                }
            }.onFailure { e ->
                if (bridgeError.isNullOrBlank()) bridgeError = "read failed: ${e.message}"
            }

            p.waitFor()
            errThread.join(2000)

            val full = synchronized(text) { text.toString() }
            val exit = p.exitValue()
            if (bridgeError.isNullOrBlank() && exit != 0) {
                bridgeError = "opencode exited with $exit: ${errTail.toString().trim().takeLast(55_000)}"
            }

            // Map the errors users actually hit (quota, permission, infra).
            val friendly = friendlyBridgeError(bridgeError)
            if (friendly != null) {
                if (full.isBlank()) throw RuntimeException(friendly)
                return EngineReply("$full\n\n⚠ $friendly", emptyList())
            }
            if (!hadEvent && full.isBlank()) {
                val detail = errTail.toString().trim().takeLast(55_000)
                throw RuntimeException("opencode produced no output${if (detail.isNotBlank()) " — $detail" else ""}")
            }
            return EngineReply(full.ifBlank { null }, emptyList())
        } finally {
            proc = null
            promptFile.delete()
        }
    }

    private fun friendlyBridgeError(raw: String?): String? {
        val e = raw?.trim().orEmpty()
        if (e.isBlank()) return null
        val lower = e.lowercase()
        return when {
            lower.contains("free usage") || lower.contains("FreeUsageLimit") ||
                lower.contains("rate limit") ->
                "Zen free-tier quota is reached for now (resets daily) — "
                    .plus("wait a bit, pick another Zen model, or use a paid Zen model / OpenRouter instead.")
            lower.contains("auth") || lower.contains("401") || lower.contains("unauthorized") ->
                "Zen key problem: open Settings → OpenCode (Zen CLI) → Link Zen key, and make sure the Zen provider has a valid key saved."
            lower.contains("network") || lower.contains("timeout") ->
                "Network issue talking to Zen — check Wi-Fi and try again."
            else -> "OpenCode: $e"
        }
    }

    private fun errorMessage(o: Any?): String? = when (o) {
        is JSONObject -> {
            val data = o.optJSONObject("data")
            val m = data?.optString("message") ?: o.optString("message")
            m.takeIf { it.isNotBlank() } ?: o.optString("name")
        }
        is String -> o
        else -> null
    }

    // ---------------- models ----------------

    override fun listModels(): List<String> {
        val fallback = listOf(
            "opencode/big-pickle",
            "opencode/x-preview-f-free",
            "opencode/mimo-v2.5-free",
            "opencode/ling-3.0-flash-fin-free",
            "opencode/nemotron-3.5-lightning-free",
            "opencode/nemotron-3-ultra-free",
            "opencode/muse-spark-1.2-contributor-free"
        )
        return runCatching {
            val ws = Workspace.root(app, app.settings)
            rootfs()
            val pb = ProcessBuilder(
                app.linuxEnv.prootCommand(ws) + listOf("-c",
                    "cd /workspace; export LD_PRELOAD=${OpenCodeBridge.GUEST_SIGSYS}; " +
                        "$opencodePathInGuest models opencode 2>/dev/null; true")
            )
            pb.environment().putAll(guestEnv() + app.linuxEnv.shellEnv())
            val p = pb.start()
            val ids = p.inputStream.bufferedReader().useLines { lines ->
                lines
                    .flatMap { Regex("opencode/[A-Za-z0-9._-]+").findAll(it) }
                    .map { it.value }
                    .toList()
                    .distinct()
            }
            if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly()
            if (ids.isEmpty()) fallback else ids
        }.getOrElse { fallback }
    }

    override fun cancelActive() {
        runCatching { proc?.destroy() }
    }
}