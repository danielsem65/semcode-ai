package com.danielsem65.semcodeai.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GeminiEngine(private val apiKey: String, private val model: String) : AiEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val idCounter = AtomicInteger()

    override fun chat(system: String, history: List<Msg>, tools: List<ToolDef>): EngineReply {
        val contents = JSONArray()
        for (m in history) {
            when (m) {
                is Msg.User -> contents.put(role("user").put(
                    "parts", JSONArray().put(JSONObject().put("text", m.text))))
                is Msg.AssistantText -> contents.put(role("model").put(
                    "parts", JSONArray().put(JSONObject().put("text", m.text))))
                is Msg.ToolCallMsg -> {
                    val args = runCatching { JSONObject(m.argsJson) }.getOrDefault(JSONObject())
                    contents.put(role("model").put(
                        "parts", JSONArray().put(
                            JSONObject().put("functionCall",
                                JSONObject().put("name", m.name).put("args", args)))))
                }
                is Msg.ToolResultMsg -> contents.put(role("user").put(
                    "parts", JSONArray().put(
                        JSONObject().put("functionResponse",
                            JSONObject().put("name", m.name)
                                .put("response", JSONObject().put("result", m.result))))))
            }
        }

        val decls = JSONArray()
        for (t in tools) {
            val params = JSONObject(t.parameters.toString())
                .put("type", t.parameters.optString("type").uppercase())
            decls.put(JSONObject()
                .put("name", t.name)
                .put("description", t.description)
                .put("parameters", fixTypes(params)))
        }

        val genConfig = JSONObject().put("temperature", 0.3)
        // Give thinking-capable models a real thinking budget for deep reasoning.
        if (model.startsWith("gemini-2.5") || model.startsWith("gemini-3") || model.contains("-thinking")) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 8192))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
            .put("generationConfig", genConfig)

        val request = Request.Builder()
            .url("${providerBase()}/models/$model:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val textBody = resp.body?.string() ?: "{}"
            val json = runCatching { JSONObject(textBody) }.getOrDefault(JSONObject())

            if (!resp.isSuccessful) {
                val err = json.optJSONObject("error")
                throw RuntimeException("Gemini ${resp.code}: ${err?.optString("message") ?: textBody.take(300)}")
            }
            val feedback = json.optJSONObject("promptFeedback")
            if (feedback != null && feedback.has("blockReason"))
                throw RuntimeException("Blocked by safety filter: ${feedback.optString("blockReason")}")

            val cand = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw RuntimeException("No response from Gemini.")
            val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()

            var text: String? = null
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                when {
                    p.has("text") && p.getString("text").isNotBlank() ->
                        text = (text ?: "") + p.getString("text")
                    p.has("functionCall") -> {
                        val fc = p.getJSONObject("functionCall")
                        calls += ToolCall("g${idCounter.incrementAndGet()}",
                            fc.getString("name"), fc.optJSONObject("args")?.toString() ?: "{}")
                    }
                }
            }
            if (text == null && calls.isEmpty()) {
                throw RuntimeException("Gemini returned no content (finish=${cand.optString("finishReason")})")
            }
            return EngineReply(text, calls)
        }
    }

    private fun role(r: String) = JSONObject().put("role", r)

    private fun providerBase() = "https://generativelanguage.googleapis.com/v1beta"

    /** Recursively uppercase schema type values for the Gemini v1beta enum. */
    private fun fixTypes(node: Any): Any = when (node) {
        is JSONObject -> {
            val out = JSONObject()
            node.keys().forEach { k ->
                if (k == "type" && node.optString(k) in setOf("object", "string", "number", "integer", "boolean", "array")) {
                    out.put(k, node.getString(k).uppercase())
                } else {
                    out.put(k, fixTypes(node.opt(k) ?: JSONObject()))
                }
            }
            out
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until node.length()) out.put(fixTypes(node.get(i)))
            out
        }
        else -> node
    }
}
