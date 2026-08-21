package com.danielsem65.semcodeai.git

import com.danielsem65.semcodeai.fs.FileOps
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

object GitOps {

    fun execute(name: String, args: JSONObject, gitUser: String?, gitToken: String?): String = try {
        when (name) {
            "git_clone" -> clone(args.getString("url"), args.getString("path"), gitUser, gitToken)
            "git_status" -> status(args.getString("path"))
            "git_stage" -> stage(args.getString("path"), args.optString("pattern", ".").ifBlank { "." })
            "git_commit" -> commit(args.getString("path"), args.getString("message"))
            "git_pull" -> pull(args.getString("path"), gitUser, gitToken)
            "git_push" -> push(args.getString("path"), gitUser, gitToken)
            else -> "ERROR: unknown git tool '$name'"
        }
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    private fun creds(user: String?, token: String?): UsernamePasswordCredentialsProvider? =
        if (!user.isNullOrBlank() && !token.isNullOrBlank()) UsernamePasswordCredentialsProvider(user, token) else null

    private fun clone(url: String, pathInput: String, u: String?, t: String?): String {
        val dst = FileOps.resolve(pathInput)
        if (dst.exists() && dst.listFiles()?.isNotEmpty() == true)
            return "ERROR: destination not empty: ${dst.path}"
        val cmd = Git.cloneRepository().setURI(url).setDirectory(dst)
        creds(u, t)?.let { cmd.setCredentialsProvider(it) }
        cmd.call()
        return "OK: cloned $url into ${dst.path}"
    }

    private fun open(pathInput: String): Git = Git.open(File(pathInput).takeIf { it.isDirectory } ?: FileOps.resolve(pathInput))

    private fun status(p: String): String = open(p).use { git ->
        val s = git.status().call()
        val sb = StringBuilder()
        sb.appendLine("Branch: ${git.repository.branch}")
        if (s.added.isNotEmpty()) sb.appendLine("Staged (added): ${s.added}")
        if (s.changed.isNotEmpty()) sb.appendLine("Staged (changed): ${s.changed}")
        if (s.removed.isNotEmpty()) sb.appendLine("Staged (removed): ${s.removed}")
        if (s.modified.isNotEmpty()) sb.appendLine("Modified, unstaged: ${s.modified}")
        if (s.untracked.isNotEmpty()) sb.appendLine("Untracked: ${s.untracked.take(50)}")
        if (sb.count { it == '\n' } <= 1) sb.appendLine("(clean)")
        sb.toString().trimEnd()
    }

    private fun stage(p: String, pattern: String): String = open(p).use { git ->
        git.add().addFilepattern(pattern).call()
        // update=true also stages modifications & deletions of already-tracked files
        git.add().setUpdate(true).addFilepattern(pattern).call()
        "OK: staged '$pattern'"
    }

    private fun commit(p: String, message: String): String = open(p).use { git ->
        git.commit()
            .setMessage(message)
            .setAuthor("SemCode AI", "semcode@local")
            .setCommitter("SemCode AI", "semcode@local")
            .call()
        "OK: committed \"${message.take(80)}\""
    }

    private fun pull(p: String, u: String?, t: String?): String = open(p).use { git ->
        val cmd = git.pull()
        creds(u, t)?.let { cmd.setCredentialsProvider(it) }
        val result = cmd.call()
        val fetches = result.fetchResult?.trackingRefUpdates?.size ?: 0
        "OK: pulled ($fetches ref updates, merge=${result.mergeResult?.mergeStatus})"
    }

    private fun push(p: String, u: String?, t: String?): String {
        val cp = creds(u, t)
            ?: return "ERROR: no GitHub credentials saved. Add your username + token in Settings."
        open(p).use { git ->
            git.push().setCredentialsProvider(cp).call()
            return "OK: pushed to remote"
        }
    }
}
