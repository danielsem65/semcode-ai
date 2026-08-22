package com.danielsem65.semcodeai.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.danielsem65.semcodeai.SemApp
import com.danielsem65.semcodeai.fs.FileOps
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val TEXT_EXTS = setOf(
    "txt", "md", "markdown", "json", "xml", "js", "jsx", "ts", "tsx", "css", "scss", "less",
    "html", "htm", "csv", "log", "properties", "gradle", "kt", "kts", "java", "py", "rb",
    "go", "rs", "c", "h", "cpp", "hpp", "cs", "sh", "bat", "yml", "yaml", "toml", "ini",
    "cfg", "conf", "sql", "php", "swift", "m", "dart", "r", "env"
)

private val NO_EXT_NAMES = setOf("gitignore", "gitattributes", "dockerfile", "license", "readme", "makefile")

fun isTextFile(f: File): Boolean {
    if (f.isDirectory) return false
    val name = f.name.lowercase()
    val ext = name.substringAfterLast('.', "")
    if (ext.isEmpty()) return name.removePrefix(".") in NO_EXT_NAMES || name in NO_EXT_NAMES
    return ext in TEXT_EXTS
}

private fun isZip(f: File) = f.isFile && f.name.lowercase().endsWith(".zip")
private fun isHtml(f: File) = f.isFile && f.extension.lowercase() in setOf("html", "htm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val ops = (context.applicationContext as SemApp).fileOps

    var dir by remember { mutableStateOf(ops.root) }
    var clipboard by remember { mutableStateOf<Pair<File, Boolean>?>(null) } // second = cut
    var refresh by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<FD?>(null) }
    var editorFile by remember { mutableStateOf<File?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }

    val entries = remember(dir, refresh) {
        dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { dir = dir.parentFile ?: dir }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
                Text(
                    ops.rel(dir).ifBlank { "." },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                )
                IconButton(
                    onClick = {
                        clipboard?.let { cb ->
                            ops.execute(
                                if (cb.second) "move_path" else "copy_path",
                                org.json.JSONObject().put("source", cb.first.path)
                                    .put("destination", File(dir, cb.first.name).path)
                            )
                            if (cb.second) clipboard = null
                            refresh++
                        }
                    },
                    enabled = clipboard != null
                ) { Icon(Icons.Filled.ContentPaste, contentDescription = "Paste here") }
                IconButton(onClick = { dialog = FD.NewFolder }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                }
                IconButton(onClick = { dialog = FD.NewFile }) {
                    Icon(Icons.Filled.NoteAdd, contentDescription = "New file")
                }
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries, key = { it.absolutePath }) { entry ->
                    Card(
                        onClick = {
                            when {
                                entry.isDirectory -> dir = entry
                                isHtml(entry) -> previewFile = entry
                                isTextFile(entry) -> editorFile = entry
                                else -> dialog = FD.Info(entry)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when {
                                    entry.isDirectory -> Icons.Filled.Folder
                                    isZip(entry) -> Icons.Filled.FolderZip
                                    isHtml(entry) -> Icons.Filled.Language
                                    isTextFile(entry) -> Icons.Filled.Code
                                    else -> Icons.Filled.Description
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (entry.isDirectory) "${entry.listFiles()?.size ?: 0} items"
                                    else FileOps.humanSize(entry.length()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RowMenu(
                                onOpen = {
                                    when {
                                        entry.isDirectory -> dir = entry
                                        isHtml(entry) -> previewFile = entry
                                        isTextFile(entry) -> editorFile = entry
                                        else -> dialog = FD.Info(entry)
                                    }
                                },
                                onEdit = { editorFile = entry },
                                onPreview = { previewFile = entry },
                                onExtract = {
                                    val dest = File(entry.parentFile, entry.name.removeSuffix(".zip"))
                                    runCatching { unzipTo(entry, dest) }
                                        .onFailure { dialog = FD.Message("Extract failed", it.message ?: "unknown error") }
                                    refresh++
                                },
                                onCompress = {
                                    val out = File(entry.parentFile, "${entry.name}.zip")
                                    runCatching { zipTo(entry, out) }
                                        .onFailure { dialog = FD.Message("Compress failed", it.message ?: "unknown error") }
                                    refresh++
                                },
                                onCut = { clipboard = entry to true },
                                onCopy = { clipboard = entry to false },
                                onRename = { dialog = FD.Rename(entry) },
                                onDelete = { dialog = FD.ConfirmDelete(entry) },
                                onInfo = { dialog = FD.Info(entry) }
                            )
                        }
                    }
                }
            }
        }

        // ---- overlays ----
        editorFile?.let { f ->
            TextEditor(
                file = f,
                onClose = { edited ->
                    editorFile = null
                    if (edited) refresh++
                }
            )
        }
        previewFile?.let { f ->
            HtmlPreview(file = f, onClose = { previewFile = null }, onEdit = {
                previewFile = null
                editorFile = f
            })
        }
    }

    when (val d = dialog) {
        is FD.Info -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            title = { Text(d.file.name, style = MaterialTheme.typography.titleSmall) },
            text = { Text(ops.execute("get_file_info", org.json.JSONObject().put("path", d.file.path))) }
        )
        is FD.Message -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } },
            title = { Text(d.title) },
            text = { Text(d.message) }
        )
        is FD.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.file.name}?") },
            text = { Text(if (d.file.isDirectory) "The folder and everything inside will be removed." else "This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    ops.execute("delete_path", org.json.JSONObject().put("path", d.file.path))
                    dialog = null; refresh++
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
        is FD.Rename -> NameDialog("Rename", d.file.name,
            onDismiss = { dialog = null }) { newName ->
            ops.execute("move_path", org.json.JSONObject()
                .put("source", d.file.path)
                .put("destination", File(d.file.parentFile, newName).path))
            dialog = null; refresh++
        }
        FD.NewFolder -> NameDialog("New folder", "",
            onDismiss = { dialog = null }) { name ->
            ops.execute("create_folder", org.json.JSONObject()
                .put("path", File(dir, name).path))
            dialog = null; refresh++
        }
        FD.NewFile -> NameDialog("New file (with extension)", "",
            onDismiss = { dialog = null }) { name ->
            ops.execute("write_file", org.json.JSONObject()
                .put("path", File(dir, name).path).put("content", ""))
            dialog = null; refresh++
        }
        null -> Unit
    }
}

