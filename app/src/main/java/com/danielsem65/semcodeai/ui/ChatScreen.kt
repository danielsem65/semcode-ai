package com.danielsem65.semcodeai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.danielsem65.semcodeai.AppViewModel
import com.danielsem65.semcodeai.ChatMessage

@Composable
fun ChatScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val step by vm.stepText.collectAsState()
    val status by vm.statusLine.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SemCode AI", style = MaterialTheme.typography.titleLarge)
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { vm.clearChat() }) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item { WelcomeCard(onOpenSettings) }
            }
            items(messages, key = { it.hashCode() }) { msg -> Bubble(msg) }
            if (busy) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(step, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Describe what to build or fix…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp)
                )
                IconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank() && !busy,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .alpha(if (input.isBlank()) 0.35f else 1f)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun WelcomeCard(onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Your pocket coding agent", style = MaterialTheme.typography.titleMedium)
            Text(
                "I can write and edit real files on this device, run shell commands, and sync your projects to GitHub.\n\nTry:\n• \"Create a Kotlin CLI project named notes-cli that renames files by date\"\n• \"Search my workspace for TODO and fix the first one\"\n\nFirst time? Pick your AI provider and key:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
            Button(onClick = onOpenSettings) { Text("Set up AI provider →") }
        }
    }
}

@Composable
private fun Bubble(msg: ChatMessage) {
    val isUser = msg.role == ChatMessage.Role.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = when {
                isUser -> MaterialTheme.colorScheme.primary
                msg.isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                else -> MaterialTheme.colorScheme.surface
            },
            shape = RoundedCornerShape(16.dp),
            tonalElevation = if (!isUser && !msg.isError && !msg.isTool) 2.dp else 0.dp,
            modifier = Modifier.widthIn(max = 330.dp)
        ) {
            if (isUser) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
                )
            } else if (msg.isTool) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
                )
            } else {
                MarkdownText(msg.text, Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
            }
        }
    }
}
