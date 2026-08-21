package com.danielsem65.semcodeai.fs

import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileOps {

    val baseDir: File
        get() = File(
            Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        )

    fun resolve(input: String): File {
        val trimmed = input.trim().trim('"')
        val f = if (trimmed.startsWith("/")) {
            File(trimmed)
        } else {
            File(baseDir, trimmed)
        }
        return f.canonicalFile
    }

    fun execute(name: String, args: JSONObject): String = try {
        when (name) {
            "list_files" -> listFiles(args.getString("path"))
            "read_file" -> readFile(args.getString("path"))
            "write_file" -> writeFile(
                args.getString("path"),
                args.optString("content", "")
            )
            "create_folder" -> createFolder(args.getString("path"))
            "delete_path" -> deletePath(args.getString("path"))
            "copy_path" -> copyPath(args.getString("source"), args.getString("destination"))
            "move_path" -> movePath(args.getString("source"), args.getString("destination"))
            "search_files" -> searchFiles(
                args.getString("directory"),
                args.getString("pattern")
            )
            "get_file_info" -> fileInfo(args.getString("path"))
            else -> "ERROR: unknown tool '$name'"
        }
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    fun listFiles(pathInput: String): String {
        val dir = resolve(pathInput)
        if (!dir.exists()) return "ERROR: not found: ${dir.path}"
        if (!dir.isDirectory) return "ERROR: not a directory: ${dir.path}"
        val entries = dir.listFiles()
            ?: return "ERROR: cannot list directory (permission denied?)"
        if (entries.isEmpty()) return "(empty directory)"
        return entries.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .joinToString("\n") {
                if (it.isDirectory) "[DIR]  ${it.name}" else "[FILE] ${it.name} (${humanSize(it.length())})"
            }
    }

    fun readFile(pathInput: String): String {
        val f = resolve(pathInput)
        if (!f.exists()) return "ERROR: not found: ${f.path}"
        if (!f.isFile) return "ERROR: not a file: ${f.path}"
        if (f.length() > 500_000) return "ERROR: file too large (${humanSize(f.length())}); read in chunks is unsupported"
        val text = f.readText()
        if (text.contains('\u0000')) return "ERROR: binary file, cannot display as text"
        return if (text.length > 60_000) text.take(60_000) + "\n...[truncated]" else text.ifEmpty { "(empty file)" }
    }

    fun writeFile(pathInput: String, content: String): String {
        val f = resolve(pathInput)
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        f.writeText(content)
        return "OK: wrote ${humanSize(content.toByteArray().size.toLong())} to ${f.path}"
    }

    fun createFolder(pathInput: String): String {
        val f = resolve(pathInput)
        val ok = f.mkdirs() || f.isDirectory
        return if (ok) "OK: folder ready at ${f.path}" else "ERROR: could not create ${f.path}"
    }

    fun deletePath(pathInput: String): String {
        val f = resolve(pathInput)
        if (!f.exists()) return "ERROR: not found: ${f.path}"
        val count = deleteRecursive(f)
        return "OK: deleted ${f.path} ($count items)"
    }

    private fun deleteRecursive(f: File): Int {
        var count = 0
        if (f.isDirectory) {
            f.listFiles()?.forEach { count += deleteRecursive(it) }
        }
        if (f.delete()) count++
        return count
    }

    fun copyPath(srcInput: String, dstInput: String): String {
        val src = resolve(srcInput)
        if (!src.exists()) return "ERROR: source not found: ${src.path}"
        val dst = resolveDestination(src, resolve(dstInput))
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
            "OK: copied ${src.name} to ${dst.path}"
        } catch (e: Exception) {
            "ERROR: copy failed: ${e.message}"
        }
    }

    fun movePath(srcInput: String, dstInput: String): String {
        val src = resolve(srcInput)
        if (!src.exists()) return "ERROR: source not found: ${src.path}"
        val dst = resolveDestination(src, resolve(dstInput))
        return try {
            if (src.renameTo(dst)) {
                "OK: moved ${src.name} to ${dst.path}"
            } else {
                Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                "OK: moved ${src.name} to ${dst.path}"
            }
        } catch (e: Exception) {
            "ERROR: move failed: ${e.message}"
        }
    }

    private fun resolveDestination(src: File, dstRaw: File): File =
        if (dstRaw.isDirectory && !dstRaw.path.equals(src.path, ignoreCase = true)) File(dstRaw, src.name) else dstRaw

    fun searchFiles(dirInput: String, pattern: String): String {
        val root = resolve(dirInput)
        if (!root.exists() || !root.isDirectory) return "ERROR: invalid directory: ${root.path}"
        val regex = wildcardToRegex(pattern)
        val results = mutableListOf<String>()
        searchRecursive(root, regex, results, depth = 0)
        return if (results.isEmpty()) "(no matches)"
        else results.joinToString("\n")
    }

    private fun searchRecursive(dir: File, regex: Regex, out: MutableList<String>, depth: Int) {
        if (depth > 8 || out.size >= 200) return
        dir.listFiles()?.forEach { f ->
            if (regex.matches(f.name)) out += f.path
            if (out.size < 200 && f.isDirectory) searchRecursive(f, regex, out, depth + 1)
        }
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        pattern.forEach { c ->
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return sb.toString().toRegex(RegexOption.IGNORE_CASE)
    }

    fun fileInfo(pathInput: String): String {
        val f = resolve(pathInput)
        if (!f.exists()) return "ERROR: not found: ${f.path}"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val type = if (f.isDirectory) {
            "directory (${f.listFiles()?.size ?: 0} entries)"
        } else {
            "file, ${humanSize(f.length())}"
        }
        return buildString {
            appendLine("Path: ${f.path}")
            appendLine("Type: $type")
            appendLine("Modified: ${fmt.format(Date(f.lastModified()))}")
            appendLine("Readable: ${f.canRead()}, Writable: ${f.canWrite()}, Hidden: ${f.isHidden}")
        }.trimEnd()
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = -1
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }
}
