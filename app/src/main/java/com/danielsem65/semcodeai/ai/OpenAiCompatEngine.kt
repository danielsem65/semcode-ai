package com.danielsem65.semcodeai.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
        if (baseUrl.contains("opencode.ai")) {
            // Zen's upstream gates free models to official OpenCode clients by User-Agent
            b.header("User-Agent", "opencode/1.18.16")
        }
        if (baseUrl.contains("openrouter")) {
            b.header("X-Title", "SemCode AI")
            b.header("HTTP-Referer", "https://github.com/danielsem65/semcode-ai")
        }
        return b
    }

    private fun postBody(system: String, history: List<Msg>, tools: List<ToolDef>, stream: Boolean): Request =
        baseRequest("$baseUrl/chat/completions")
            .post(JSONObject()
                .put("model", model)
                .put("messages", messagesJson(system, history))
                .put("tools", toolsJson(tools))
                .put("temperature", 0.3)
                .put("stream", stream)
                .toString()
                .toRequestBody(JSON))
            .build()

    // ---------------- non-streaming ----------------

    override fun chat(system: String, history: List<Msg>, tools: List<ToolDef>): EngineReply {
        val call = client.newCall(postBody(system, history, tools, stream = false))
        activeCall = call
        try {
            call.execute().use { resp ->
                val raw = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) throw RuntimeException(friendlyError(resp.code, raw))

                val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?: throw RuntimeException("Provider sent no message.")
                return parseMessage(msg)
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    // ---------------- streaming ----------------

    private class ToolAcc(var id: String, var name: String, val args: StringBuilder)

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

                        val tcs = delta.optJSONArray("tool_calls") ?: continue
                        for (i in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(i) ?: continue
                            val idx = tc.optInt("index", i)
                            val fn = tc.optJSONObject("function")
                            val acc = slots.getOrPut(idx) {
                                ToolAcc(
                                    id = sstr(tc, "id").ifBlank { "call_$idx" },
                                    name = sstr(fn, "name"),
                                    args = StringBuilder(sstr(fn, "arguments"))
                                )
                            }
                            sstr(tc, "id").takeIf { it.isNotEmpty() }?.let { acc.id = it }
                            fn?.let { f ->
                                sstr(f, "name").takeIf { it.isNotEmpty() }?.let { acc.name = it }
                                sstr(f, "arguments").takeIf { it.isNotEmpty() }?.let { acc.args.append(it) }
                            }
                        }
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

        private fun errText(body: String): String = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")?.take(300)
        }.getOrNull() ?: body.take(300)

        private fun friendlyError(code: Int, body: String): String {
            val detail = errText(body)
            val limited = code == 429 || code == 492 ||
                detail.contains("rate limit", true) || detail.contains("FreeUsageLimit", true)
            return if (limited)
                "This free model's quota is used up right now (Zen resets daily). " +
                    "Pick another model from the list, switch provider in Settings, or try again later. [$code]"
            else "HTTP $code: $detail"
        }
    }
}
