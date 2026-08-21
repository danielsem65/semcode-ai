package com.danielsem65.semcodeai.ai

import org.json.JSONObject

sealed class Msg {
    data class User(val text: String) : Msg()
    data class AssistantText(val text: String) : Msg()
    data class ToolCallMsg(val id: String, val name: String, val argsJson: String) : Msg()
    data class ToolResultMsg(val id: String, val name: String, val result: String) : Msg()
}

class ToolCall(val id: String, val name: String, val argsJson: String)

class EngineReply(
    val text: String?,
    val calls: List<ToolCall>
)

class ToolDef(val name: String, val description: String, val parameters: JSONObject) {
    companion object {
        fun obj(vararg props: Pair<String, String>, required: List<String> = emptyList()): JSONObject {
            val properties = JSONObject()
            props.forEach { (pname, ptype) -> properties.put(pname, JSONObject().put("type", ptype)) }
            return JSONObject().put("type", "object").put("properties", properties)
                .put("required", org.json.JSONArray(required))
        }
        val STRING = "string"; val BOOLEAN = "boolean"; val NUMBER = "number"
    }
}

interface AiEngine {
    fun chat(system: String, history: List<Msg>, tools: List<ToolDef>): EngineReply
}

enum class EngineKind { GEMINI, OPENAI_COMPAT, ANTHROPIC }

data class Provider(
    val id: String,
    val displayName: String,
    val kind: EngineKind,
    val baseUrl: String,
    val defaultModel: String,
    val keyUrl: String,
    val note: String
)

object Providers {
    val ALL = listOf(
        Provider("gemini", "Gemini (free tier)", EngineKind.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
            "https://aistudio.google.com/apikey", "Free API key from Google AI Studio"),
        Provider("zen", "OpenCode Zen (free models)", EngineKind.OPENAI_COMPAT,
            "https://opencode.ai/zen/v1", "big-pickle",
            "https://opencode.ai/auth", "big-pickle & friends are FREE"),
        Provider("openrouter", "OpenRouter (free models)", EngineKind.OPENAI_COMPAT,
            "https://openrouter.ai/api/v1", "openrouter/free",
            "https://openrouter.ai/keys", "'openrouter/free' or any model + ':free' = $0"),
        Provider("groq", "Groq (free tier)", EngineKind.OPENAI_COMPAT,
            "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile",
            "https://console.groq.com/keys", "Fast, free tier"),
        Provider("deepseek", "DeepSeek", EngineKind.OPENAI_COMPAT,
            "https://api.deepseek.com/v1", "deepseek-chat",
            "https://platform.deepseek.com", "Paid, very cheap"),
        Provider("anthropic", "Anthropic Claude", EngineKind.ANTHROPIC,
            "https://api.anthropic.com/v1", "claude-sonnet-4-5",
            "https://console.anthropic.com", "Paid; excellent coder")
    )

    fun byId(id: String): Provider = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun create(provider: Provider, apiKey: String, model: String): AiEngine = when (provider.kind) {
        EngineKind.GEMINI -> GeminiEngine(apiKey, model)
        EngineKind.OPENAI_COMPAT -> OpenAiCompatEngine(provider.baseUrl, apiKey, model)
        EngineKind.ANTHROPIC -> AnthropicEngine(provider.baseUrl, apiKey, model)
    }
}
