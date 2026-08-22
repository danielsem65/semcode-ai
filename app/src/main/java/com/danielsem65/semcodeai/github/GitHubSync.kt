package com.danielsem65.semcodeai.github

import com.danielsem65.semcodeai.fs.FileOps
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * GitHub integration over the plain REST API — no git binary, no JGit.
 * clone/pull = zipball download; push = blob → tree → commit → ref update,
 * producing real commits visible on github.com.
 */
object GitHubSync {

    private val json = "application/json".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // ---------- public operations ----------

    /** repo: "owner/name" or any github.com URL. Downloads default branch into path. */
    fun clone(ops: FileOps, token: String, repoInput: String, pathInput: String): String {
        val slug = slug(repoInput) ?: return "ERROR: cannot parse repo from '$repoInput'"
        val dest = ops.resolve(pathInput.ifBlank { slug.substringAfter('/') })
        if (dest.exists() && dest.listFiles()?.isNotEmpty() == true)
            return "ERROR: destination not empty: ${ops.rel(dest)}"

        val meta = api("GET", "/repos/$slug", token, null)
        val branch = meta.optString("default_branch", "main")

        downloadZipball(token, slug, branch, dest, stripTopLevel = true)
        writeMeta(dest, slug, branch, "")
        val n = countFiles(dest)
        return "OK: cloned $slug@$branch ($n files) → ${ops.rel(dest)}"
    }

    fun pull(ops: FileOps, token: String, pathInput: String): String {
        val dir = ops.resolve(pathInput)
        val m = readMeta(dir) ?: return "ERROR: ${ops.rel(dir)} is not a synced project (no .semcode/meta.json)"
        dir.listFiles()?.forEach { if (it.name != META_DIR) deleteRec(it) }
        downloadZipball(token, m.slug, m.branch, dir, stripTopLevel = true)
        writeMeta(dir, m.slug, m.branch, m.lastSha)
        return "OK: pulled latest ${m.slug}@${m.branch} (${countFiles(dir)} files)"
    }

    fun status(ops: FileOps, token: String, pathInput: String): String {
        val dir = ops.resolve(pathInput)
        val m = readMeta(dir)
            ?: return "${ops.rel(dir)} is a plain local folder (not cloned via GitHub). Use github_clone or github_create_repo first."
        return listOf(
            "Project: ${ops.rel(dir)}",
            "Repo: https://github.com/${m.slug}",
            "Branch: ${m.branch}",
            "Last pushed commit: ${m.lastSha.ifBlank { "(never pushed from here)" }}",
            "Local files: ${countFiles(dir)}"
        ).joinToString("\n")
    }

