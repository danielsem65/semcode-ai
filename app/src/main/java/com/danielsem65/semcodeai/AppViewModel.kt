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

    private val _activeProviderId = MutableStateFlow("")
    val activeProviderId: StateFlow<String> = _activeProviderId

    private val apiHistory = mutableListOf<Msg>()

    // ---------------- projects ----------------
    private val projectStore get() = getApplication<SemApp>().projectStore

    private val _projects = MutableStateFlow(projectStore.list())
    val projects: StateFlow<List<com.danielsem65.semcodeai.core.Project>> = _projects

    private val _projectId = MutableStateFlow<String?>(null)
    val projectId: StateFlow<String?> = _projectId

    init { refreshStatus() }

    fun currentProjectName(): String =
        _projectId.value?.let { pid -> _projects.value.firstOrNull { it.id == pid }?.name }
            ?: "New chat"

    /** Persist the current conversation (call whenever its content changes). */
    private fun persist() {
        val pid = _projectId.value ?: return
        projectStore.save(pid, currentProjectName(), _messages.value.toList(), apiHistory.toList())
        _projects.value = projectStore.list()
    }

    fun newChat() {
        persist()
        _projectId.value = null
        _messages.value = emptyList()
        apiHistory.clear()
        _stepText.value = ""
    }

    fun openProject(id: String) {
        if (_busy.value) return
        persist()
        val data = projectStore.load(id)
        _projectId.value = id
        _messages.value = data?.first ?: emptyList()
        apiHistory.clear()
        if (data != null) apiHistory += data.second
    }

    fun renameProject(id: String, name: String) {
        projectStore.rename(id, name)
        _projects.value = projectStore.list()
    }

    fun deleteProject(id: String) {
        projectStore.delete(id)
        _projects.value = projectStore.list()
        if (_projectId.value == id) {
            _projectId.value = null
            _messages.value = emptyList()
            apiHistory.clear()
        }
    }

    fun activeProvider(): Provider = Providers.byId(settings.activeProviderId)

    fun effectiveModel(p: Provider = activeProvider()): String =
        settings.modelOverride.ifBlank { p.defaultModel }

    fun refreshStatus() {
        val p = activeProvider()
        val keyOk = p.isLocal || settings.hasKeyFor(p.id)
        _activeProviderId.value = p.id
        _statusLine.value =
            "${p.displayName} · ${effectiveModel(p)}" + if (!keyOk) "  ⚠ no key" else ""
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

        // Attach this conversation to a persisted project (named from the first message)
        if (_projectId.value == null || !projectStore.exists(_projectId.value!!)) {
            val title = userText.replace('\n', ' ').trim().take(42).ifBlank { "New chat" }
            _projectId.value = projectStore.create(title)
            _projects.value = projectStore.list()
        }

        _busy.value = true
        _stepText.value = "thinking…"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                runAgent(provider, key, model)
            } catch (e: Exception) {
                emitModel("Error: ${e.message ?: "request failed"}", isError = true)
            } finally {
                persist()
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
                    "run_command" -> activeShell().exec(
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
        val app = getApplication<SemApp>()
        val linuxOn = _termMode.value == "linux" && app.linuxEnv.isInstalled()
        val shellLine = if (linuxOn)
            "- Shell: FULL LINUX (${app.linuxEnv.installedLabel()} via proot) through run_command. " +
                "apt/apk install, git, python3 and the distro's toolchain are available. " +
                "The workspace is mounted at /workspace. Install packages freely; state persists."
        else
            "- Shell: Android toybox/mksh via run_command. Available: ls cat cp mv rm mkdir grep sed awk find tar gzip curl sh. " +
                "NOT available: apt/sudo/git binaries/python/node/javac. Never pretend a missing toolchain ran; " +
                "use the dedicated github_* tools for all git operations. " +
                "(The user can install a full Linux env in Settings for apt/git/python.)"
        return """
You are SemCode AI — a professional software engineering agent running on the user's Android phone. You plan before acting, write production-quality code, verify your own changes, and never fabricate results.

Environment:
- Workspace root: $root — all relative paths resolve against it. Create each project in its own subfolder.
- Storage mode: ${if (fullMode) "FULL device storage enabled" else "app-private workspace (user can enable full storage in Settings)"}. Absolute paths outside the workspace may fail unless full storage is on.
$shellLine

Working method:
1. PLAN briefly, then act. Batch independent tool calls.
2. INSPECT first: list_files/read_file/search_in_files so edits match reality.
3. EDIT precisely: edit_file requires old_string copied EXACTLY from the file (whitespace included), prefer many small edits over full rewrites; use write_file for new files or complete rewrites.
4. VERIFY after changes: re-read edited regions or grep them; fix anything wrong before reporting done.
5. GIT: status → push with a clean conventional commit message when asked. Never force anything; report errors honestly.
6. FINISH with a tight summary: files changed, what you verified, next steps if any.

Style: concise, practical, plain text. Use ``` fences for any code you show.
""".trimIndent()
    }

    // ---------------- terminal ----------------
    private val _termLines = MutableStateFlow(listOf(""))
    val termLines: StateFlow<List<String>> = _termLines

    private val _termMode = MutableStateFlow("android")
    val termMode: StateFlow<String> = _termMode

    init {
        appendTerm(
            "SemCode terminal ready — Android toybox sh.\n" +
                "Type help for commands. Install a full Linux env in Settings for apt/git/python."
        )
    }

    fun linuxInstalled(): Boolean = getApplication<SemApp>().linuxEnv.isInstalled()

    fun linuxLabel(): String = getApplication<SemApp>().linuxEnv.installedLabel()

    fun termCwd(): String = runCatching { activeShell().cwd }.getOrDefault(fileOps.root.path)

    /** Switch terminal (and the AI's run_command) between the Android shell and proot Linux. */
    fun termUseMode(mode: String) {
        if (mode == _termMode.value) return
        val app = getApplication<SemApp>()
        if (mode == "linux" && !app.linuxEnv.isInstalled()) {
            appendTerm("Linux not installed yet — go to Settings → Linux environment → Install.")
            return
        }
        _termMode.value = mode
        _termLines.value = emptyList()
        appendTerm(
            if (mode == "linux")
                "Linux (${app.linuxEnv.installedLabel()}) via proot — real apt/apk, git, python3." +
                    "\n/workspace = your SemCode workspace. Type help for tips."
            else
                "Android toybox sh. Type help for commands."
        )
    }

    private fun activeShell(): com.danielsem65.semcodeai.core.ShellSession =
        if (_termMode.value == "linux") getApplication<SemApp>().linuxShell()
        else shell

    fun termRun(commandRaw: String) {
        val command = commandRaw.trim()
        if (command.isEmpty()) return
        appendTerm("$ $command")
        val lower = command.lowercase()
        if (lower == "help" || lower == "?") {
            appendTerm(TERMINAL_HELP)
            return
        }
        if (lower == "clear" || lower == "cls") {
            _termLines.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val out = try {
                activeShell().exec(command)
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

    private val TERMINAL_HELP = """
        |This is Android's built-in shell (mksh + toybox) — a real but minimal
        |UNIX shell. No bash, no apt/pkg manager, no python/node.
        |
        |FILES     ls  cd  pwd  cat  echo  mkdir  rm  cp  mv  touch  ln  stat
        |          chmod  find  du  df
        |TEXT      grep  sed  awk  head  tail  wc  sort  uniq  tr  cut  xargs
        |ARCHIVE   tar  gzip  gunzip  zip  unzip  base64  md5sum  sha256sum
        |NETWORK   ping  curl  netstat  nslookup   e.g: curl -s https://api.ipify.org
        |SYSTEM    ps  top  uptime  uname -a  getprop  logcat  date  id  whoami
        |ANDROID   am start / pm list packages / dumpsys battery / settings get secure
        |
        |TIPS
        |• 'help' shows this · 'clear' wipes the screen · ⟳ icon kills a hung command
        |• The AI agent shares THIS session — its run_command output appears here,
        |  and your 'cd' persists for it too.
        |• In LINUX mode the workspace is /workspace; apk/apt/git/python3 work.
        |• Full docs per command:  ls --help
    """.trimMargin()

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
