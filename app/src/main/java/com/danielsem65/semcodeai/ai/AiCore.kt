package com.danielsem65.semcodeai.ai

import org.json.JSONObject

/** Neutral conversation message used by every engine. */
sealed class Msg {
    data class User(val text: String) : Msg()
    data class AssistantText(val text: String) : Msg()
    data class ToolUse(val id: String, val name: String, val argsJson: String) : Msg()
    data class ToolResult(val id: String, val name: String, val result: String) : Msg()
}

class ToolCall(val id: String, val name: String, val argsJson: String)

class EngineReply(val text: String?, val calls: List<ToolCall>)

class ToolDef(val name: String, val description: String, val parameters: JSONObject) {
    companion object {
        const val STRING = "string"
        const val NUMBER = "number"
        const val BOOLEAN = "boolean"

        fun schema(vararg props: Pair<String, String>, required: List<String> = emptyList()): JSONObject {
            val properties = JSONObject()
            for ((pname, ptype) in props) properties.put(pname, JSONObject().put("type", ptype))
            return JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", org.json.JSONArray(required))
        }
    }
}

interface AiEngine {
    /** One request/response round. Blocking; call from a background dispatcher. */
    fun chat(system: String, history: List<Msg>, tools: List<ToolDef>): EngineReply

    /**
     * Streaming variant — calls onDelta with each text chunk as it arrives.
     * Tool-call results still arrive in the final EngineReply.
     * Default falls back to non-streaming chat().
     */
    fun chatStream(
        system: String,
        history: List<Msg>,
        tools: List<ToolDef>,
        onDelta: (String) -> Unit
    ): EngineReply = chat(system, history, tools)

    /** Cancels any in-flight request (streaming or not). No-op when idle. */
    fun cancelActive() {}

    /** Lists available model IDs; doubles as a key tester. */
    fun listModels(): List<String>
}

data class Provider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyUrl: String,
    val note: String,
    val isLocal: Boolean = false
)

object Providers {

    val ALL = listOf(
        Provider(
            "openrouter", "OpenRouter",
            "https://openrouter.ai/api/v1",
            "openrouter/free",
            "https://openrouter.ai/keys",
            "'openrouter/free' routes to $0 models. Free tier is capped per day — adding even ~\$1 of credit raises those limits massively."
        ),
        Provider(
            "groq", "Groq",
            "https://api.groq.com/openai/v1",
            "llama-3.3-70b-versatile",
            "https://console.groq.com/keys",
            "Very generous free tier (separate quota from OpenRouter) and blazing-fast streaming. Key from console.groq.com/keys."
        ),
        Provider(
            "zen", "OpenCode Zen",
            "https://opencode.ai/zen/v1",
            "big-pickle",
            "https://opencode.ai/auth",
            "Zen's free tier only works INSIDE the official OpenCode app/CLI — from third-party apps like this one it returns 400 \"only be used in OpenCode\". Prefer OpenRouter or Groq."
        ),
        Provider(
            "ollama", "Ollama (local)",
            "http://127.0.0.1:11434/v1",
            "",
            "https://ollama.com",
            "Offline models (gemma3, qwen3…) via Ollama on-device (Termux) or adb reverse. No key needed.",
            isLocal = true
        ),
        Provider(
            "device", "On-device (offline)",
            "http://127.0.0.1:${com.danielsem65.semcodeai.core.LlamaServer.PORT}/v1",
            "local-model",
            "",
            "Runs a .gguf model entirely on this phone — no internet at all. Pick your model file in the section below.",
            isLocal = true
        )
    )

    fun byId(id: String): Provider = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun create(provider: Provider, apiKey: String, model: String): AiEngine =
        OpenAiCompatEngine(provider.baseUrl, apiKey.ifBlank { "none" }, model, isLocal = provider.isLocal)
}
