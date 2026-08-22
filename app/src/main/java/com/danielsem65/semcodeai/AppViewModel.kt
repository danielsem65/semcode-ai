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
    val isError: Boolean = false,
    val proposalId: String? = null,
    val id: String = java.util.UUID.randomUUID().toString()
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

    // ---------------- streaming / stop ----------------
    @Volatile private var activeEngine: com.danielsem65.semcodeai.ai.AiEngine? = null
    private val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Text arriving token-by-token for the in-flight reply. */
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText

    // ---------------- approvals (ask-before-changes) ----------------
    data class PendingApproval(
        val id: String,
        val tool: String,
        val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>
    )

    private val _pendingApprovals = MutableStateFlow<Map<String, PendingApproval>>(emptyMap())
    val pendingApprovals: StateFlow<Map<String, PendingApproval>> = _pendingApprovals

    fun decideProposal(id: String, approve: Boolean) {
        _pendingApprovals.value[id]?.deferred?.complete(approve)
    }

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

        // Launch on the application-scoped scope so the run survives
        // Activity/ViewModel destruction; the foreground service keeps the
        // process alive while it works.
        val appCtx = getApplication<SemApp>()
        appCtx.runStarted()
        appCtx.runScope.launch {
            var failed = false
            try {
                runAgent(provider, key, model)
            } catch (e: Exception) {
                failed = true
                emitModel("Error: ${e.message ?: "request failed"}", isError = true)
            } finally {
                persist()
                _busy.value = false
                _stepText.value = ""
                _liveText.value = ""
                activeEngine = null

                // Notify when the user isn't looking at the app
                if (!com.danielsem65.semcodeai.core.AppForeground.foreground) {
                    com.danielsem65.semcodeai.core.Notify.post(
                        appCtx,
                        (System.currentTimeMillis() and 0x7fffffff).toInt(),
                        if (failed) "SemCode AI — task failed" else "SemCode AI — task finished",
                        "${currentProjectName()}: ${if (failed) "the run ended with an error" else "the agent is done, open the app to review"}"
                    )
                }
                appCtx.runEnded()
            }
        }
    }

    private suspend fun runAgent(provider: Provider, apiKey: String, model: String) {
        val engine = if (provider.id == "device") {
            val modelPath = settings.deviceModelPath
            if (modelPath.isBlank()) throw RuntimeException(
                "No on-device model selected. Settings → On-device (offline) → Browse → pick a .gguf file."
            )
            _stepText.value = "preparing model…"
            com.danielsem65.semcodeai.core.LlamaServer.ensureStarted(
                getApplication(), modelPath
            ) { progress -> _stepText.value = progress }
            com.danielsem65.semcodeai.ai.OpenAiCompatEngine(
                "http://127.0.0.1:${com.danielsem65.semcodeai.core.LlamaServer.PORT}/v1",
                "none", "local-model", isLocal = true
            )
        } else {
            Providers.create(provider, apiKey, model)
        }
        activeEngine = engine
        stopRequested.set(false)
        var steps = 0
        var failStreak = 0
        var lastFailSig = ""

        while (steps < MAX_STEPS) {
            if (stopRequested.get()) {
                emitModel("_Stopped._")
                return
            }
            steps++
            _stepText.value = "step $steps/$MAX_STEPS"
            maybeCompact(engine)

            val reply: EngineReply = withContext(Dispatchers.IO) {
                _liveText.value = ""
                var r: EngineReply? = null
                try {
                    // Throttle UI updates — appending per token would re-render
                    // the live bubble thousands of times and stall the app.
                    val sb = StringBuilder()
                    var lastFlush = 0L
                    r = engine.chatStream(
                        systemPrompt(), apiHistory.toList(),
                        com.danielsem65.semcodeai.ai.Tools.all()
                    ) { delta ->
                        synchronized(sb) {
                            sb.append(delta)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastFlush >= LIVE_FLUSH_MS) {
                                lastFlush = now
                                _liveText.value = sb.toString()
                            }
                        }
                    }
                    _liveText.value = sb.toString()
                } finally {
                    _liveText.value = ""
                }
                requireNotNull(r) { "no reply" }
            }

            if (reply.calls.isEmpty()) {
                emitModel(reply.text ?: "(no answer)")
                return
            }
            if (!reply.text.isNullOrBlank()) emitModel(reply.text)

            for (call in reply.calls) {
                if (stopRequested.get()) {
                    apiHistory += Msg.ToolResult(call.id, call.name, "STOPPED_BY_USER")
                    continue
                }
                apiHistory += Msg.ToolUse(call.id, call.name, call.argsJson)
                emitTool("${call.name} ${shortArgs(call.argsJson)}")
                _stepText.value = "step $steps/$MAX_STEPS · ${call.name}"
                val result = dispatch(call.name, call.argsJson)
                emitResult(shorten(result))
                apiHistory += Msg.ToolResult(call.id, call.name, result.take(20_000))

                // Loop guard: the same failing call three times in a row means
                // the model is stuck — cut it off instead of burning all steps.
                if (result.startsWith("ERROR")) {
                    val sig = "${call.name}:${call.argsJson}"
                    if (sig == lastFailSig) failStreak++ else { failStreak = 1; lastFailSig = sig }
                    if (failStreak >= 3) {
                        emitModel(
                            "**Stopped: `${call.name}` keeps failing with identical arguments.**\n\n" +
                                "Last error: ${result.take(300)}\n\n" +
                                "I paused here so we don't loop. Tell me how to proceed."
                        )
                        return
                    }
                } else {
                    failStreak = 0
                    lastFailSig = ""
                }
            }
        }
        if (!stopRequested.get()) {
            emitModel("Reached the $MAX_STEPS-step limit — say \"continue\" and I'll keep going.")
        } else {
            emitModel("_Stopped._")
        }
    }

    /** Stops the current run at the next boundary and aborts in-flight requests. */
    fun stopGeneration() {
        stopRequested.set(true)
        activeEngine?.cancelActive()
        _pendingApprovals.value.values.forEach { it.deferred.complete(false) }
    }

    private suspend fun dispatch(name: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        return withContext(Dispatchers.IO) {
            try {
                if (name in APPROVAL_TOOLS && !gateIfNeeded(name, argsJson)) {
                    "DENIED_BY_USER — the user rejected this action. Ask what they'd prefer or continue without it."
                } else when (name) {
                    "run_command" -> activeShell().exec(
                        args.optString("command", ""),
                        args.optLong("timeout_seconds", 30).coerceIn(1, 600)
                    )
                    else ->
                        if (name.startsWith("github_")) githubDispatch(name, args)
                        else fileOps.execute(name, args)
                }
            } catch (e: Exception) {
                // Small models repeat a failing call verbatim unless the error
                // tells them exactly what went wrong and how to fix it.
                val required = runCatching {
                    com.danielsem65.semcodeai.ai.Tools.all()
                        .firstOrNull { it.name == name }?.parameters
                        ?.optJSONArray("required")
                        ?.let { r -> (0 until r.length()).map { r.optString(it) } }
                }.getOrNull().orEmpty()
                buildString {
                    append("ERROR in $name: ${e.message ?: "call failed"}. ")
                    if (required.isEmpty()) {
                        append("Check your arguments are valid JSON with the correct keys. ")
                    } else {
                        append("Required argument(s): ${required.joinToString(", ")}. ")
                        append(
                            "Call again as {\"name\": \"$name\", \"arguments\": {" +
                                required.joinToString(", ") { "\"$it\": \"...\"" } + "}}. "
                        )
                    }
                    append("Fix the arguments — do NOT repeat the identical call.")
                }
            }
        }
    }

    /**
     * When "Ask before changes" is on, destructive tools pause the agent until
     * the user approves or denies a preview card in chat.
     */
    private suspend fun gateIfNeeded(name: String, argsJson: String): Boolean {
        if (!settings.askBeforeChanges || name !in APPROVAL_TOOLS) return true

        val id = "ap${System.nanoTime()}"
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        _pendingApprovals.value = _pendingApprovals.value + (id to PendingApproval(id, name, deferred))

        val preview = runCatching { fileOps.previewDiff(name, argsJson) }
            .getOrDefault("(preview unavailable)")
        emitModel(
            "**✋ Approval needed · `$name`**\n```diff\n${preview.take(2500)}\n```",
            proposalId = id
        )

        val ok = try { deferred.await() } catch (_: Exception) { false }
        _pendingApprovals.value = _pendingApprovals.value - id

        _messages.value = _messages.value.map {
            if (it.proposalId == id)
                it.copy(
                    proposalId = null,
                    text = it.text.replaceFirst(
                        "**✋ Approval needed",
                        if (ok) "**✅ Approved & applied" else "**🚫 Denied"
                    )
                )
            else it
        }
        return ok
    }

    private suspend fun githubDispatch(name: String, args: JSONObject): String =
        withContext(Dispatchers.IO) {
            val token = settings.githubToken
            when (name) {
                "github_clone" -> GitHubSync.clone(fileOps, token, args.optString("repo"), args.optString("path"))
                "github_status" -> GitHubSync.status(fileOps, token, args.getString("path"))
                "github_push" -> GitHubSync.push(
                    fileOps, token, args.getString("path"),
                    args.optString("message", "Update from SemCode AI").ifBlank { "Update from SemCode AI" },
                    args.optBoolean("force", false)
                )
                "github_pull" -> GitHubSync.pull(fileOps, token, args.getString("path"))
                "github_create_repo" -> GitHubSync.createRepo(token, args.getString("name"), args.optBoolean("private", false))
                else -> "ERROR: unknown $name"
            }
        }

    // ---------------- context management ----------------

    private fun Msg.approxChars(): Int = when (this) {
        is Msg.User -> text.length
        is Msg.AssistantText -> text.length
        is Msg.ToolUse -> argsJson.length + 40
        is Msg.ToolResult -> result.length
    }

    /**
     * Long-project compaction: when the API history grows past the char budget,
     * summarize everything except the last few messages into a single handover
     * note and continue from there.
     */
    private suspend fun maybeCompact(engine: com.danielsem65.semcodeai.ai.AiEngine) {
        val total = apiHistory.sumOf { it.approxChars() }
        if (total < COMPACT_ABOVE_CHARS || apiHistory.size < COMPACT_KEEP + 4) return

        val head = apiHistory.dropLast(COMPACT_KEEP)
        val tail = apiHistory.takeLast(COMPACT_KEEP)

        val transcript = StringBuilder()
        for (m in head) {
            transcript.append(
                when (m) {
                    is Msg.User -> "USER: ${m.text.take(1200)}\n"
                    is Msg.AssistantText -> "ASSISTANT: ${m.text.take(1200)}\n"
                    is Msg.ToolUse -> "TOOL ${m.name} ${m.argsJson.take(300)}\n"
                    is Msg.ToolResult -> "RESULT: ${m.result.take(600)}\n"
                }
            )
        }

        _stepText.value = "compacting context…"
        val summary = runCatching {
            engine.chat(
                "You compress coding-agent worklogs. Summarize the following earlier " +
                    "conversation into a compact handover for the next session: project names, " +
                    "key files, decisions made, what is done, current task, next steps. " +
                    "Max 250 words, plain text.",
                listOf(Msg.User(transcript.toString().take(30_000))),
                emptyList()
            ).text ?: ""
        }.getOrDefault("")

        if (summary.isBlank()) return
        apiHistory.clear()
        apiHistory += Msg.AssistantText("[Context compacted — summary of earlier work]\n$summary")
        apiHistory += tail
    }

    private fun systemPrompt(): String {
        val root = fileOps.root.path
        val fullMode = settings.fullStorage
        val app = getApplication<SemApp>()
        val linuxOn = _termMode.value == "linux" && app.linuxEnv.isInstalled()
        val memFile = java.io.File(fileOps.root, "AGENTS.md")
        val memory = if (memFile.exists())
            runCatching { memFile.readText() }.getOrDefault("").take(4000) else ""
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

${if (memory.isNotBlank())
    "Project memory (AGENTS.md):\n$memory\n"
else
    "Project memory: AGENTS.md does not exist yet at the workspace root.\n"}

Working method:
1. PLAN briefly, then act. Batch independent tool calls.
2. INSPECT first: list_files/read_file/search_in_files so edits match reality.
3. EDIT precisely: edit_file requires old_string copied EXACTLY from the file (whitespace included); prefer many small edits over full rewrites; use write_file for new files or complete rewrites.
4. MEMORY: AGENTS.md at the workspace root stores durable facts (architecture, decisions, conventions, current goal). Create/update it with write_file whenever such facts change or are learned.
5. VERIFY after changes: re-read edited regions or grep them; fix anything wrong before reporting done.
6. GIT: status → push with a clean conventional commit message when asked. Never force anything; report errors honestly.
7. FINISH with a tight summary: files changed, what you verified, next steps if any.

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
        // Self-heal: a previously installed but broken Linux env is silently
        // re-downloaded in the background (Alpine is ~4 MB).
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<SemApp>()
            if (app.linuxEnv.detectBroken()) {
                appendTerm("Linux environment looks broken — repairing in background…")
                runCatching {
                    app.invalidateLinuxSession()
                    app.linuxEnv.repair { }
                }.onSuccess { ok ->
                    appendTerm(
                        if (ok) "Linux environment repaired ✓ — Shell tab → Linux."
                        else "Linux repair finished with warnings — reinstall in Settings if the shell misbehaves."
                    )
                }.onFailure {
                    appendTerm("Linux repair failed: ${it.message?.take(120)} — Settings → Reinstall.")
                }
            }
        }
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
    private fun emitModel(text: String, isError: Boolean = false, proposalId: String? = null) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, text, isError = isError, proposalId = proposalId)
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
        private const val LIVE_FLUSH_MS = 150L
        private const val COMPACT_ABOVE_CHARS = 60_000
        private const val COMPACT_KEEP = 10

        private val APPROVAL_TOOLS = setOf(
            "write_file", "edit_file", "delete_path", "move_path", "copy_path", "run_command"
        )
    }
}
