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
            "zen", "OpenCode Zen",
            "https://opencode.ai/zen/v1",
            "big-pickle",
            "https://opencode.ai/auth",
            "FREE coding models — big-pickle, x-preview-f-free, mimo & more. Key from opencode.ai/auth."
        ),
        Provider(
            "openrouter", "OpenRouter",
            "https://openrouter.ai/api/v1",
            "openrouter/free",
            "https://openrouter.ai/keys",
            "'openrouter/free' routes to any free model; append ':free' to specific models for $0."
        ),
        Provider(
            "ollama", "Ollama (local)",
            "http://127.0.0.1:11434/v1",
            "",
            "https://ollama.com",
            "Offline models (gemma3, qwen3…) via Ollama on-device (Termux) or adb reverse. No key needed.",
            isLocal = true
        )
    )

    fun byId(id: String): Provider = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun create(provider: Provider, apiKey: String, model: String): AiEngine =
        OpenAiCompatEngine(provider.baseUrl, apiKey.ifBlank { "none" }, model, isLocal = provider.isLocal)
}