// ---------------- text editor ----------------

@Composable
private fun TextEditor(file: File, onClose: (edited: Boolean) -> Unit) {
    val sizeBytes = remember(file.path) { runCatching { file.length() }.getOrDefault(0L) }
    val readOnly = sizeBytes > 256 * 1024
    val viewOnly = sizeBytes > 2 * 1024 * 1024

    var original by remember(file.path) {
        mutableStateOf(
            runCatching { file.readText() }.getOrElse { "(unreadable: ${it.message})" }
        )
    }
    var text by remember(file.path) { mutableStateOf(original) }
    val dirty = text != original

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (!dirty || viewOnly) onClose(false)
                    else onClose(false) // discard silently; user chose close — keep simple
                }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        buildString {
                            append(FileOps.humanSize(sizeBytes))
                            append(" · ${text.lines().size} lines")
                            if (viewOnly) append(" · too large to edit")
                            else if (readOnly) append(" · read-only (large)")
                            if (dirty) append(" · unsaved")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!viewOnly && !readOnly) {
                    TextButton(
                        onClick = {
                            runCatching { file.writeText(text) }.onSuccess {
                                original = text
                                onClose(true)
                            }
                        },
                        enabled = dirty
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(18.dp))
                        Text("Save", Modifier.padding(start = 4.dp))
                    }
                } else {
                    TextButton(onClick = { onClose(viewOnly.not()) }) { Text("Done") }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { if (!readOnly && !viewOnly) text = it },
                readOnly = readOnly || viewOnly,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

// ---------------- html preview ----------------

@Composable
private fun HtmlPreview(file: File, onClose: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Column(Modifier.weight(1f)) {
                Text("Preview", style = MaterialTheme.typography.titleSmall)
                Text(file.name, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            TextButton(onClick = onEdit) { Text("Edit") }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = true
                    settings.loadWithOverviewMode = true
                    webViewClient = WebViewClient()
                }
            },
            update = { wv -> wv.loadUrl("file://${file.absolutePath}") },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ---------------- zip ----------------

private fun zipTo(src: File, out: File) {
    if (out.exists()) out.delete()
    ZipOutputStream(FileOutputStream(out)).use { zos ->
        fun walk(f: File, rel: String) {
            if (f.isDirectory) {
                f.listFiles()?.forEach { walk(it, if (rel.isEmpty()) it.name else "$rel/${it.name}") }
            } else {
                zos.putNextEntry(ZipEntry(rel.ifEmpty { f.name }))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        walk(src, "")
    }
}

private fun unzipTo(zip: File, dest: File) {
    dest.mkdirs()
    ZipInputStream(zip.inputStream().buffered()).use { zis ->
        var e = zis.nextEntry
        while (e != null) {
            val rel = e.name
            if (rel.isNotBlank() && !rel.contains("..")) {
                val out = File(dest, rel)
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
            }
            zis.closeEntry()
            e = zis.nextEntry
        }
    }
}

// ---------------- menus & dialogs ----------------

@Composable
private fun RowMenu(
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onPreview: () -> Unit,
    onExtract: () -> Unit,
    onCompress: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Actions",
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(Icons.Filled.Folder, null) }, onClick = { open = false; onOpen() })
        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Code, null) }, onClick = { open = false; onEdit() })
        DropdownMenuItem(text = { Text("Preview in browser view") }, leadingIcon = { Icon(Icons.Filled.Language, null) }, onClick = { open = false; onPreview() })
        DropdownMenuItem(text = { Text("Extract here") }, leadingIcon = { Icon(Icons.Filled.FolderZip, null) }, onClick = { open = false; onExtract() })
        DropdownMenuItem(text = { Text("Compress to ZIP") }, leadingIcon = { Icon(Icons.Filled.Archive, null) }, onClick = { open = false; onCompress() })
        DropdownMenuItem(text = { Text("Cut") }, leadingIcon = { Icon(Icons.Filled.ContentCut, null) }, onClick = { open = false; onCut() })
        DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { open = false; onCopy() })
        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) }, onClick = { open = false; onRename() })
        DropdownMenuItem(text = { Text("Info") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { open = false; onInfo() })
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { open = false; onDelete() }
        )
    }
}

private sealed class FD {
    data class Info(val file: File) : FD()
    data class Message(val title: String, val message: String) : FD()
    data class ConfirmDelete(val file: File) : FD()
    data class Rename(val file: File) : FD()
    object NewFolder : FD()
    object NewFile : FD()
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
