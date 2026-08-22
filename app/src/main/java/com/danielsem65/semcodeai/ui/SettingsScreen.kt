package com.danielsem65.semcodeai.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.danielsem65.semcodeai.AppViewModel
import com.danielsem65.semcodeai.SemApp
import com.danielsem65.semcodeai.ai.Providers
import com.danielsem65.semcodeai.core.LinuxEnv
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

        SafetyCard(vm, app.settings)

        DiagnosticsCard()

        LinuxCard(vm)

        GithubCard()

        Text(
            "SemCode AI v2.1 · files + shell + Linux + GitHub agent · keys are stored only on this device.",
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

    val active = vm.activeProviderId.collectAsState().value == p.id
    var expanded by rememberSaveable { mutableStateOf(false) }
    var keyDraft by rememberSaveable(p.id) { mutableStateOf("") }

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
                }) {
                    Text(if (active) "Active ✓ (tap to re-apply)" else "Use this provider")
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
                            settings.modelOverride = if (id == p.defaultModel) "" else id
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

    var token by rememberSaveable { mutableStateOf(app.settings.githubToken) }
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

@Composable
private fun DiagnosticsCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var report by rememberSaveable { mutableStateOf(
        com.danielsem65.semcodeai.core.CrashLog.latest(ctx) ?: ""
    ) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
            if (report.isBlank()) {
                Text(
                    "No crash reports. If the app ever crashes, the report will appear here to copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    report.take(1200),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 10,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Row {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(report))
                    }) { Text("Copy full report") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        com.danielsem65.semcodeai.core.CrashLog.clear(ctx)
                        report = ""
                    }) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun SafetyCard(vm: AppViewModel, settings: com.danielsem65.semcodeai.core.SettingsStore) {
    var ask by rememberSaveable { mutableStateOf(settings.askBeforeChanges) }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ask before changes", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Pause the agent for your approval before it edits files or runs commands — with a diff preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = ask, onCheckedChange = {
                ask = it
                settings.askBeforeChanges = it
            })
        }
    }
}

@Composable
private fun LinuxCard(vm: AppViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as SemApp
    val scope = rememberCoroutineScope()
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    var installed by remember { mutableStateOf(app.linuxEnv.installedLabel()) }
    var selected by remember { mutableStateOf(LinuxEnv.Distro.ALPINE) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Linux environment", style = MaterialTheme.typography.titleMedium)
            Text(
                if (installed.isNotBlank()) "Installed: $installed — real apt/apk, git, python3 in the terminal and for the AI."
                else "Optional. Adds a full Linux distro (via proot, no root): apt/apk packages, git, python3. The AI can use it too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (installed.isBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    for (d in LinuxEnv.Distro.values()) {
                        Button(
                            onClick = { selected = d },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected == d) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("${d.label} (${d.sizeHint})",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected == d) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp)) {
                    Button(onClick = {
                        busy = true; msg = "downloading…"; progress = 0
                        scope.launch {
                            try {
                                app.linuxEnv.install(selected) { pct -> main.post { progress = pct } }
                                installed = app.linuxEnv.installedLabel()
                                msg = "✓ $installed ready — open the Shell tab and tap Linux"
                            } catch (e: Exception) {
                                msg = "✗ ${e.message?.take(160)}"
                            }
                            busy = false
                        }
                    }, enabled = !busy) { Text("Install") }
                    if (busy) {
                        Text("$progress%",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 10.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedButton(onClick = {
                        busy = true; msg = ""
                        scope.launch {
                            runCatching {
                                app.invalidateLinuxSession()
                                app.linuxEnv.remove()
                            }
                            installed = ""
                            vm.refreshStatus()
                            busy = false
                        }
                    }, enabled = !busy) { Text("Remove") }
                    Text("Shell tab → Linux to switch into it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp))
                }
            }
            if (msg.isNotBlank()) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("✗")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
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
