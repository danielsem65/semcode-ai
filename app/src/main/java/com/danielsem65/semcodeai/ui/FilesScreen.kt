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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danielsem65.semcodeai.fs.FileOps
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    var currentDir by remember { mutableStateOf(FileOps.baseDir) }
    var clipboard by remember { mutableStateOf<Pair<File, Boolean>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<FilesDialog?>(null) }

    val entries = remember(currentDir, refresh) {
        currentDir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
    }
    val canGoUp = currentDir.absolutePath != "/"

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (canGoUp) {
                    IconButton(onClick = { currentDir = currentDir.parentFile ?: currentDir }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                    }
                }
                Text(
                    currentDir.path,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            Row {
                IconButton(onClick = { clipboard?.let { cb ->
                    FileOps.execute(if (cb.second) "move_path" else "copy_path",
                        org.json.JSONObject().put("source", cb.first.path).put("destination", currentDir.path))
                    if (cb.second) clipboard = null
                    refresh++
                } },
                    enabled = clipboard != null) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                }
                IconButton(onClick = { dialog = FilesDialog.NewFolder }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                }
                IconButton(onClick = { dialog = FilesDialog.NewFile }) {
                    Icon(Icons.Filled.NoteAdd, contentDescription = "New file")
                }
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
            items(entries, key = { it.absolutePath }) { entry ->
                Card(
                    onClick = {
                        if (entry.isDirectory) currentDir = entry
                        else dialog = FilesDialog.Preview(entry)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (entry.isDirectory) "${entry.listFiles()?.size ?: 0} items"
                                else FileOps.humanSize(entry.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            IconButton(onClick = { clipboard = entry to false }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", modifier = Modifier.padding(2.dp))
                            }
                            IconButton(onClick = { clipboard = entry to true }) {
                                Icon(Icons.Filled.ContentCut, contentDescription = "Cut", modifier = Modifier.padding(2.dp))
                            }
                            IconButton(onClick = { dialog = FilesDialog.Rename(entry) }) {
                                Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Rename", modifier = Modifier.padding(2.dp))
                            }
                            IconButton(onClick = {
                                FileOps.execute("delete_path",
                                    org.json.JSONObject().put("path", entry.path))
                                refresh++
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(2.dp))
                            }
                            IconButton(onClick = { dialog = FilesDialog.Info(entry) }) {
                                Icon(Icons.Filled.Info, contentDescription = "Info", modifier = Modifier.padding(2.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    dialog?.let { d ->
        when (d) {
            is FilesDialog.Preview -> AlertDialog(
                onDismissRequest = { dialog = null },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
                title = { Text(d.file.name) },
                text = {
                    Text(
                        runCatching { d.file.readText().take(4000).ifEmpty { "(empty)" } }
                            .getOrElse { "(binary or unreadable: ${it.message})" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            )
            is FilesDialog.Info -> AlertDialog(
                onDismissRequest = { dialog = null },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
                title = { Text(d.file.name) },
                text = { Text(FileOps.execute("get_file_info", org.json.JSONObject().put("path", d.file.path))) }
            )
            is FilesDialog.Rename -> NameDialog(
                title = "Rename",
                initial = d.file.name,
                onDismiss = { dialog = null }
            ) { newName ->
                FileOps.execute("move_path", org.json.JSONObject()
                    .put("source", d.file.path)
                    .put("destination", File(d.file.parentFile, newName).path))
                dialog = null
                refresh++
            }
            FilesDialog.NewFolder -> NameDialog(
                title = "New folder",
                initial = "",
                onDismiss = { dialog = null }
            ) { name ->
                FileOps.execute("create_folder", org.json.JSONObject()
                    .put("path", File(currentDir, name).path))
                dialog = null
                refresh++
            }
            FilesDialog.NewFile -> NameDialog(
                title = "New file",
                initial = "",
                onDismiss = { dialog = null }
            ) { name ->
                FileOps.execute("write_file", org.json.JSONObject()
                    .put("path", File(currentDir, name).path)
                    .put("content", ""))
                dialog = null
                refresh++
            }
        }
    }
}

private sealed class FilesDialog {
    data class Preview(val file: File) : FilesDialog()
    data class Info(val file: File) : FilesDialog()
    data class Rename(val file: File) : FilesDialog()
    object NewFolder : FilesDialog()
    object NewFile : FilesDialog()
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            FilledIconButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text("  OK  ")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true
            )
        }
    )
}
