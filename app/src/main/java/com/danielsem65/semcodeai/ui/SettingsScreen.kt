package com.danielsem65.semcodeai.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.danielsem65.semcodeai.AppViewModel
import com.danielsem65.semcodeai.ai.Providers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current

    var selectedId by remember { mutableStateOf(vm.activeProvider().id) }
    var keyDraft by remember { mutableStateOf("") }
    var modelDraft by remember {
        mutableStateOf(if (vm.settings.modelOverride.isBlank()) "" else vm.settings.modelOverride)
    }
    var gitUser by remember { mutableStateOf(vm.settings.gitUser) }
    var gitToken by remember { mutableStateOf(vm.settings.gitToken) }
    var dropdownOpen by remember { mutableStateOf(false) }

    val selected = Providers.byId(selectedId)
    val savedKey = vm.settings.apiKey(selected.id)
    val storageGranted = remember { mutableStateOf(hasStoragePermission()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text(
            "Active: ${vm.activeProvider().displayName} · ${vm.effectiveModel()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp)
        )

        // ---------- AI provider ----------
        Card(modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("AI Provider", style = MaterialTheme.typography.titleMedium)

                ExposedDropdownMenuBox(
                    expanded = dropdownOpen,
                    onExpandedChange = { dropdownOpen = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    OutlinedTextField(
                        value = selected.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownOpen) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownOpen,
                        onDismissRequest = { dropdownOpen = false }
                    ) {
                        Providers.ALL.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Text("${p.displayName}${if (vm.settings.hasKeyFor(p.id)) "  ✓" else ""}")
                                },
                                onClick = {
                                    selectedId = p.id
                                    keyDraft = ""
                                    modelDraft =
                                        if (p.defaultModel == vm.settings.modelOverride) vm.settings.modelOverride else ""
                                    dropdownOpen = false
                                }
                            )
                        }
                    }
                }

                Text(
                    selected.note + " · get a key: " + selected.keyUrl.substringAfter("//"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selected.keyUrl))) } }
                )
                Text(
                    if (savedKey.isNotEmpty()) "A key is saved for ${selected.displayName}."
                    else "No key saved for ${selected.displayName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (savedKey.isEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )

                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    singleLine = true,
                    label = { Text("API key for ${selected.displayName}") },
                    placeholder = { Text("paste key…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        if (keyDraft.isNotBlank()) {
                            vm.settings.setApiKey(selected.id, keyDraft)
                            keyDraft = ""
                            vm.refreshStatus()
                        }
                    }) { Text("Save key") }
                    if (savedKey.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { vm.settings.clearApiKey(selected.id); vm.refreshStatus() },
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text("Remove") }
                    }
                }

                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = { modelDraft = it },
                    singleLine = true,
                    label = { Text("Model override (blank = ${selected.defaultModel})") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
                Button(
                    onClick = {
                        vm.settings.modelOverride = modelDraft
                        vm.settings.activeProviderId = selected.id
                        vm.refreshStatus()
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Use ${selected.displayName}") }
            }
        }

        // ---------- Git ----------
        Card(modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Git / GitHub", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Needed for push & private repos. Use a Personal Access Token (repo scope): github.com/settings/tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                OutlinedTextField(
                    value = gitUser,
                    onValueChange = { gitUser = it },
                    singleLine = true,
                    label = { Text("GitHub username") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gitToken,
                    onValueChange = { gitToken = it },
                    singleLine = true,
                    label = { Text("Personal access token") },
                    placeholder = { Text("ghp_… or github_pat_…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        vm.settings.gitUser = gitUser
                        vm.settings.gitToken = gitToken
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Save Git credentials") }
            }
        }

        // ---------- Storage ----------
        Card(modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Storage access", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (storageGranted.value) "Full storage access granted."
                    else "Grant \"All files access\" so SemCode AI can manage your files.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    } else {
                        storageGranted.value = hasStoragePermission()
                    }
                }) {
                    Text(if (storageGranted.value) "Re-check" else "Grant access")
                }
            }
        }

        TextButton(onClick = {}, enabled = false) { Text("") }
        Text(
            "SemCode AI v1.1 — personal coding agent. The AI runs real shell commands and edits real files. Use with care.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 88.dp)
        )
    }
}

fun hasStoragePermission(): Boolean =
    Environment.isExternalStorageManager() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R
