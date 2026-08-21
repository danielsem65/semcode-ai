package com.danielsem65.semcodeai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielsem65.semcodeai.AppViewModel
import com.danielsem65.semcodeai.SemApp
import androidx.compose.ui.platform.LocalContext

@Composable
fun TerminalScreen(vm: AppViewModel) {
    val lines by vm.termLines.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val context = LocalContext.current
    val app = context.applicationContext as SemApp
    val cwd = app.session.cwd

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TERMINAL · ${cwd.take(40)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.termInterrupt() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reset shell",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.termClear() }) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            items(lines) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (line.startsWith("$")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text("$ ", fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 2.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("ls, cat, grep…", fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                IconButton(onClick = { vm.termRun(input); input = "" }, enabled = input.isNotBlank()) {
                    Icon(Icons.Filled.Send, contentDescription = "Run",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
