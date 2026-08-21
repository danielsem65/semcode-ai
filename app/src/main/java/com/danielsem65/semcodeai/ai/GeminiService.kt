package com.danielsem65.semcodeai.ai

import com.danielsem65.semcodeai.fs.FileOps
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiService(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun systemPrompt(): String {
        val base = FileOps.baseDir.absolutePath.trimEnd('/')
        return """
You are SemCode AI, a personal file-management assistant running on the user's Android device.
You have direct, authorized access to the device storage through the provided tools. The user is the sole owner and operator of this device and has granted full permission for any file operation, including deletion.

Storage notes:
- The main shared storage root is "$base".
- Paths you pass to tools are resolved relative to that root unless absolute.
- Examples: "Download/report.pdf" -> $base/Download/report.pdf ; "/storage/emulated/0/DCIM" is absolute.

Rules:
1. Use tools whenever the user asks anything about files or folders. Never invent file listings - always verify with list_files, search_files or get_file_info first.
2. Before deleting or overwriting anything, briefly state what you are about to do, then do it in the same turn. Do not ask for confirmation; the user pre-authorized destructive operations.
3. When moving/copying, if the destination is an existing folder, the item is placed inside it automatically.
4. After each operation completes, summarize concisely what happened (paths + sizes).
5. For reading large files, remember content is truncated at 500 KB.
6. Keep answers short and practical. Use plain text.
7. If a tool returns an ERROR, report it honestly and suggest a fix or try an alternative approach.
""".trimIndent()
    }

    private fun declaration(name: String, desc: String, props: List<Pair<String, String>>, required: List<String>): JSONObject {
        val properties = JSONObject()
        props.forEach { (pname, ptype) ->
            properties.put(pname, JSONObject().put("type", ptype).put("description", ""))
        }
        return JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", properties)
                    .put("required", JSONArray(required))
            )
    }

    fun toolDeclarations(): JSONArray {
        val decls = JSONArray()
        decls.put(declaration("list_files", "List the contents of a directory.",
            listOf("path" to "STRING"), listOf("path")))
        decls.put(declaration("read_file", "Read a text file's full content.",
            listOf("path" to "STRING"), listOf("path")))
        decls.put(declaration("write_file", "Create or overwrite a text file with the given content.",
            listOf("path" to "STRING", "content" to "STRING"), listOf("path", "content")))
        decls.put(declaration("create_folder", "Create a directory including missing parents.",
            listOf("path" to "STRING"), listOf("path")))
        decls.put(declaration("delete_path", "Permanently delete a file or an entire folder tree.",
            listOf("path" to "STRING"), listOf("path")))
        decls.put(declaration("copy_path", "Copy a file/folder recursively to a destination.",
            listOf("source" to "STRING", "destination" to "STRING"), listOf("source", "destination")))
        decls.put(declaration("move_path", "Move or rename a file/folder to a destination.",
            listOf("source" to "STRING", "destination" to "STRING"), listOf("source", "destination")))
        decls.put(declaration("search_files", "Search filenames by wildcard pattern (* and ?), case-insensitive, inside a directory tree.",
            listOf("directory" to "STRING", "pattern" to "STRING"), listOf("directory", "pattern")))
        decls.put(declaration("get_file_info", "Get metadata (size, dates, permissions) of a path.",
            listOf("path" to "STRING"), listOf("path")))
        return decls
    }

    data class ModelReply(
        val parts: List<Part>,
        val rawText: String?
    )

    sealed class Part {
        data class Text(val value: String) : Part()
        data class Call(val name: String, val args: JSONObject) : Part()
    }

    fun generate(contents: JSONArray): ModelReply {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put(
                "tools",
                JSONArray().put(JSONObject().put("functionDeclarations", toolDeclarations()))
            )
            .put("generationConfig", JSONObject().put("temperature", 0.3))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val textBody = resp.body?.string() ?: "{}"
            val json = JSONObject(textBody)

            if (!resp.isSuccessful) {
                val err = json.optJSONObject("error")
                throw RuntimeException("API ${resp.code}: ${err?.optString("message") ?: textBody.take(300)}")
            }

            val feedback = json.optJSONObject("promptFeedback")
            if (feedback != null && feedback.has("blockReason")) {
                throw RuntimeException("Blocked by safety filter: ${feedback.optString("blockReason")}")
            }

            val candidates = json.optJSONArray("candidates")
                ?: throw RuntimeException("No response from model.")
            if (candidates.length() == 0) throw RuntimeException("Empty candidates.")

            val partsJson = candidates.getJSONObject(0)
                .optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()

            val parts = mutableListOf<Part>()
            var rawText: String? = null
            for (i in 0 until partsJson.length()) {
                val p = partsJson.getJSONObject(i)
                when {
                    p.has("text") -> {
                        val t = p.getString("text")
                        if (t.isNotBlank()) { parts += Part.Text(t); rawText = (rawText ?: "") + t }
                    }
                    p.has("functionCall") -> {
                        val fc = p.getJSONObject("functionCall")
                        parts += Part.Call(fc.getString("name"), fc.optJSONObject("args") ?: JSONObject())
                    }
                }
            }
            if (parts.isEmpty()) {
                val reason = candidates.getJSONObject(0).optString("finishReason", "UNKNOWN")
                throw RuntimeException("Model returned no content (finishReason=$reason)")
            }
            return ModelReply(parts, rawText)
        }
    }
}
