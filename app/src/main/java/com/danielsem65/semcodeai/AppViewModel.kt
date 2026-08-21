package com.danielsem65.semcodeai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielsem65.semcodeai.ai.GeminiService
import com.danielsem65.semcodeai.data.SettingsStore
import com.danielsem65.semcodeai.fs.FileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(
    val role: Role,
    val text: String,
    val isTool: Boolean = false
) {
    enum class Role { USER, MODEL }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun hasApiKey(): Boolean = settings.apiKey.isNotEmpty()

    fun saveApiKey(key: String) {
        settings.apiKey = key
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || _busy.value || !hasApiKey()) return

        _messages.value += ChatMessage(ChatMessage.Role.USER, text)
        _busy.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                runAgent()
            } catch (e: Exception) {
                emitModel("Error: ${e.message ?: "unknown failure"}")
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun runAgent() {
        val service = GeminiService(settings.apiKey)
        // historyContents() already contains the newest user message (added in send()).
        val contents = historyContents()

        var steps = 0
        while (steps < MAX_STEPS && _busy.value) {
            steps++
            val reply = service.generate(contents)

            val calls = reply.parts.filterIsInstance<GeminiService.Part.Call>()
            if (calls.isEmpty()) {
                reply.rawText?.let { emitModel(it) }
                break
            }

            contents.put(
                JSONObject()
                    .put("role", "model")
                    .put("parts", JSONArray().apply {
                        reply.parts.forEach { p ->
                            when (p) {
                                is GeminiService.Part.Text -> put(JSONObject().put("text", p.value))
                                is GeminiService.Part.Call -> put(
                                    JSONObject().put(
                                        "functionCall",
                                        JSONObject().put("name", p.name).put("args", p.args)
                                    )
                                )
                            }
                        }
                    })
            )

            for (call in calls) {
                emitTool("${call.name}(${formatArgs(call.args)})")
                val result = FileOps.execute(call.name, call.args)
                emitResult(if (result.startsWith("ERROR")) result else summarize(result))
                contents.put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "functionResponse",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put(
                                            "response",
                                            JSONObject().put("result", result.take(20_000))
                                        )
                                )
                            )
                        )
                )
            }

            if (reply.rawText?.isNotBlank() == true) emitModel(reply.rawText)
        }
    }

    private fun historyContents(): JSONArray {
        // Rebuild API history only from user + model text turns; tool chatter is session-local.
        val arr = JSONArray()
        _messages.value
            .filter { !it.isTool && !it.text.startsWith("Error:") }
            .takeLast(MAX_HISTORY * 2)
            .forEach { m ->
                arr.put(
                    JSONObject()
                        .put("role", if (m.role == ChatMessage.Role.USER) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", m.text)))
                )
            }
        return arr
    }

    private fun formatArgs(args: JSONObject): String {
        val entries = mutableListOf<String>()
        args.keys().forEach { k ->
            val v = args.optString(k, "")
            entries += "$k=${if (v.length > 40) v.take(40) + "…" else v}"
        }
        return entries.joinToString(", ")
    }

    private fun summarize(result: String): String {
        val lines = result.split("\n")
        return if (lines.size > 12) {
            lines.take(12).joinToString("\n") + "\n… (+${lines.size - 12} more)"
        } else result
    }

    private fun emitModel(text: String) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, text)
    }

    private fun emitTool(text: String) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, "⚙ $text", isTool = true)
    }

    private fun emitResult(text: String) {
        _messages.value += ChatMessage(ChatMessage.Role.MODEL, text.takeLast(400), isTool = true)
    }

    companion object {
        private const val MAX_STEPS = 12
        private const val MAX_HISTORY = 10
    }
}