    /**
     * Snapshot-push every local file as one commit on the synced branch.
     * Produces a normal commit visible on GitHub.
     *
     * Robust against the classic "422 not fast forward": the remote tip is
     * re-read for every attempt; transient API errors are NOT mistaken for
     * an empty repo; races retry with the fresh tip (up to 3 times); and an
     * explicit force flag can overwrite the remote when the user asks.
     */
    fun push(ops: FileOps, token: String, pathInput: String, message: String, force: Boolean = false): String {
        if (token.isBlank()) return "ERROR: no GitHub token saved. Add one in Settings → GitHub."
        val dir = ops.resolve(pathInput)
        if (!dir.isDirectory) return "ERROR: not a folder: ${ops.rel(dir)}"
        var m = readMeta(dir)
            ?: return "ERROR: ${ops.rel(dir)} has no sync info. github_clone it first, or create the repo with github_create_repo and clone."

        val files = collectFiles(dir)
        if (files.isEmpty()) return "ERROR: nothing to push — folder is empty."
        if (files.size > MAX_FILES) return "ERROR: ${files.size} files exceeds the $MAX_FILES-file limit for snapshot push."

        var lastError = ""
        for (attempt in 1..3) {
            // Fresh remote state each attempt. Only 404 means "branch absent";
            // every other failure aborts instead of faking an empty repo.
            val tip = getBranchTip(token, m.slug, m.branch)
            val baseSha: String
            val baseTree: String
            when (tip) {
                is Tip.Error -> return "ERROR: cannot read ${m.slug}@${m.branch} — ${tip.msg}"
                is Tip.Missing -> { baseSha = ""; baseTree = "" }
                is Tip.Found -> { baseSha = tip.sha; baseTree = tip.treeSha }
            }

            try {
                // 1) blobs
                val treeEntries = JSONArray()
                var pushed = 0
                for (f in files) {
                    val relPath = f.relativeTo(dir).invariantSeparatorsPath
                    val bytes = f.readBytes()
                    val blobBody = JSONObject()
                        .put("content", Base64.getEncoder().encodeToString(bytes))
                        .put("encoding", "base64")
                    val blob = api("POST", "/repos/${m.slug}/git/blobs", token, blobBody)
                    treeEntries.put(
                        JSONObject()
                            .put("path", relPath)
                            .put("mode", "100644")
                            .put("type", "blob")
                            .put("sha", blob.getString("sha"))
                    )
                    pushed++
                }

                // 2) tree (rebuilt per attempt so base_tree always matches the parent)
                val treeBody = JSONObject().put("tree", treeEntries)
                if (baseTree.isNotBlank()) treeBody.put("base_tree", baseTree)
                val tree = api("POST", "/repos/${m.slug}/git/trees", token, treeBody)

                // 3) commit
                val commitBody = JSONObject()
                    .put("message", message)
                    .put("tree", tree.getString("sha"))
                if (baseSha.isNotBlank()) commitBody.put("parents", JSONArray().put(baseSha))
                val commit = api("POST", "/repos/${m.slug}/git/commits", token, commitBody)

                // 4) ref
                val newSha = commit.getString("sha")
                if (baseSha.isNotBlank()) {
                    updateRef(token, m.slug, m.branch, newSha, force = force && attempt == 3)
                } else {
                    try {
                        api("POST", "/repos/${m.slug}/git/refs", token,
                            JSONObject().put("ref", "refs/heads/${m.branch}").put("sha", newSha))
                    } catch (e: Exception) {
                        // Branch appeared meanwhile (or race) — retry reads it properly.
                        throw RefConflict("ref create rejected: ${e.message?.take(120)}")
                    }
                }

                writeMeta(dir, m.slug, m.branch, newSha)
                m = readMeta(dir) ?: m
                return "OK: pushed $pushed files → ${m.slug}@${m.branch}\nCommit: $newSha\n${message.take(100)}"
            } catch (e: RefConflict) {
                lastError = e.message ?: "conflict"
                continue
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                break
            }
        }

        return buildString {
            append("ERROR: push failed — $lastError\n")
            append("The remote branch moved while pushing (edited on github.com or another device?).\n")
            append("Fix: github_pull to take the remote version first, or ask me to push again with force=true to overwrite the remote.")
        }
    }

    private sealed class Tip {
        class Found(val sha: String, val treeSha: String) : Tip()
        object Missing : Tip()
        class Error(val msg: String) : Tip()
    }

    private class RefConflict(msg: String) : RuntimeException(msg)

