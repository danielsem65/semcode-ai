package com.danielsem65.semcodeai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.danielsem65.semcodeai.SemApp
import com.danielsem65.semcodeai.fs.FileOps
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val ops = (context.applicationContext as SemApp).fileOps

    var dir by remember { mutableStateOf(ops.root) }
    var clipboard by remember { mutableStateOf<Pair<File, Boolean>?>(null) } // file to cut?
    var refresh by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<FD?>(null) }

    val entries = remember(dir, refresh) {
        dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dir.path != "/") {
                IconButton(onClick = { dir = dir.parentFile ?: dir }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
            }
            Text(
                ops.rel(dir),
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
                                .put("destination", dir.path)
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
                        if (entry.isDirectory) dir = entry else dialog = FD.Preview(entry)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (entry.isDirectory) "${entry.listFiles()?.size ?: 0} items"
                                else FileOps.humanSize(entry.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RowMenu(
                            onCut = { clipboard = entry to true },
                            onCopy = { clipboard = entry to false },
                            onRename = { dialog = FD.Rename(entry) },
                            onDelete = {
                                ops.execute("delete_path", org.json.JSONObject().put("path", entry.path))
                                refresh++
                            },
                            onInfo = { dialog = FD.Info(entry) }
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is FD.Preview -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            title = { Text(d.file.name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    runCatching { d.file.readText().take(4000).ifEmpty { "(empty)" } }
                        .getOrElse { "(binary or unreadable)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        )
        is FD.Info -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            title = { Text(d.file.name, style = MaterialTheme.typography.titleSmall) },
            text = { Text(ops.execute("get_file_info", org.json.JSONObject().put("path", d.file.path))) }
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
        FD.NewFile -> NameDialog("New file", "",
            onDismiss = { dialog = null }) { name ->
            ops.execute("write_file", org.json.JSONObject()
                .put("path", File(dir, name).path).put("content", ""))
            dialog = null; refresh++
        }
        null -> Unit
    }
}

@Composable
private fun RowMenu(
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
    data class Preview(val file: File) : FD()
    data class Info(val file: File) : FD()
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

/** tiny wrapper so the paste lambda can ignore the result string */

