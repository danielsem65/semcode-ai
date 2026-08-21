package com.danielsem65.semcodeai.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Works with OpenCode Zen, OpenRouter, Groq, DeepSeek, Ollama, LM Studio, etc. */
class OpenAiCompatEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val extraHeaders: Map<String, String> = mapOf("X-Title" to "SemCode AI")
) : AiEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun chat(system: String, history: List<Msg>, tools: List<ToolDef>): EngineReply {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in history) {
            when (m) {
                is Msg.User -> messages.put(
                    JSONObject().put("role", "user").put("content", m.text))
                is Msg.AssistantText -> messages.put(
                    JSONObject().put("role", "assistant").put("content", m.text))
                is Msg.ToolCallMsg -> messages.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("tool_calls", JSONArray().put(
                            JSONObject()
                                .put("id", m.id)
                                .put("type", "function")
                                .put("function", JSONObject()
                                    .put("name", m.name)
                                    .put("arguments", m.argsJson)))))
                is Msg.ToolResultMsg -> messages.put(
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

        val builder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        client.newCall(builder.build()).execute().use { resp ->
            val textBody = resp.body?.string() ?: "{}"
            val json = runCatching { JSONObject(textBody) }.getOrDefault(JSONObject())
            if (!resp.isSuccessful) {
                val err = json.optJSONObject("error")
                throw RuntimeException("API ${resp.code}: ${err?.optString("message") ?: textBody.take(300)}")
            }

            val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw RuntimeException("No response from provider.")
            var text = msg.optString("content", "").takeIf { it.isNotBlank() }
            val calls = mutableListOf<ToolCall>()
            val tc = msg.optJSONArray("tool_calls") ?: JSONArray()
            for (i in 0 until tc.length()) {
                val c = tc.getJSONObject(i)
                val fn = c.optJSONObject("function") ?: continue
                calls += ToolCall(c.optString("id", "t$i"), fn.getString("name"),
                    fn.optString("arguments", "{}").ifBlank { "{}" })
            }
            if (text == null && calls.isEmpty()) throw RuntimeException("Provider returned no content.")
            return EngineReply(text, calls)
        }
    }
}
