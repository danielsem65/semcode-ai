package com.danielsem65.semcodeai.ai

object Tools {

    fun all(): List<ToolDef> {
        val S = ToolDef.STRING; val N = ToolDef.NUMBER; val B = ToolDef.BOOLEAN
        fun t(name: String, desc: String, vararg props: Pair<String, String>, required: List<String>) =
            ToolDef(name, desc, ToolDef.schema(*props, required = required))

        return listOf(
            t("list_files", "List entries of a directory (name, type, size).",
                "path" to S, required = listOf("path")),
            t("read_file", "Read a text file's full content (up to ~500KB; binary files are rejected).",
                "path" to S, required = listOf("path")),
            t("write_file", "Create or fully overwrite a text file. Parent folders are created automatically.",
                "path" to S, "content" to S, required = listOf("path", "content")),
            t("edit_file", "Exact string replace inside a file. old_string must match byte-for-byte and be unique — copy it from read_file output including indentation. Set replace_all=true only for intentional global replaces.",
                "path" to S, "old_string" to S, "new_string" to S, "replace_all" to B,
                required = listOf("path", "old_string", "new_string")),
            t("search_in_files", "Grep-style content search returning file:line:match. Skips binaries, .git and files >1MB.",
                "directory" to S, "query" to S, required = listOf("directory", "query")),
            t("search_files", "Find files by name using wildcards (* any chars, ? one char), case-insensitive.",
                "directory" to S, "pattern" to S, required = listOf("directory", "pattern")),
            t("create_folder", "Create a directory including missing parents.",
                "path" to S, required = listOf("path")),
            t("delete_path", "Permanently delete a file or an entire folder tree. User pre-authorized deletions.",
                "path" to S, required = listOf("path")),
            t("copy_path", "Recursively copy a file/folder. If destination is an existing folder, the item is copied INTO it.",
                "source" to S, "destination" to S, required = listOf("source", "destination")),
            t("move_path", "Move or rename a file/folder. Same into-folder rule as copy.",
                "source" to S, "destination" to S, required = listOf("source", "destination")),
            t("get_file_info", "Metadata for a path: size, modified date, permissions, entry count.",
                "path" to S, required = listOf("path")),
            t("run_command", "Execute one command in the persistent shared shell (same session as the user's Terminal tab). In Android mode it is toybox/mksh (no apt/git/python); in Linux mode it is a full distro via proot with apt/apk, git and python3, workspace mounted at /workspace. State survives between calls. stdin is closed. Default timeout 30s.",
                "command" to S, "timeout_seconds" to N, required = listOf("command")),
            t("github_clone", "Download a GitHub repository into a local project folder (snapshot of default/current branch).",
                "repo" to S, "path" to S, required = listOf("repo")),
            t("github_status", "Show sync info of a cloned project: repo, branch, last pushed commit, file count.",
                "path" to S, required = listOf("path")),
            t("github_push", "Commit ALL files in the project folder to GitHub as one snapshot commit on the synced branch. Optional boolean 'force': true overwrites the remote branch when a normal push is rejected because the remote moved (prefer github_pull first).",
                "path" to S, "message" to S, required = listOf("path", "message")),
            t("github_pull", "Re-download the latest version of the repo over the local project folder (local changes are overwritten).",
                "path" to S, required = listOf("path")),
            t("github_create_repo", "Create a new GitHub repository under the user's account.",
                "name" to S, "private" to B, required = listOf("name"))
        )
    }
}
