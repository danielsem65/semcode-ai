package com.danielsem65.semcodeai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielsem65.semcodeai.AppViewModel
import android.text.format.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsPanel(
    vm: AppViewModel,
    onNewChat: () -> Unit,
    onOpenProject: () -> Unit,
    onClose: () -> Unit
) {
    val projects by vm.projects.collectAsState()
    val activeId by vm.projectId.collectAsState()

    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var deleteName by remember { mutableStateOf("") }

    ModalDrawerSheet {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                "SemCode AI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp)
            )
            Text(
                "projects · saved automatically",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )

            NavigationDrawerItem(
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                label = { Text("New chat", fontWeight = FontWeight.SemiBold) },
                selected = false,
                onClick = {
                    vm.newChat()
                    onNewChat()
                    onClose()
                }
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (projects.isEmpty()) {
                Text(
                    "No projects yet.\nStart a chat — it saves itself here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                )
            } else {
                LazyColumn {
                    items(projects, key = { it.id }) { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.openProject(p.id)
                                    onOpenProject()
                                    onClose()
                                }
                                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.name + if (p.id == activeId) "  •" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (p.id == activeId) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${DateUtils.getRelativeTimeSpanString(p.updatedAt)} · ${p.messageCount} msgs",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                renameTarget = p.id
                                renameText = p.name
                            }) {
                                Icon(
                                    Icons.Filled.Edit, contentDescription = "Rename",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(2.dp)
                                )
                            }
                            IconButton(onClick = {
                                deleteTarget = p.id
                                deleteName = p.name
                            }) {
                                Icon(
                                    Icons.Filled.Delete, contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }

    // ---- rename dialog ----
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameProject(renameTarget!!, renameText)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // ---- delete dialog ----
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete project") },
            text = { Text("Delete \"$deleteName\" and its whole conversation? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(deleteTarget!!)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}
