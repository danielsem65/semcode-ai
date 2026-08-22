package com.danielsem65.semcodeai.core

import android.content.Context
import com.danielsem65.semcodeai.ChatMessage
import com.danielsem65.semcodeai.ai.Msg
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)

/**
 * Persistent chat projects — one JSON file per project.
 * Lives in /storage/emulated/0/.semcode-ai/projects when storage is granted
 * (survives clear-data/uninstall); falls back to private storage otherwise.
 * Existing private projects are migrated once.
 */
class ProjectStore(private val context: Context) {

    private val legacyDir = File(context.filesDir, "projects")

    private fun dir(): File {
        val home = Workspace.home(context) ?: return legacyDir.apply { mkdirs() }
        val d = File(home, "projects").apply { mkdirs() }
        if (legacyDir.exists()) {
            legacyDir.listFiles { f -> f.extension == "json" }?.forEach { f ->
                val dst = File(d, f.name)
                if (!dst.exists()) runCatching { f.copyTo(dst) }
            }
        }
        return d
    }

    fun list(): List<Project> =
        dir().listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    Project(
                        id = f.nameWithoutExtension,
                        name = o.optString("name", "Untitled").ifBlank { "Untitled" },
                        createdAt = o.optLong("createdAt"),
                        updatedAt = o.optLong("updatedAt"),
                        messageCount = o.optJSONArray("messages")?.length() ?: 0
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun create(name: String): String {
        val id = "p${System.currentTimeMillis()}"
        save(id, name.ifBlank { "New chat" }, emptyList(), emptyList())
        return id
    }

    fun exists(id: String): Boolean = File(dir(), "$id.json").exists()

    fun projectName(id: String): String =
        runCatching {
            JSONObject(File(dir(), "$id.json").readText()).optString("name", "New chat")
        }.getOrDefault("New chat")

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        val f = File(dir(), "$id.json")
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            o.put("name", name.trim())
            f.writeText(o.toString())
        }
    }

    fun delete(id: String) {
        File(dir(), "$id.json").delete()
    }

    /** Atomic-ish save of transcript + API history. */
    fun save(id: String, name: String, messages: List<ChatMessage>, api: List<Msg>) {
        val f = File(dir(), "$id.json")
        val tmp = File(dir(), "$id.tmp")
        try {
            val prevName = if (f.exists()) runCatching {
                JSONObject(f.readText()).optString("name")
            }.getOrNull().orEmpty() else ""
            val createdAt = if (f.exists()) runCatching {
                JSONObject(f.readText()).optLong("createdAt")
            }.getOrElse { System.currentTimeMillis() } else System.currentTimeMillis()

            val mArr = JSONArray()
            for (m in messages) {
                mArr.put(JSONObject()
                    .put("role", m.role.name)
                    .put("text", m.text)
                    .put("isTool", m.isTool)
                    .put("isError", m.isError))
            }

            val aArr = JSONArray()
            for (m in api) {
                when (m) {
                    is Msg.User -> aArr.put(JSONObject().put("t", "u").put("x", m.text))
                    is Msg.AssistantText -> aArr.put(JSONObject().put("t", "a").put("x", m.text))
                    is Msg.ToolUse -> aArr.put(JSONObject()
                        .put("t", "tu").put("id", m.id).put("n", m.name).put("args", m.argsJson))
                    is Msg.ToolResult -> aArr.put(JSONObject()
                        .put("t", "tr").put("id", m.id).put("n", m.name).put("r", m.result))
                }
            }

            tmp.writeText(JSONObject()
                .put("name", name.ifBlank { prevName.ifBlank { "New chat" } })
                .put("createdAt", createdAt)
                .put("updatedAt", System.currentTimeMillis())
                .put("messages", mArr)
                .put("api", aArr)
                .toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
        }
    }

    fun load(id: String): Pair<List<ChatMessage>, List<Msg>>? = runCatching {
        val f = File(dir(), "$id.json")
        if (!f.exists()) return null
        val o = JSONObject(f.readText())

        val msgs = mutableListOf<ChatMessage>()
        val mArr = o.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until mArr.length()) {
            val m = mArr.optJSONObject(i) ?: continue
            val role = runCatching {
                ChatMessage.Role.valueOf(m.optString("role", "MODEL"))
            }.getOrDefault(ChatMessage.Role.MODEL)
            msgs += ChatMessage(
                role = role,
                text = m.optString("text"),
                isTool = m.optBoolean("isTool"),
                isError = m.optBoolean("isError")
            )
        }

        val api = mutableListOf<Msg>()
        val aArr = o.optJSONArray("api") ?: JSONArray()
        for (i in 0 until aArr.length()) {
            val m = aArr.optJSONObject(i) ?: continue
            when (m.optString("t")) {
                "u" -> api += Msg.User(m.optString("x"))
                "a" -> api += Msg.AssistantText(m.optString("x"))
                "tu" -> api += Msg.ToolUse(m.optString("id"), m.optString("n"), m.optString("args"))
                "tr" -> api += Msg.ToolResult(m.optString("id"), m.optString("n"), m.optString("r"))
            }
        }
        msgs to api
    }.getOrNull()
}
