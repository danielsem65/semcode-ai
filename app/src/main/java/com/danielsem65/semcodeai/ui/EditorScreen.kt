package com.danielsem65.semcodeai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

private val EDITOR_FONT = 15.sp
private val EDITOR_LINE_HEIGHT_SP = 21

/** Undo/redo entry: previous text snapshot. */
private class Snap(val text: String)

@Composable
fun EditorScreen(path: String, onClose: (changed: Boolean) -> Unit) {
    val file = remember(path) { File(path) }
    var original by remember(path) {
        mutableStateOf(runCatching { file.readText() }.getOrElse { "(unreadable: ${it.message})" })
    }
    var text by remember(path) { mutableStateOf(original) }
    var changed by remember(path) { mutableStateOf(false) }

    val undoStack = remember { mutableListOf<Snap>() }
    val redoStack = remember { mutableListOf<Snap>() }
    var lastEditMs by remember { mutableStateOf(0L) }

    var showFind by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf("") }

    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val lineHeightPx = with(density) { EDITOR_LINE_HEIGHT_SP.sp.toDp().toPx() }
    val scope = rememberCoroutineScope()

    fun jumpTo(charIndex: Int) {
        scope.launch {
            if (charIndex <= 0) { scroll.scrollTo(0); return@launch }
            var line = 0
            for (i in 0 until charIndex.coerceAtMost(text.length)) if (text[i] == '\n') line++
            val target = ((line - 6).coerceAtLeast(0) * lineHeightPx).toInt()
            scroll.scrollTo(target)
        }
    }

    fun pushHistory(prev: String) {
        val now = System.currentTimeMillis()
        if (now - lastEditMs > 400 || undoStack.isEmpty()) {
            undoStack.add(Snap(prev))
            if (undoStack.size > 200) undoStack.removeAt(0)
            redoStack.clear()
        }
        lastEditMs = now
    }

    fun setBody(newText: String) {
        pushHistory(text)
        text = newText
        if (!changed && newText != original) changed = true
        if (toast.isNotBlank()) toast = ""
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
                            append("${text.lines().size} lines")
                            if (changed) append(" - unsaved changes")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    enabled = undoStack.isNotEmpty(),
                    onClick = {
                        val s = undoStack.removeAt(undoStack.size - 1)
                        redoStack.add(Snap(text))
                        text = s.text
                        changed = s.text != original
                    }
                ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
                IconButton(
                    enabled = redoStack.isNotEmpty(),
                    onClick = {
                        val s = redoStack.removeAt(redoStack.size - 1)
                        undoStack.add(Snap(text))
                        text = s.text
                        changed = s.text != original
                    }
                ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
                IconButton(onClick = { showFind = true }) {
                    Icon(Icons.Filled.Search, contentDescription = "Find and replace")
                }
                IconButton(onClick = { wrap = !wrap }) {
                    Icon(
                        if (wrap) Icons.Filled.FormatAlignJustify else Icons.Filled.FormatAlignLeft,
                        contentDescription = if (wrap) "Unwrap lines" else "Wrap lines"
                    )
                }
                TextButton(
                    onClick = {
                        runCatching { file.writeText(text) }.onSuccess {
                            original = text
                            changed = false
                            toast = "Saved"
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

            Row(
                Modifier.fillMaxSize().verticalScroll(scroll)
                    .padding(top = 4.dp, bottom = 32.dp)
            ) {
                Column(
                    Modifier
                        .width(52.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 8.dp)
                ) {
                    val lines = text.count { it == '\n' } + 1
                    for (i in 1..lines) {
                        Text(
                            i.toString(),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = EDITOR_FONT,
                                lineHeight = EDITOR_LINE_HEIGHT_SP.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                if (wrap) {
                    BasicTextField(
                        value = text,
                        onValueChange = { setBody(it) },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = EDITOR_FONT,
                            lineHeight = EDITOR_LINE_HEIGHT_SP.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                } else {
                    Row(Modifier.weight(1f).horizontalScroll(hScroll)) {
                        BasicTextField(
                            value = text,
                            onValueChange = { setBody(it) },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = EDITOR_FONT,
                                lineHeight = EDITOR_LINE_HEIGHT_SP.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.padding(start = 8.dp, end = 48.dp, top = 8.dp, bottom = 8.dp)
                        )
                    }
                }
            }
        }

        if (showFind) {
            FindReplaceDialog(
                text = text,
                onApply = { setBody(it) },
                onNext = { jumpTo(it) },
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
private fun FindReplaceDialog(
    text: String,
    onApply: (String) -> Unit,
    onNext: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var find by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var idx by remember { mutableStateOf(-1) }

    val total = if (find.isEmpty()) 0 else {
        var n = 0; var p = text.indexOf(find)
        while (p >= 0) { n++; p = text.indexOf(find, p + find.length) }
        n
    }

    fun findNext() {
        if (find.isEmpty() || total == 0) { msg = "Not found"; return }
        idx = if (idx < 0 || idx + 1 >= total) 0 else idx + 1
        var seen = 0; var p = text.indexOf(find)
        while (p >= 0) {
            if (seen == idx) { onNext(p); msg = "Match ${idx + 1} of $total (line ${text.substring(0, p).count { it == '\n' } + 1})"; break }
            seen++; p = text.indexOf(find, p + find.length)
        }
    }

    fun replaceOne() {
        if (find.isEmpty()) return
        val at = if (idx in 0 until total) {
            var seen = 0; var p = text.indexOf(find)
            while (p >= 0) { if (seen == idx) break; seen++; p = text.indexOf(find, p + find.length) }
            p
        } else text.indexOf(find)
        if (at >= 0) {
            onApply(text.replaceRange(at, at + find.length, replaceWith))
            msg = "Replaced 1 occurrence"
            idx = -1
        } else msg = "Not found"
    }

    fun replaceAll() {
        if (find.isEmpty()) return
        var count = 0
        val sb = StringBuilder(text)
        var p = sb.indexOf(find)
        while (p >= 0) {
            sb.replace(p, p + find.length, replaceWith)
            count++
            p = sb.indexOf(find, p + replaceWith.length)
        }
        if (count > 0) { onApply(sb.toString()); msg = "Replaced $count occurrence(s)"; idx = -1 }
        else msg = "Not found"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find and replace") },
        text = {
            Column {
                OutlinedTextField(
                    value = find, onValueChange = { find = it; msg = ""; idx = -1 },
                    label = { Text(if (total > 0) "Find ($total matches)" else "Find") },
                    singleLine = true,
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
            Row {
                TextButton(onClick = { findNext() }, enabled = find.isNotEmpty()) { Text("Next") }
                TextButton(onClick = { replaceOne() }, enabled = find.isNotEmpty()) { Text("Replace") }
                TextButton(onClick = { replaceAll() }, enabled = find.isNotEmpty()) { Text("All") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    )
}
