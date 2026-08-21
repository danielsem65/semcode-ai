package com.danielsem65.semcodeai.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One engine for every OpenAI-compatible endpoint:
 * OpenCode Zen, OpenRouter, and Ollama's local server.
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

    override fun listModels(): List<String> {
        val req = baseRequest("$baseUrl/models").build()
        client.newCall(req).execute().use { resp ->
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

    override fun chat(system: String, history: List<Msg>, tools: List<ToolDef>): EngineReply {
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", system)
        )
        for (m in history) {
            when (m) {
                is Msg.User -> messages.put(
                    JSONObject().put("role", "user").put("content", m.text))
                is Msg.AssistantText -> messages.put(
                    JSONObject().put("role", "assistant").put("content", m.text))
                is Msg.ToolUse -> messages.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("tool_calls", JSONArray().put(
                            JSONObject()
                                .put("id", m.id)
                                .put("type", "function")
                                .put("function", JSONObject()
                                    .put("name", m.name)
                                    .put("arguments", m.argsJson)))))
                is Msg.ToolResult -> messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", m.id)
                        .put("content", m.result))
            }
        }

        val toolsArr = JSONArray()
        for (t in tools) {
            toolsArr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("parameters", t.parameters)))
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", toolsArr)
            .put("temperature", 0.3)

        val req = baseRequest("$baseUrl/chat/completions")
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${errText(raw)}")

            val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw RuntimeException("Provider sent no message.")

            var text = msg.optString("content", "").takeIf { it.isNotBlank() }
            val calls = mutableListOf<ToolCall>()
            val tc = msg.optJSONArray("tool_calls")
            if (tc != null) {
                for (i in 0 until tc.length()) {
                    val c = tc.optJSONObject(i) ?: continue
                    val fn = c.optJSONObject("function") ?: continue
                    calls += ToolCall(
                        id = c.optString("id", "call_$i"),
                        name = fn.optString("name"),
                        argsJson = fn.optString("arguments", "{}").ifBlank { "{}" }
                    )
                }
            }
            if (text == null && calls.isEmpty()) throw RuntimeException("Provider returned empty content.")
            return EngineReply(text, calls.filter { it.name.isNotBlank() })
        }
    }

    private fun baseRequest(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (!isLocal) b.header("Authorization", "Bearer $apiKey")
        else if (apiKey.isNotBlank() && apiKey != "none") b.header("Authorization", "Bearer $apiKey")
        if (baseUrl.contains("openrouter")) {
            b.header("X-Title", "SemCode AI")
            b.header("HTTP-Referer", "https://github.com/danielsem65/semcode-ai")
        }
        return b
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private fun errText(body: String): String = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")?.take(300)
        }.getOrNull() ?: body.take(300)
    }
}
