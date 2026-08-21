package com.danielsem65.semcodeai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielsem65.semcodeai.ai.EngineReply
import com.danielsem65.semcodeai.ai.Msg
import com.danielsem65.semcodeai.ai.Provider
import com.danielsem65.semcodeai.ai.Providers
import com.danielsem65.semcodeai.ai.ToolCall
import com.danielsem65.semcodeai.ai.ToolDef
import com.danielsem65.semcodeai.data.SettingsStore
import com.danielsem65.semcodeai.fs.FileOps
import com.danielsem65.semcodeai.git.GitOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ChatMessage(
    val role: Role,
    val text: String,
    val isTool: Boolean = false
) {
    enum class Role { USER, MODEL }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)
    private val session get() = getApplication<SemApp>().session
    private val workspace get() = getApplication<SemApp>().workspace

    // ---------- chat ----------
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _statusLine = MutableStateFlow("")
    val statusLine: StateFlow<String> = _statusLine

    private val apiHistory = mutableListOf<Msg>()

    init { refreshStatus() }

    fun activeProvider(): Provider = Providers.byId(settings.activeProviderId)

    fun effectiveModel(p: Provider = activeProvider()): String =
        settings.modelOverride.ifBlank { p.defaultModel }

    fun refreshStatus() {
        val p = activeProvider()
        val hasKey = settings.hasKeyFor(p.id)
        _statusLine.value = "${p.displayName} · ${effectiveModel(p)}" + if (!hasKey) " · no key!" else ""
    }

    fun clearChat() {
        _messages.value = emptyList()
        apiHistory.clear()
    }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || _busy.value) return

        val provider = activeProvider()
        val key = settings.apiKey(provider.id)
        if (key.isEmpty()) {
            _messages.value += ChatMessage(
                ChatMessage.Role.MODEL,
                "No API key saved for ${provider.displayName}. Open Settings, pick the provider and paste a key.\n(${provider.keyUrl})"
            )
            return
        }

        _messages.value += ChatMessage(ChatMessage.Role.USER, text)
        apiHistory += Msg.User(text)
        _busy.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                runAgent(provider, key, text)
            } catch (e: Exception) {
                emitModel("Error: ${e.message ?: "unknown failure"}")
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun runAgent(provider: Provider, key: String, @Suppress("UNUSED_PARAMETER") lastText: String) {
        val engine = Providers.create(provider, key, effectiveModel(provider))
        var steps = 0
        while (steps < MAX_STEPS) {
            steps++
            val reply: EngineReply = engine.chat(systemPrompt(), apiHistory.toList(), buildTools())

            val calls = reply.calls
            if (calls.isEmpty()) {
                emitModel(reply.text ?: "(no answer)")
                return
            }
            if (!reply.text.isNullOrBlank()) emitModel(reply.text)

            for (call in calls) {
                apiHistory += Msg.ToolCallMsg(call.id, call.name, call.argsJson)
                emitTool("${call.name}(${shortArgs(call.argsJson)})")
                val result = dispatch(call.name, call.argsJson)
                emitResult(shorten(result))
                apiHistory += Msg.ToolResultMsg(call.id, call.name, result.take(20_000))
            }
        }
        emitModel("(stopped after $MAX_STEPS tool rounds — ask me to continue)")
    }

    private fun dispatch(name: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        return try {
            when {
                name == "run_command" -> session.exec(
                    args.optString("command", ""),
                    args.optLong("timeout_seconds", 45).coerceIn(1, 300)
                )
                name.startsWith("git_") -> GitOps.execute(
                    name, args,
                    settings.gitUser.ifBlank { null },
                    settings.gitToken.ifBlank { null }
                )
                else -> FileOps.execute(name, args)
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    // ---------- terminal ----------
    private val _termLines = MutableStateFlow(listOf("-- SemCode terminal — toybox sh --"))
    val termLines: StateFlow<List<String>> = _termLines

    private val _termOpen = MutableStateFlow(false)
    val termOpen: StateFlow<Boolean> = _termOpen

    fun toggleTerm() { _termOpen.value = !_termOpen.value }
    fun closeTerm() { _termOpen.value = false }
    fun clearTerm() { _termLines.value = emptyList() }

    fun termRun(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        appendTerm("$ $cmd")
        viewModelScope.launch(Dispatchers.IO) {
            val out = try { session.exec(cmd) } catch (e: Exception) { "ERROR: ${e.message}" }
            appendTerm(out)
        }
    }

    fun termInterrupt() {
        viewModelScope.launch(Dispatchers.IO) {
            session.interrupt()
            appendTerm("^C — session killed, fresh shell next command")
        }
    }

    private fun appendTerm(text: String) {
        _termLines.value = (_termLines.value + text.split('\n')).takeLast(1500)
    }

    // ---------- AI plumbing ----------
    private fun systemPrompt(): String {
        val base = FileOps.baseDir.absolutePath.trimEnd('/')
        val ws = workspace.path.trimEnd('/')
        return """
You are SemCode AI, an expert software engineering agent running fully on the user's Android phone. You can reason deeply, plan multi-step work, write and refactor code across many files, run commands in the device's real Unix shell, and operate git repositories. Work autonomously and thoroughly until the task is complete.

Environment:
- Workspace for projects: $ws (create project folders here unless told otherwise).
- Shared storage root: $base - all paths resolve relative to it unless absolute.
- Shell is Android toybox/mksh: ls, cat, cp, mv, rm, grep, sed, awk, find, tar, gzip, curl exist. There is NO apt/sudo, and NO python/node/javac by default. Never pretend unavailable toolchains ran.
- Git works through dedicated git_* tools over HTTPS. Pushing requires the user's saved GitHub username + token (Settings); if missing, tell them where to add it.

Method (think before you act):
1. Plan briefly: which files to inspect, change, create, or run.
2. Inspect before editing: list_files/read_file/search_in_files so edits match reality.
3. Make precise edits: edit_file needs an EXACT unique old_string (copy whitespace exactly; include surrounding lines to disambiguate) - or rewrite whole files with write_file.
4. Verify: re-read changed sections or run_command checks (e.g. grep your change). Fix anything broken before finishing.
5. Git workflow when asked: git_status -> git_stage -> git_commit (clear conventional message) -> git_push only if requested. NEVER force-push.
6. Finish with a short summary: what changed (paths), what you verified, and any follow-ups.

Rules: be honest about failures and fix them; never invent file contents or output; batch independent operations in one turn; keep replies tight.
""".trimIndent()
    }

    private fun buildTools(): List<ToolDef> {
        val S = ToolDef.STRING; val B = ToolDef.BOOLEAN; val N = ToolDef.NUMBER
        fun td(name: String, desc: String, vararg props: Pair<String, String>, required: List<String>) =
            ToolDef(name, desc, ToolDef.obj(*props, required = required))

        return listOf(
            td("list_files", "List a directory's entries.", "path" to S, required = listOf("path")),
            td("read_file", "Read a full text file (up to ~500KB).", "path" to S, required = listOf("path")),
            td("write_file", "Create or completely overwrite a text file.", "path" to S, "content" to S, required = listOf("path", "content")),
            td("edit_file", "Replace exact text inside a file. old_string must be unique unless replace_all.",
                "path" to S, "old_string" to S, "new_string" to S, "replace_all" to B, required = listOf("path", "old_string", "new_string")),
            td("search_in_files", "Grep-like content search (file:line:match), skips binaries/.git.",
                "directory" to S, "query" to S, required = listOf("directory", "query")),
            td("search_files", "Find filenames by wildcard (* ?), case-insensitive.",
                "directory" to S, "pattern" to S, required = listOf("directory", "pattern")),
            td("create_folder", "Create a directory tree.", "path" to S, required = listOf("path")),
            td("delete_path", "Permanently delete a file or folder tree.", "path" to S, required = listOf("path")),
            td("copy_path", "Copy file/folder recursively.", "source" to S, "destination" to S, required = listOf("source", "destination")),
            td("move_path", "Move or rename file/folder.", "source" to S, "destination" to S, required = listOf("source", "destination")),
            td("get_file_info", "Path metadata (size, dates, permissions).", "path" to S, required = listOf("path")),
            td("run_command", "Run one command in the persistent device shell (state survives between calls; stdin closed; default timeout 45s).",
                "command" to S, "timeout_seconds" to N, required = listOf("command")),
            td("git_clone", "Clone a repo over HTTPS into a folder.", "url" to S, "path" to S, required = listOf("url", "path")),
            td("git_status", "Show branch + working tree changes.", "path" to S, required = listOf("path")),
            td("git_stage", "Stage changes ('.' = everything incl. deletions).", "path" to S, "pattern" to S, required = listOf("path")),
            td("git_commit", "Commit staged changes.", "path" to S, "message" to S, required = listOf("path", "message")),
            td("git_pull", "Pull and merge from remote.", "path" to S, required = listOf("path")),
            td("git_push", "Push commits (needs GitHub token in Settings).", "path" to S, required = listOf("path"))
        )
    }

    private fun shortArgs(json: String): String = json.take(120)

    private fun shorten(result: String): String {
        val lines = result.split("\n")
        return if (lines.size > 12) lines.take(12).joinToString("\n") + "\n… (+${lines.size - 12} more)"
        else result
    }

    private fun emitModel(text: String) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, text)
    }

    private fun emitTool(text: String) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, "⚙ $text", isTool = true)
    }

    private fun emitResult(text: String) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, text.takeLast(400), isTool = true)
    }

    companion object {
        private const val MAX_STEPS = 25
    }
}
