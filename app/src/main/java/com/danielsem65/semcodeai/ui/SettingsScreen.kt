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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.danielsem65.semcodeai.AppViewModel
import com.danielsem65.semcodeai.SemApp
import com.danielsem65.semcodeai.ai.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as SemApp

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Card(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("AI Providers", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Active: ${vm.statusLine.collectAsState().value}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                for (p in Providers.ALL) ProviderRow(vm, p)
            }
        }

        StorageCard(vm)

        GithubCard()

        Text(
            "SemCode AI v2.0 · files + shell + GitHub agent · keys are stored only on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )
    }
}

@Composable
private fun ProviderRow(vm: AppViewModel, p: com.danielsem65.semcodeai.ai.Provider) {
    val context = LocalContext.current
    val settings = (context.applicationContext as SemApp).settings
    val scope = rememberCoroutineScope()

    val active = settings.activeProviderId == p.id
    var expanded by remember { mutableStateOf(false) }
    var keyDraft by remember(p.id) { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.displayName + if (settings.hasKeyFor(p.id)) "  ✓" else "",
                        style = MaterialTheme.typography.titleSmall)
                    if (active) {
                        Text("ACTIVE", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(if (expanded) "▲" else "▼",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!expanded) return@Column

            Text(p.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Text("Get a key → ${p.keyUrl.removePrefix("https://")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 4.dp)
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl)))
                        }
                    })

            // ---- API key ----
            var testState by remember(p.id) { mutableStateOf("") }
            var testing by remember(p.id) { mutableStateOf(false) }

            if (!p.isLocal) {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    singleLine = true,
                    label = {
                        Text(if (settings.hasKeyFor(p.id)) "API key (saved — type to replace)" else "API key")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        if (keyDraft.isNotBlank()) {
                            settings.setApiKey(p.id, keyDraft)
                            keyDraft = ""
                            vm.refreshStatus()
                        }
                    }) { Text("Save key") }
                    OutlinedButton(onClick = {
                        testing = true; testState = ""
                        scope.launch {
                            testState = withContext(Dispatchers.IO) {
                                runCatching {
                                    val engine = Providers.create(p, settings.apiKey(p.id),
                                        settings.modelOverride.ifBlank { p.defaultModel })
                                    "✓ works — ${engine.listModels().size} models"
                                }.getOrElse { "✗ ${it.message?.take(140)}" }
                            }
                            testing = false
                        }
                    }, modifier = Modifier.padding(start = 8.dp)) { Text("Test") }
                    if (testing) CircularProgressIndicator(Modifier.padding(start = 10.dp), strokeWidth = 2.dp)
                }
                if (testState.isNotBlank()) {
                    Text(testState,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testState.startsWith("✓")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }

            // ---- model ----
            ModelField(vm, p)

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    settings.activeProviderId = p.id
                    vm.refreshStatus()
                }, enabled = !active) {
                    Text(if (active) "Active ✓" else "Use this provider")
                }
            }
        }
    }
}

@Composable
private fun ModelField(vm: AppViewModel, p: com.danielsem65.semcodeai.ai.Provider) {
    val context = LocalContext.current
    val settings = (context.applicationContext as SemApp).settings
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var models by remember(p.id) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember(p.id) { mutableStateOf(false) }
    var text by remember(p.id) {
        mutableStateOf(settings.modelOverride.ifBlank { p.defaultModel })
    }

    Column(Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text("Model") },
            trailingIcon = {
                TextButton(onClick = {
                    menuOpen = !menuOpen
                    if (menuOpen && models.isEmpty() && (p.isLocal || settings.hasKeyFor(p.id))) {
                        loading = true
                        scope.launch {
                            models = withContext(Dispatchers.IO) {
                                runCatching {
                                    Providers.create(p, settings.apiKey(p.id), text).listModels()
                                }.getOrElse { emptyList() }
                            }
                            loading = false
                        }
                    }
                }) { Text(if (loading) "…" else "list ▾") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = menuOpen && models.isNotEmpty(), onDismissRequest = { menuOpen = false }) {
            val filter = text.trim().lowercase()
            models.filter { filter.isBlank() || it.lowercase().contains(filter) }
                .take(60)
                .forEach { id ->
                    DropdownMenuItem(text = { Text(id, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            text = id
                            settings.modelOverride = ""
                            vm.refreshStatus()
                            menuOpen = false
                        })
                }
        }
        Text(
            "Blank = default (${p.defaultModel}). Picking from the list sets an override.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = {
                settings.modelOverride =
                    if (text == p.defaultModel) "" else text.trim()
                vm.refreshStatus()
            }) { Text("Apply model override") }
        }
    }
}

@Composable
private fun StorageCard(vm: AppViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as SemApp
    val granted = Environment.isExternalStorageManager()
    var useFull by remember { mutableStateOf(app.settings.fullStorage && granted) }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Full device storage")
                    Text(
                        when {
                            granted -> "/storage/emulated/0/semcode"
                            else -> "Needs \"All files access\". Until then a private workspace is used."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = useFull, onCheckedChange = { want ->
                    if (want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !granted) {
                        openAllFilesAccess(context)
                    } else {
                        useFull = want
                        app.settings.fullStorage = want
                        app.onWorkspaceChanged()
                        vm.refreshStatus()
                    }
                })
            }
        }
    }
}

@Composable
private fun GithubCard() {
    val context = LocalContext.current
    val app = context.applicationContext as SemApp
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(app.settings.githubToken) }
    var state by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("GitHub", style = MaterialTheme.typography.titleMedium)
            Text(
                "Personal access token with repo scope:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Text(
                "github.com/settings/tokens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens")))
                    }
                }
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                singleLine = true,
                label = { Text("Access token") },
                placeholder = { Text("github_pat_… or ghp_…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    app.settings.githubToken = token.trim()
                    busy = true; state = ""
                    scope.launch {
                        state = withContext(Dispatchers.IO) {
                            runCatching { com.danielsem65.semcodeai.github.GitHubSync.testToken(token.trim()) }
                                .getOrElse { "✗ ${it.message?.take(120)}" }
                        }
                        busy = false
                    }
                }) { Text("Save & test") }
                if (busy) CircularProgressIndicator(Modifier.padding(start = 10.dp), strokeWidth = 2.dp)
                if (state.isNotBlank()) Text(
                    state,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.startsWith("✗")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
    }
}

internal fun openAllFilesAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
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
}
