package com.danielsem65.semcodeai.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private data class Turn(val role: String, val blocks: JSONArray)

class AnthropicEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) : AiEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun chat(system: String, history: List<Msg>, tools: List<ToolDef>): EngineReply {
        // Anthropic requires alternating user/assistant roles; merge consecutive same-role turns.
        val turns = mutableListOf<Turn>()
        fun push(role: String, block: JSONObject) {
            val last = turns.lastOrNull()
            if (last != null && last.role == role) last.blocks.put(block)
            else turns.add(Turn(role, JSONArray().put(block)))
        }

        for (m in history) {
            when (m) {
                is Msg.User -> push("user", JSONObject().put("type", "text").put("text", m.text))
                is Msg.AssistantText -> push("assistant", JSONObject().put("type", "text").put("text", m.text))
                is Msg.ToolCallMsg -> push("assistant", JSONObject()
                    .put("type", "tool_use")
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("input", runCatching { JSONObject(m.argsJson) }.getOrDefault(JSONObject())))
                is Msg.ToolResultMsg -> push("user", JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", m.id)
                    .put("content", m.result))
            }
        }
        if (turns.firstOrNull()?.role != "user") turns.add(0, Turn("user", JSONArray().put(
            JSONObject().put("type", "text").put("text", "(start)"))))
        if (turns.last().role == "assistant") turns.add(Turn("user", JSONArray().put(
            JSONObject().put("type", "text").put("text", "Continue."))))

        val messages = JSONArray()
        for (t in turns) messages.put(JSONObject().put("role", t.role).put("content", t.blocks))

        val toolsArr = JSONArray()
        for (t in tools) toolsArr.put(JSONObject()
            .put("name", t.name)
            .put("description", t.description)
            .put("input_schema", t.parameters))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 8192)
            .put("system", system)
            .put("messages", messages)
            .put("tools", toolsArr)

        val request = Request.Builder()
            .url("$baseUrl/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val textBody = resp.body?.string() ?: "{}"
            val json = runCatching { JSONObject(textBody) }.getOrDefault(JSONObject())
            if (!resp.isSuccessful) {
                val err = json.optJSONObject("error")
                throw RuntimeException("Claude ${resp.code}: ${err?.optString("message") ?: textBody.take(300)}")
            }

            val content = json.optJSONArray("content") ?: JSONArray()
            var text: String? = null
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> if (b.optString("text").isNotBlank())
                        text = (text ?: "") + b.getString("text")
                    "tool_use" -> calls += ToolCall(b.getString("id"), b.getString("name"),
                        b.optJSONObject("input")?.toString() ?: "{}")
                }
            }
            if (text == null && calls.isEmpty()) throw RuntimeException("Claude returned no content.")
            return EngineReply(text, calls)
        }
    }
}
