package com.danielsem65.semcodeai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val EDITOR_FONT = 15.sp
private val EDITOR_LINE_HEIGHT = 21.sp

/** Snapshot for undo/redo: full text + selection. */
private class Snap(val text: String, val sel: TextRange)

@Composable
fun EditorScreen(path: String, onClose: (changed: Boolean) -> Unit) {
    val file = remember(path) { File(path) }
    var original by remember(path) {
        mutableStateOf(runCatching { file.readText() }.getOrElse { "(unreadable: ${it.message})" })
    }
    var value by remember(path) {
        mutableStateOf<TextFieldValue>(
            TextFieldValue(text = original, selection = TextRange(0))
        )
    }
    var changed by remember(path) { mutableStateOf(false) }

    // undo/redo history
    val undoStack = remember { mutableListOf<Snap>() }
    val redoStack = remember { mutableListOf<Snap>() }
    var lastEditMs by remember { mutableStateOf(0L) }

    var showFind by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf("") }

    fun pushHistory(prev: TextFieldValue) {
        val now = System.currentTimeMillis()
        if (now - lastEditMs > 400 || undoStack.isEmpty()) {
            undoStack.add(Snap(prev.text, prev.selection))
            if (undoStack.size > 200) undoStack.removeAt(0)
            redoStack.clear()
        }
        lastEditMs = now
    }

    fun applySnap(s: Snap) {
        value = TextFieldValue(s.text, s.sel)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (changed) confirmClose = true else onClose(false)
                }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        buildString {
                            append("${value.text.lines().size} lines")
                            if (changed) append(" · unsaved changes")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    enabled = undoStack.isNotEmpty(),
                    onClick = {
                        val s = undoStack.removeAt(undoStack.size - 1)
                        redoStack.add(Snap(value.text, value.selection))
                        applySnap(s); changed = s.text != original
                    }
                ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
                IconButton(
                    enabled = redoStack.isNotEmpty(),
                    onClick = {
                        val s = redoStack.removeAt(redoStack.size - 1)
                        undoStack.add(Snap(value.text, value.selection))
                        applySnap(s); changed = s.text != original
                    }
                ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
                IconButton(onClick = { showFind = true }) {
                    Icon(Icons.Filled.Search, contentDescription = "Find & replace")
                }
                IconButton(onClick = { wrap = !wrap }) {
                    Icon(
                        if (wrap) Icons.Filled.FormatAlignJustify else Icons.Filled.FormatAlignLeft,
                        contentDescription = if (wrap) "Unwrap lines" else "Wrap lines"
                    )
                }
                TextButton(
                    onClick = {
                        runCatching { file.writeText(value.text) }.onSuccess {
                            original = value.text
                            changed = false
                            toast = "Saved ✓"
                        }.onFailure { toast = "Save failed: ${it.message?.take(80)}" }
                    }
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(18.dp))
                    Text("Save", Modifier.padding(start = 4.dp))
                }
            }
            if (toast.isNotBlank()) {
                Text(
                    toast,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 14.dp)
                )
            }

            EditorBody(
                value = value,
                onValueChange = { new ->
                    pushHistory(value)
                    value = new
                    if (!changed && new.text != original) changed = true
                    if (toast.isNotBlank()) toast = ""
                },
                wrap = wrap
            )
        }

        if (showFind) {
            FindReplaceDialog(
                value = value,
                onApply = { v ->
                    pushHistory(value)
                    value = v
                    changed = v.text != original
                },
                onDismiss = { showFind = false }
            )
        }
        if (confirmClose) {
            AlertDialog(
                onDismissRequest = { confirmClose = false },
                title = { Text("Discard changes?") },
                text = { Text("You have unsaved edits in ${file.name}.") },
                confirmButton = {
                    TextButton(onClick = { confirmClose = false; onClose(changed) }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClose = false }) { Text("Keep editing") }
                }
            )
        }
    }
}

@Composable
private fun EditorBody(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    wrap: Boolean
) {
    val lineCount = value.text.count { it == '\n' } + 1
    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()

    Row(Modifier.fillMaxSize()) {
        // Line-number gutter shares the same vertical scroll as the text.
        Column(
            Modifier
                .width(52.dp)
                .fillMaxHeight()
                .verticalScroll(scroll)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp)
        ) {
            for (i in 1..lineCount) {
                Text(
                    i.toString(),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = EDITOR_FONT,
                        lineHeight = EDITOR_LINE_HEIGHT
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        if (wrap) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                softWrap = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = EDITOR_FONT,
                    lineHeight = EDITOR_LINE_HEIGHT,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(scroll)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                softWrap = false,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = EDITOR_FONT,
                    lineHeight = EDITOR_LINE_HEIGHT,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(scroll)
                    .horizontalScroll(hScroll)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun FindReplaceDialog(
    value: TextFieldValue,
    onApply: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit
) {
    var find by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    fun findNext() {
        if (find.isEmpty()) return
        val from = if (value.selection.end < value.text.length) value.selection.end else 0
        val idx = value.text.indexOf(find, from, ignoreCase = false)
        val found = if (idx >= 0) idx else value.text.indexOf(find, 0, ignoreCase = false)
        if (found >= 0) {
            onApply(value.copy(selection = TextRange(found, found + find.length)))
            msg = ""
        } else msg = "Not found"
    }

    fun replaceOne() {
        val sel = value.selection
        if (sel.length >= 0 &&
            sel.start >= 0 && sel.end <= value.text.length &&
            value.text.substring(sel.min, sel.max).equals(find, ignoreCase = true) && find.isNotEmpty()
        ) {
            val newText = value.text.replaceRange(sel.min, sel.max, replaceWith)
            val pos = sel.min + replaceWith.length
            onApply(TextFieldValue(newText, TextRange(pos, pos)))
            msg = ""
        } else findNext()
    }

    fun replaceAll() {
        if (find.isEmpty()) return
        var count = 0
        var idx = value.text.indexOf(find)
        val sb = StringBuilder(value.text)
        while (idx >= 0) {
            sb.replace(idx, idx + find.length, replaceWith)
            count++
            idx = sb.indexOf(find, idx + replaceWith.length)
        }
        if (count > 0) {
            onApply(TextFieldValue(sb.toString(), TextRange(0)))
            msg = "Replaced $count occurrence(s)"
        } else msg = "Not found"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find & replace") },
        text = {
            Column {
                OutlinedTextField(
                    value = find, onValueChange = { find = it; msg = "" },
                    label = { Text("Find") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replaceWith, onValueChange = { replaceWith = it; msg = "" },
                    label = { Text("Replace with") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (msg.isNotBlank()) {
                    Text(msg, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = { findNext() }, enabled = find.isNotEmpty()) { Text("Next") }
                TextButton(onClick = { replaceOne() }, enabled = find.isNotEmpty()) { Text("Replace") }
                TextButton(onClick = { replaceAll(); }, enabled = find.isNotEmpty()) { Text("All") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    )
}
