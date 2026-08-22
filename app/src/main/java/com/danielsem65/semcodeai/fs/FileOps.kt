package com.danielsem65.semcodeai.fs

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All filesystem tools. Paths are resolved against [rootProvider] unless absolute.
 */
class FileOps(private val rootProvider: () -> File) {

    val root: File get() = rootProvider()

    fun resolve(input: String): File {
        val trimmed = input.trim().trim('"', '\'')
        val f = if (trimmed.startsWith("/")) File(trimmed) else File(root, trimmed)
        return f.canonicalFile
    }

    fun execute(name: String, args: JSONObject): String = try {
        when (name) {
            "list_files" -> listFiles(args.optString("path", ".").ifBlank { "." })
            "read_file" -> readFile(args.getString("path"))
            "write_file" -> writeFile(args.getString("path"), args.optString("content", ""))
            "edit_file" -> editFile(
                args.getString("path"),
                args.getString("old_string"),
                args.optString("new_string", ""),
                args.optBoolean("replace_all", false)
            )
            "search_in_files" -> searchInFiles(args.getString("directory"), args.getString("query"))
            "search_files" -> searchFiles(args.getString("directory"), args.getString("pattern"))
            "create_folder" -> createFolder(args.getString("path"))
            "delete_path" -> deletePath(args.getString("path"))
            "copy_path" -> copyPath(args.getString("source"), args.getString("destination"))
            "move_path" -> movePath(args.getString("source"), args.getString("destination"))
            "get_file_info" -> fileInfo(args.getString("path"))
            else -> "ERROR: unknown tool '$name'"
        }
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    /**
     * Human-readable preview of what a destructive tool would do, without
     * touching the filesystem. Used by the approval gate.
     */
    fun previewDiff(name: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        return when (name) {
            "write_file" -> {
                val f = resolve(args.optString("path"))
                val old = if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
                DiffUtil.unified(old, args.optString("content", ""))
            }
            "edit_file" -> {
                val f = resolve(args.optString("path"))
                if (!f.exists()) return "(file does not exist — edit will fail)"
                val old = runCatching { f.readText() }.getOrDefault("")
                val os = args.optString("old_string")
                if (os.isEmpty() || !old.contains(os)) return "⚠ old_string not found in file — this edit will fail"
                DiffUtil.unified(old, old.replaceFirst(os, args.optString("new_string", "")))
            }
            "delete_path" -> {
                val f = resolve(args.optString("path"))
                if (!f.exists()) "(nothing to delete)"
                else if (f.isFile)
                    DiffUtil.unified(runCatching { f.readText() }.getOrDefault(""), "")
                else
                    "[DIR] ${rel(f)} — folder with ~${f.walkBottomUp().take(2000).count()} entries will be deleted"
            }
            "move_path" -> "Move ${args.optString("source")} → ${args.optString("destination")}"
            "copy_path" -> "Copy ${args.optString("source")} → ${args.optString("destination")}"
            "run_command" -> "$ ${args.optString("command", "").take(400)}"
            else -> name
        }
    }

    fun listFiles(pathInput: String): String {
        val dir = resolve(pathInput)
        if (!dir.exists()) return "ERROR: not found: ${rel(dir)}"
        if (!dir.isDirectory) return "ERROR: not a directory: ${rel(dir)}"
        val entries = dir.listFiles() ?: return "ERROR: cannot list (permission denied?)"
        if (entries.isEmpty()) return "(empty directory)"
        return entries
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .joinToString("\n") {
                if (it.isDirectory) "[DIR]  ${it.name}"
                else "[FILE] ${it.name} (${humanSize(it.length())})"
            }
    }

    fun readFile(pathInput: String): String {
        val f = resolve(pathInput)
        if (!f.exists()) return "ERROR: not found: ${rel(f)}"
        if (!f.isFile) return "ERROR: not a file: ${rel(f)}"
        if (f.length() > 500_000L) return "ERROR: file too large (${humanSize(f.length())})"
        val text = runCatching { f.readText() }.getOrElse { return "ERROR: unreadable: ${it.message}" }
        if (text.contains('\u0000')) return "ERROR: binary file — cannot show as text"
        return when {
            text.isEmpty() -> "(empty file)"
            text.length > 60_000 -> text.take(60_000) + "\n…[truncated]"
            else -> text
        }
    }

    fun writeFile(pathInput: String, content: String): String {
        val f = resolve(pathInput)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "OK: wrote ${humanSize(content.toByteArray().size.toLong())} → ${rel(f)}"
    }

    fun editFile(pathInput: String, oldString: String, newString: String, replaceAll: Boolean): String {
        val f = resolve(pathInput)
        if (!f.isFile) return "ERROR: not found: ${rel(f)}"
        if (oldString.isEmpty()) return "ERROR: old_string is empty"
        val text = f.readText()
        val n = text.split(oldString).size - 1
        if (n == 0) return "ERROR: old_string not found in ${f.name}. Re-read the file and copy exactly (whitespace matters)."
        if (n > 1 && !replaceAll)
            return "ERROR: old_string appears $n times — add surrounding lines to make it unique, or set replace_all=true."
        f.writeText(if (replaceAll) text.replace(oldString, newString) else text.replaceFirst(oldString, newString))
        return "OK: edited ${f.name} ($n occurrence${if (n == 1) "" else "s"})"
    }

    fun createFolder(pathInput: String): String {
        val f = resolve(pathInput)
        return if (f.mkdirs() || f.isDirectory) "OK: folder ready at ${rel(f)}"
        else "ERROR: could not create ${rel(f)}"
    }

    fun deletePath(pathInput: String): String {
        val f = resolve(pathInput)
        if (!f.exists()) return "ERROR: not found: ${rel(f)}"
        val count = deleteRecursive(f)
        return "OK: deleted ${rel(f)} ($count items)"
    }

    private fun deleteRecursive(f: File): Int {
        var count = 0
        if (f.isDirectory) f.listFiles()?.forEach { count += deleteRecursive(it) }
        if (f.delete()) count++
        return count
    }

    fun copyPath(srcInput: String, dstInput: String): String {
        val src = resolve(srcInput)
        if (!src.exists()) return "ERROR: source not found: ${rel(src)}"
        val dst = intoDir(src, resolve(dstInput))
        return try {
            Files.walk(src.toPath()).use { walk ->
                walk.forEach { p ->
                    val target = dst.toPath().resolve(src.toPath().relativize(p))
                    if (p.toFile().isDirectory) target.toFile().mkdirs()
                    else {
                        target.parent?.toFile()?.mkdirs()
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            "OK: copied ${src.name} → ${rel(dst)}"
        } catch (e: Exception) {
            "ERROR: copy failed: ${e.message}"
        }
    }

    fun movePath(srcInput: String, dstInput: String): String {
        val src = resolve(srcInput)
        if (!src.exists()) return "ERROR: source not found: ${rel(src)}"
        val dst = intoDir(src, resolve(dstInput))
        return try {
            if (!src.renameTo(dst)) Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            "OK: moved ${src.name} → ${rel(dst)}"
        } catch (e: Exception) {
            "ERROR: move failed: ${e.message}"
        }
    }

    private fun intoDir(src: File, dstRaw: File): File =
        if (dstRaw.isDirectory && !dstRaw.path.equals(src.path, ignoreCase = true)) File(dstRaw, src.name) else dstRaw

    fun searchFiles(dirInput: String, pattern: String): String {
        val dir = resolve(dirInput)
        if (!dir.isDirectory) return "ERROR: invalid directory: ${rel(dir)}"
        val results = mutableListOf<String>()
        findByName(dir, wildcardToRegex(pattern), results, 0)
        return if (results.isEmpty()) "(no matches)" else results.joinToString("\n")
    }

    private fun findByName(dir: File, regex: Regex, out: MutableList<String>, depth: Int) {
        if (depth > 8 || out.size >= 200) return
        dir.listFiles()?.forEach { f ->
            if (regex.matches(f.name)) out += rel(f)
            if (out.size < 200 && f.isDirectory && !f.name.startsWith(".")) findByName(f, regex, out, depth + 1)
        }
    }

    private fun wildcardToRegex(pattern: String): Regex = buildString {
        for (c in pattern) when (c) {
            '*' -> append(".*")
            '?' -> append('.')
            in ".()[]{}+^$|\\" -> append('\\').append(c)
            else -> append(c)
        }
    }.toRegex(RegexOption.IGNORE_CASE)

    fun searchInFiles(dirInput: String, query: String): String {
        val dir = resolve(dirInput)
        if (!dir.isDirectory) return "ERROR: invalid directory: ${rel(dir)}"
        if (query.isEmpty()) return "ERROR: empty query"
        val out = mutableListOf<String>()
        grep(dir, query, out, 0)
        return if (out.isEmpty()) "(no matches)" else out.joinToString("\n")
    }

    private fun grep(dir: File, query: String, out: MutableList<String>, depth: Int) {
        if (depth > 10 || out.size >= 100) return
        dir.listFiles()?.forEach { f ->
            if (out.size >= 100) return
            when {
                f.isDirectory -> if (!f.name.startsWith(".")) grep(f, query, out, depth + 1)
                f.extension.lowercase() in BINARY_EXT || f.length() > 1_000_000L -> Unit
                else -> {
                    val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
                    if (text.contains('\u0000')) return@forEach
                    for ((i, line) in text.lines().withIndex()) {
                        if (line.contains(query, ignoreCase = true)) {
                            out += "${rel(f)}:${i + 1}: ${line.trim().take(160)}"
                            if (out.size >= 100) break
                        }
                    }
                }
            }
        }
    }

    fun fileInfo(pathInput: String): String {
        val f = resolve(pathInput)
        if (!f.exists()) return "ERROR: not found: ${rel(f)}"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val type = if (f.isDirectory) "directory (${f.listFiles()?.size ?: 0} entries)"
        else "file, ${humanSize(f.length())}"
        return listOf(
            "Path: ${f.path}",
            "Type: $type",
            "Modified: ${fmt.format(Date(f.lastModified()))}",
            "Readable: ${f.canRead()}  Writable: ${f.canWrite()}  Hidden: ${f.isHidden}"
        ).joinToString("\n")
    }

    /** Pretty path relative to workspace root when possible. */
    fun rel(f: File): String =
        runCatching { "~/" + f.canonicalPath.removePrefix(root.canonicalPath).trimStart('/') }
            .getOrDefault(f.path)

    companion object {
        private val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "apk", "aab", "jar", "zip",
            "tar", "gz", "7z", "rar", "pdf", "mp3", "mp4", "avi", "mov", "so", "bin",
            "dex", "odex", "ttf", "otf", "woff", "woff2"
        )

        fun humanSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var v = bytes.toDouble()
            var i = -1
            while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
            return String.format(Locale.US, "%.1f %s", v, units[i])
        }
    }
}
