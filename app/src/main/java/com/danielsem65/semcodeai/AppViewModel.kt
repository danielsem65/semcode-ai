package com.danielsem65.semcodeai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielsem65.semcodeai.ai.EngineReply
import com.danielsem65.semcodeai.ai.Msg
import com.danielsem65.semcodeai.ai.Provider
import com.danielsem65.semcodeai.ai.Providers
import com.danielsem65.semcodeai.github.GitHubSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ChatMessage(
    val role: Role,
    val text: String,
    val isTool: Boolean = false,
    val isError: Boolean = false
) {
    enum class Role { USER, MODEL }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings get() = getApplication<SemApp>().settings
    private val fileOps get() = getApplication<SemApp>().fileOps
    private val shell get() = getApplication<SemApp>().session

    // ---------------- chat ----------------
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _stepText = MutableStateFlow("")
    val stepText: StateFlow<String> = _stepText

    private val _statusLine = MutableStateFlow("")
    val statusLine: StateFlow<String> = _statusLine

    private val apiHistory = mutableListOf<Msg>()

    init { refreshStatus() }

    fun activeProvider(): Provider = Providers.byId(settings.activeProviderId)

    fun effectiveModel(p: Provider = activeProvider()): String =
        settings.modelOverride.ifBlank { p.defaultModel }

    fun refreshStatus() {
        val p = activeProvider()
        val keyOk = p.isLocal || settings.hasKeyFor(p.id)
        _statusLine.value =
            "${p.displayName} · ${effectiveModel(p)}" + if (!keyOk) "  ⚠ no key" else ""
    }

    fun clearChat() {
        _messages.value = emptyList()
        apiHistory.clear()
        _stepText.value = ""
    }

    fun send(userTextRaw: String) {
        val userText = userTextRaw.trim()
        if (userText.isEmpty() || _busy.value) return

        val provider = activeProvider()
        val model = effectiveModel(provider)
        val key = settings.apiKey(provider.id)
        if (!provider.isLocal && key.isBlank()) {
            _messages.value += ChatMessage(
                ChatMessage.Role.MODEL,
                "**No API key for ${provider.displayName}.**\n\nOpen Settings → pick ${provider.displayName} → paste your key from ${provider.keyUrl}.\nTip: use the Test button there to verify it instantly.",
                isError = true
            )
            return
        }

        _messages.value += ChatMessage(ChatMessage.Role.USER, userText)
        apiHistory += Msg.User(userText)
        _busy.value = true
        _stepText.value = "thinking…"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                runAgent(provider, key, model)
            } catch (e: Exception) {
                emitModel("Error: ${e.message ?: "request failed"}", isError = true)
            } finally {
                _busy.value = false
                _stepText.value = ""
            }
        }
    }

    private suspend fun runAgent(provider: Provider, apiKey: String, model: String) {
        val engine = Providers.create(provider, apiKey, model)
        var steps = 0

        while (steps < MAX_STEPS) {
            steps++
            _stepText.value = "step $steps/$MAX_STEPS"

            val reply: EngineReply = withContext(Dispatchers.IO) {
                engine.chat(systemPrompt(), apiHistory.toList(), com.danielsem65.semcodeai.ai.Tools.all())
            }

            if (reply.calls.isEmpty()) {
                emitModel(reply.text ?: "(no answer)")
                return
            }
            if (!reply.text.isNullOrBlank()) emitModel(reply.text)

            for (call in reply.calls) {
                apiHistory += Msg.ToolUse(call.id, call.name, call.argsJson)
                emitTool("${call.name} ${shortArgs(call.argsJson)}")
                _stepText.value = "step $steps/$MAX_STEPS · ${call.name}"
                val result = dispatch(call.name, call.argsJson)
                emitResult(shorten(result))
                apiHistory += Msg.ToolResult(call.id, call.name, result.take(20_000))
            }
        }
        emitModel("Reached the $MAX_STEPS-step limit — say \"continue\" and I'll keep going.")
    }

    private suspend fun dispatch(name: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        return withContext(Dispatchers.IO) {
            try {
                when (name) {
                    "run_command" -> shell.exec(
                        args.optString("command", ""),
                        args.optLong("timeout_seconds", 30).coerceIn(1, 600)
                    )
                    else ->
                        if (name.startsWith("github_")) githubDispatch(name, args)
                        else fileOps.execute(name, args)
                }
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }
    }

    private suspend fun githubDispatch(name: String, args: JSONObject): String =
        withContext(Dispatchers.IO) {
            val token = settings.githubToken
            when (name) {
                "github_clone" -> GitHubSync.clone(fileOps, token, args.optString("repo"), args.optString("path"))
                "github_status" -> GitHubSync.status(fileOps, token, args.getString("path"))
                "github_push" -> GitHubSync.push(
                    fileOps, token, args.getString("path"),
                    args.optString("message", "Update from SemCode AI").ifBlank { "Update from SemCode AI" }
                )
                "github_pull" -> GitHubSync.pull(fileOps, token, args.getString("path"))
                "github_create_repo" -> GitHubSync.createRepo(token, args.getString("name"), args.optBoolean("private", false))
                else -> "ERROR: unknown $name"
            }
        }

    private fun systemPrompt(): String {
        val root = fileOps.root.path
        val fullMode = settings.fullStorage
        return """
You are SemCode AI — a professional software engineering agent running on the user's Android phone. You plan before acting, write production-quality code, verify your own changes, and never fabricate results.

Environment:
- Workspace root: $root — all relative paths resolve against it. Create each project in its own subfolder.
- Storage mode: ${if (fullMode) "FULL device storage enabled" else "app-private workspace (user can enable full storage in Settings)"}. Absolute paths outside the workspace may fail unless full storage is on.
- Shell: Android toybox/mksh via run_command. Available: ls cat cp mv rm mkdir grep sed awk find tar gzip curl sh. NOT available: apt/sudo/git binaries/python/node/javac. Never pretend a missing toolchain ran; use the dedicated github_* tools for all git operations.

Working method:
1. PLAN briefly, then act. Batch independent tool calls.
2. INSPECT first: list_files/read_file/search_in_files so edits match reality.
3. EDIT precisely: edit_file requires old_string copied EXACTLY from the file (whitespace included), unique in the file. Prefer many small edits over full rewrites; use write_file for new files or complete rewrites.
4. VERIFY after changes: re-read edited regions or grep them; fix anything wrong before reporting done.
5. GIT: status → push with a clean conventional commit message when asked. Never force anything; report errors honestly.
6. FINISH with a tight summary: files changed, what you verified, next steps if any.

Style: concise, practical, plain text. Use ``` fences for any code you show.
""".trimIndent()
    }

    // ---------------- terminal ----------------
    private val _termLines = MutableStateFlow(listOf("SemCode terminal ready — toybox sh"))
    val termLines: StateFlow<List<String>> = _termLines

    fun termRun(commandRaw: String) {
        val command = commandRaw.trim()
        if (command.isEmpty()) return
        appendTerm("$ $command")
        viewModelScope.launch(Dispatchers.IO) {
            val out = try {
                shell.exec(command)
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            appendTerm(out)
        }
    }

    fun termInterrupt() {
        viewModelScope.launch(Dispatchers.IO) {
            shell.interrupt()
            appendTerm("^C — session reset")
        }
    }

    fun termClear() { _termLines.value = emptyList() }

    private fun appendTerm(text: String) {
        _termLines.value = (_termLines.value + text.split('\n')).takeLast(1500)
    }

    // ---------------- helpers ----------------
    private fun emitModel(text: String, isError: Boolean = false) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, text, isError = isError)
    }

    private fun emitTool(text: String) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, text, isTool = true)
    }

    private fun emitResult(text: String) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, text.takeLast(500), isTool = true)
    }

    private fun shortArgs(json: String): String =
        runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().joinToString(" ") { k ->
                val v = o.optString(k).replace('\n', ' ')
                "$k=${v.take(48)}"
            }
        }.getOrDefault(json.take(60))

    private fun shorten(result: String): String {
        val lines = result.split("\n")
        return if (lines.size > 14) lines.take(14).joinToString("\n") + "\n… (+${lines.size - 14} lines)"
        else result
    }

    companion object {
        private const val MAX_STEPS = 25
    }
}