    private fun getBranchTip(token: String, slug: String, branch: String): Tip {
        val req = requestBuilder("GET", "/repos/$slug/branches/$branch", token, null).build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.isSuccessful -> {
                    val j = runCatching { JSONObject(resp.body?.string() ?: "{}") }.getOrElse { return Tip.Error("bad response") }
                    // Branch API shape: { name, commit: { sha, commit: { tree: { sha } } } }
                    val commitObj = j.optJSONObject("commit")
                    val sha = commitObj?.optString("sha", "").orEmpty().ifBlank { j.optString("sha", "") }
                    val tree = commitObj?.optJSONObject("commit")
                        ?.optJSONObject("tree")?.optString("sha", "").orEmpty()
                    return if (sha.isBlank()) Tip.Error("branch response missing commit.sha") else Tip.Found(sha, tree)
                }
                resp.code == 404 -> return Tip.Missing
                else -> {
                    val msg = runCatching { JSONObject(resp.body?.string() ?: "{}").optString("message") }.getOrDefault("")
                    return Tip.Error("HTTP ${resp.code} ${msg.take(120)}")
                }
            }
        }
    }

    private fun updateRef(token: String, slug: String, branch: String, newSha: String, force: Boolean) {
        try {
            api("PATCH", "/repos/$slug/git/refs/heads/$branch", token,
                JSONObject().put("sha", newSha).put("force", force))
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (!force && msg.contains("fast", ignoreCase = true)) throw RefConflict("non-fast-forward")
            if (msg.contains("422")) throw RefConflict(msg)
            throw e
        }
    }

    fun createRepo(token: String, name: String, isPrivate: Boolean): String {
        if (token.isBlank()) return "ERROR: no GitHub token saved. Add one in Settings → GitHub."
        val cleanName = name.trim().trim('/').substringAfterLast('/')
        if (cleanName.isEmpty()) return "ERROR: invalid repo name"
        val body = JSONObject().put("name", cleanName).put("private", isPrivate)
            .put("auto_init", false).put("description", "Created by SemCode AI on Android")
        val r = api("POST", "/user/repos", token, body)
        val full = r.optString("full_name", r.optString("name", cleanName))
        return "OK: created https://github.com/$full"
    }

    fun testToken(token: String): String {
        if (token.isBlank()) return "No token saved"
        val user = api("GET", "/user", token, null)
        return "Connected as ${user.optString("login")}"
    }

    // ---------- internals ----------

    private const val META_DIR = ".semcode"
    private const val MAX_FILES = 400
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private data class Meta(val slug: String, val branch: String, val lastSha: String)

    private fun writeMeta(dir: File, slug: String, branch: String, sha: String) {
        val d = File(dir, META_DIR); d.mkdirs()
        File(d, "meta.json").writeText(
            JSONObject().put("repo", slug).put("branch", branch).put("last_pushed_sha", sha).toString()
        )
    }

    private fun readMeta(dir: File): Meta? = runCatching {
        val f = File(File(dir, META_DIR), "meta.json")
        if (!f.isFile) return null
        val j = JSONObject(f.readText())
        Meta(j.optString("repo"), j.optString("branch", "main"), j.optString("last_pushed_sha", ""))
    }.getOrNull()

    private fun collectFiles(dir: File): List<File> {
        val out = mutableListOf<File>()
        fun walk(d: File) {
            d.listFiles()?.forEach { f ->
                when {
                    f.name == META_DIR -> Unit
                    f.isDirectory -> walk(f)
                    else -> if (f.length() <= MAX_FILE_BYTES) out += f
                }
            }
        }
        walk(dir)
        return out.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
    }

    private fun countFiles(dir: File): Int {
        var n = 0
        fun walk(d: File) {
            d.listFiles()?.forEach { if (it.isDirectory) walk(it) else n++ }
        }
        walk(dir)
        return n
    }

    private fun deleteRec(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRec(it) }
        f.delete()
    }

    private fun downloadZipball(token: String, slug: String, branch: String, dest: File, stripTopLevel: Boolean) {
        dest.mkdirs()
        val req = requestBuilder("GET", "/repos/$slug/zipball/$branch", token, null).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("download failed: HTTP ${resp.code}")
            val tmp = File.createTempFile("semcode", ".zip")
            tmp.outputStream().use { resp.body!!.byteStream().copyTo(it) }
            unzip(tmp, dest, stripTopLevel)
            tmp.delete()
        }
    }

    private fun unzip(zip: File, dest: File, stripTopLevel: Boolean) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val parts = entry.name.split('/', limit = 2)
                val rel = if (stripTopLevel && parts.size == 2) parts[1] else entry.name
                if (rel.isNotBlank() && !rel.contains("..")) {
                    val out = File(dest, rel)
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun slug(input: String): String? {
        val s = input.trim().removeSuffix("/").removeSuffix(".git")
        val cleaned = s
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .removePrefix("git@github.com:")
        val parts = cleaned.split("/")
        return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
            "${parts[0]}/${parts[1]}" else null
    }

    private fun requestBuilder(method: String, path: String, token: String, body: JSONObject?): Request.Builder {
        val b = Request.Builder()
            .url("https://api.github.com$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "SemCodeAI-Android")
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        return when (method) {
            "POST" -> b.post((body ?: JSONObject()).toString().toRequestBody(json))
            "PATCH" -> b.patch((body ?: JSONObject()).toString().toRequestBody(json))
            else -> b.get()
        }
    }

    private fun api(method: String, path: String, token: String, body: JSONObject?): JSONObject {
        val req = requestBuilder(method, path, token, body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
                throw RuntimeException(
                    when (resp.code) {
                        401 -> "GitHub rejected the token (401). Check Settings → GitHub."
                        403 -> "GitHub refused (403): ${msg.take(200)}"
                        404 -> "Not found (404): $path — wrong name or private without token access."
                        422 -> "Rejected (422): ${msg.take(200)}"
                        else -> "GitHub HTTP ${resp.code}: ${msg.take(200).ifBlank { text.take(200) }}"
                    }
                )
            }
            return if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        }
    }
}
