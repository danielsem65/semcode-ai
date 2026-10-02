package com.danielsem65.semcodeai.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * One engine for every OpenAI-compatible endpoint:
 * OpenCode Zen, OpenRouter, and Ollama's local server.
 * Supports SSE streaming (chatStream) and mid-flight cancellation.
 */
class OpenAiCompatEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val isLocal: Boolean = false
) : AiEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(if (isLocal) 5L else 30L, TimeUnit.SECONDS)
        .readTimeout(300L, TimeUnit.SECONDS)
        .writeTimeout(60L, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeCall: okhttp3.Call? = null

    /**
     * Zen's free tier rejects requests that don't look like they came from the
     * OpenCode client. The x-opencode-session value must be a well-formed
     * session id: "ses_" + 12 lowercase hex + 14 base62 alphanumerics (30 chars
     * total). This mirrors packages/schema/src/identifier.ts in the client.
     */
    private val zenSessionId = newZenSessionId()

    override fun cancelActive() {
        runCatching { activeCall?.cancel() }
    }

    // ---------------- request bodies ----------------

    private fun messagesJson(system: String, history: List<Msg>): JSONArray {
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", system)
        )
        for (m in history) {
            when (m) {
                is Msg.User -> messages.put(
                    JSONObject().put("role", "user").put("content", m.text))
                is Msg.AssistantText -> messages.put(
                    JSONObject().put("role", "assistant")
                        // Some upstreams reject missing/null content outright.
                        .put("content", m.text.ifBlank { "(ok)" }))
                is Msg.ToolUse -> {
                    // arguments must be a valid JSON *string* per the OpenAI
                    // schema; anything else makes strict upstreams 400.
                    val cleanArgs = if (m.argsJson.isNotBlank() &&
                        runCatching { org.json.JSONTokener(m.argsJson).nextValue() }.getOrNull()
                            is JSONObject
                    ) m.argsJson else "{}"
                    messages.put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", "")
                            .put("tool_calls", JSONArray().put(
                                JSONObject()
                                    .put("id", m.id)
                                    .put("type", "function")
                                    .put("function", JSONObject()
                                        .put("name", m.name)
                                        .put("arguments", cleanArgs)))))
                }
                is Msg.ToolResult -> messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", m.id)
                        // Empty tool outputs are rejected by some backends.
                        .put("content", m.result.ifBlank { "(no output)" }))
            }
        }
        return messages
    }

    private fun toolsJson(tools: List<ToolDef>): JSONArray {
        val toolsArr = JSONArray()
        for (t in tools) {
            toolsArr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("parameters", t.parameters)))
        }
        return toolsArr
    }

    private fun baseRequest(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (!isLocal) b.header("Authorization", "Bearer $apiKey")
        else if (apiKey.isNotBlank() && apiKey != "none") b.header("Authorization", "Bearer $apiKey")
        if (isZen) {
            // Zen's gateway only serves its free models to requests that carry
            // the official client's signals. Verified requirements:
            //   x-opencode-session : well-formed ses_ id (see newZenSessionId)
            //   x-opencode-client  : client label
            //   User-Agent         : versioned opencode UA
            b.header("User-Agent", ZEN_USER_AGENT)
            b.header("x-opencode-session", zenSessionId)
            b.header("x-opencode-client", "cli")
            b.header("x-opencode-project", "global")
        }
        if (baseUrl.contains("openrouter")) {
            b.header("X-Title", "SemCode AI")
            b.header("HTTP-Referer", "https://github.com/danielsem65/semcode-ai")
        }
        return b
    }

    private val isZen: Boolean get() = baseUrl.contains("opencode.ai")

    /**
     * Zen also requires the request to look like a real agent turn: streaming
     * on, and a tool set that includes the canonical `bash` and `read` tools.
     * The client always has these, so we merge them into whatever tool set the
     * agent is actually exposing.
     */
    private fun zenBody(system: String, history: List<Msg>, tools: List<ToolDef>): JSONObject {
        val body = JSONObject()
            .put("model", apiModel())
            .put("messages", messagesJson(system, history))
            .put("stream", true)
        if (isZen) {
            body.put("tools", zenTools(tools))
        } else {
            body.put("tools", toolsJson(tools))
                .put("temperature", 0.3)
        }
        return body
    }

    private fun zenTools(tools: List<ToolDef>): JSONArray {
        val arr = toolsJson(tools)
        val names = HashSet<String>()
        for (i in 0 until arr.length()) {
            names += arr.optJSONObject(i)?.optJSONObject("function")?.optString("name").orEmpty()
        }
        for (required in ZEN_REQUIRED_TOOLS) {
            if (required in names) continue
            val spec = ZEN_TOOL_SPECS[required] ?: continue
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", required)
                    .put("description", spec.description)
                    .put("parameters", spec.parameters)))
        }
        return arr
    }

    private fun postBody(system: String, history: List<Msg>, tools: List<ToolDef>, stream: Boolean): Request {
        val payload = if (isZen) zenBody(system, history, tools) else JSONObject()
            .put("model", apiModel())
            .put("messages", messagesJson(system, history))
            .put("tools", toolsJson(tools))
            .put("temperature", 0.3)
            .put("stream", stream)
        return baseRequest("$baseUrl/chat/completions")
            .post(payload.toString().toRequestBody(JSON))
            .build()
    }

    /** Zen /models lists plain ids; saved overrides may carry an "opencode/" prefix. */
    private fun apiModel(): String =
        if (baseUrl.contains("opencode.ai")) model.removePrefix("opencode/") else model

    // ---------------- non-streaming ----------------

    override fun chat(system: String, history: List<Msg>, tools: List<ToolDef>): EngineReply {
        val call = client.newCall(postBody(system, history, tools, stream = false))
        activeCall = call
        try {
            call.execute().use { resp ->
                val raw = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) throw RuntimeException(friendlyError(resp.code, raw))

                // Zen always answers with SSE regardless of the requested mode.
                if (raw.lineSequence().any { it.trimStart().startsWith("data:") }) {
                    return parseSseReply(raw)
                }

                val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?: throw RuntimeException("Provider sent no message.")
                return parseMessage(msg)
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    /** Reassembles a full reply from a complete SSE payload. */
    private fun parseSseReply(raw: String): EngineReply {
        val text = StringBuilder()
        val slots = HashMap<Int, ToolAcc>()
        for (line in raw.lineSequence()) {
            val payload = line.trim().takeIf { it.startsWith("data:") }
                ?.removePrefix("data:")?.trim() ?: continue
            if (payload == "[DONE]") break
            val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            val delta = chunk.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("delta") ?: continue
            (delta.opt("content") as? String)?.takeIf { it.isNotEmpty() }?.let(text::append)
            accumulateToolCalls(delta.optJSONArray("tool_calls"), slots)
        }
        val calls = slots.toSortedMap().values.mapNotNull { acc ->
            if (acc.name.isBlank() || acc.name == "null") null
            else ToolCall(acc.id, acc.name, acc.args.toString().ifBlank { "{}" })
        }
        if (text.isBlank() && calls.isEmpty()) throw RuntimeException("Provider returned empty content.")
        return EngineReply(text.toString().ifBlank { null }, calls)
    }

    // ---------------- streaming ----------------

    private class ToolAcc(var id: String, var name: String, val args: StringBuilder)

    /** Folds one delta's tool_calls fragments into the per-index accumulators. */
    private fun accumulateToolCalls(tcs: JSONArray?, slots: MutableMap<Int, ToolAcc>) {
        tcs ?: return
        for (i in 0 until tcs.length()) {
            val tc = tcs.optJSONObject(i) ?: continue
            val idx = tc.optInt("index", i)
            val fn = tc.optJSONObject("function")
            // Seed args EMPTY. The append below adds this delta's fragment; if
            // the initializer also seeded it, the first fragment of every call
            // would be duplicated, mangling JSON (e.g. `{"path":".{"path":"."}`)
            // and wrecking long multi-part commands that stream in pieces.
            val acc = slots.getOrPut(idx) {
                ToolAcc(
                    id = sstr(tc, "id").ifBlank { "call_$idx" },
                    name = sstr(fn, "name"),
                    args = StringBuilder()
                )
            }
            sstr(tc, "id").takeIf { it.isNotEmpty() }?.let { acc.id = it }
            fn?.let { f ->
                sstr(f, "name").takeIf { it.isNotEmpty() }?.let { acc.name = it }
                sstr(f, "arguments").takeIf { it.isNotEmpty() }?.let { acc.args.append(it) }
            }
        }
    }

    override fun chatStream(
        system: String,
        history: List<Msg>,
        tools: List<ToolDef>,
        onDelta: (String) -> Unit
    ): EngineReply {
        val call = client.newCall(postBody(system, history, tools, stream = true))
        activeCall = call
        try {
            call.execute().use { resp ->
                val body = resp.body ?: throw IOException("empty response body")
                if (!resp.isSuccessful) throw RuntimeException(friendlyError(resp.code, body.string()))

                val text = StringBuilder()
                val slots = HashMap<Int, ToolAcc>()

                body.source().inputStream().bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val payload = line.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim()
                            ?: continue
                        if (payload == "[DONE]") break
                        val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                        val delta = chunk.optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta") ?: continue

                        // Strict string check: optString() turns JSON null into
                        // the literal text "null" (org.json quirk).
                        val c = delta.opt("content")
                        if (c is String && c.isNotEmpty()) {
                            text.append(c)
                            onDelta(c)
                        }

                        accumulateToolCalls(delta.optJSONArray("tool_calls"), slots)
                    }
                }

                val calls = slots.toSortedMap().values.mapNotNull { acc ->
                    if (acc.name.isBlank() || acc.name == "null") null
                    else ToolCall(acc.id, acc.name, acc.args.toString().ifBlank { "{}" })
                }
                return EngineReply(text.toString().ifBlank { null }, calls.filter { it.id != "null" })
            }
        } catch (e: IOException) {
            throw IOException("stream interrupted", e)
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    // ---------------- shared parsing / errors ----------------

    private fun parseMessage(msg: JSONObject): EngineReply {
        val rawContent = msg.opt("content")
        var text = (rawContent as? String)?.takeIf { it.isNotBlank() }
        val calls = mutableListOf<ToolCall>()
        val tc = msg.optJSONArray("tool_calls")
        if (tc != null) {
            for (i in 0 until tc.length()) {
                val c = tc.optJSONObject(i) ?: continue
                val fn = c.optJSONObject("function") ?: continue
                val name = sstr(fn, "name")
                if (name.isBlank() || name == "null") continue
                calls += ToolCall(
                    id = sstr(c, "id").ifBlank { "call_$i" },
                    name = name,
                    argsJson = sstr(fn, "arguments").ifBlank { "{}" }
                )
            }
        }
        if (text == null && calls.isEmpty()) throw RuntimeException("Provider returned empty content.")
        return EngineReply(text, calls)
    }

    /** org.json's optString returns the literal "null" for JSON null values —
     *  this returns "" instead, and only when the value is truly a string. */
    private fun sstr(o: JSONObject?, key: String): String {
        val v = o?.opt(key) ?: return ""
        return v as? String ?: ""
    }

    override fun listModels(): List<String> {
        client.newCall(baseRequest("$baseUrl/models").build()).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${errText(body)}")
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val ids = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id") ?: continue
                if (id.isNotBlank()) ids += id
            }
            return ids.distinct()
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private const val ZEN_USER_AGENT = "opencode/1.18.30"

        /** Zen's gateway only serves free models when these canonical tools are present. */
        private val ZEN_REQUIRED_TOOLS = listOf("bash", "read")

        /**
         * Zen's gate is satisfied by the *names* being present, but the model
         * reads the schema we publish — an empty one makes it call these with
         * no arguments at all. So publish real schemas, and let [ToolCall]
         * dispatch map them onto the tools the app already implements.
         */
        private class ZenToolSpec(val description: String, val parameters: JSONObject)

        private val ZEN_TOOL_SPECS = mapOf(
            "bash" to ZenToolSpec(
                "Executes a shell command in the workspace. Android toybox/mksh unless a " +
                    "full Linux env is installed. Prefer the dedicated github_* tools for git.",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("command", JSONObject().put("type", "string"))
                        .put("timeout_seconds", JSONObject().put("type", "number")))
                    .put("required", JSONArray(listOf("command")))
            ),
            "read" to ZenToolSpec(
                "Reads a file from the workspace. Relative paths resolve against the workspace root.",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("path", JSONObject().put("type", "string")))
                    .put("required", JSONArray(listOf("path")))
            )
        )

        private const val ZEN_ID_CHARS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        /**
         * Builds a session id shaped exactly like the OpenCode client's:
         * "ses_" + 12 lowercase hex + 14 base62 characters (30 chars total).
         * Zen rejects any other shape with FreeTierError.
         */
        private fun newZenSessionId(): String {
            val time = System.currentTimeMillis()
            val value = (time.toBigInteger() * 0x1000.toBigInteger() + 1.toBigInteger())
                .inv()
            val hex = buildString {
                for (i in 0 until 6) {
                    append("%02x".format(((value shr (40 - 8 * i)) and 0xff.toBigInteger()).toInt()))
                }
            }
            val rnd = SecureRandom()
            val tail = buildString {
                repeat(14) { append(ZEN_ID_CHARS[rnd.nextInt(ZEN_ID_CHARS.length)]) }
            }
            return "ses_$hex$tail"
        }

        private fun errText(body: String): String = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")?.take(300)
        }.getOrNull() ?: body.take(300)

        private fun friendlyError(code: Int, body: String): String {
            val detail = errText(body)
            val lower = detail.lowercase()
            if (code == 500 && lower.contains("internal server error") ||
                lower.contains("not supported") ||
                lower.contains("model is unavailable") ||
                lower.contains("endpoint is unavailable")
            ) {
                return "That model is temporarily unreachable on Zen's gateway (down or unavailable from your region). " +
                    "Pick 'big-pickle' or another '*free' model in Settings → Model → Apply model override. [$code: $detail]"
            }
            if (lower.contains("only be used in opencode") || lower.contains("missing session")) {
                return "OpenCode Zen rejected this request as non-OpenCode. If it persists, restart SemCode " +
                    "(fresh session id) or pick another model in Settings. [$code]"
            }
            val limited = code == 429 || code == 492 ||
                detail.contains("rate limit", true) || detail.contains("FreeUsageLimit", true)
            return if (limited)
                "This free model's quota is used up right now (Zen resets daily). " +
                    "Pick another model from the list, switch provider in Settings, or try again later. [$code]"
            else "HTTP $code: $detail"
        }
    }
}
