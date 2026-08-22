package com.danielsem65.semcodeai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.danielsem65.semcodeai.ui.ChatScreen
import com.danielsem65.semcodeai.ui.FilesScreen
import com.danielsem65.semcodeai.ui.ProjectsPanel
import com.danielsem65.semcodeai.ui.SettingsScreen
import com.danielsem65.semcodeai.ui.TerminalScreen
import com.danielsem65.semcodeai.ui.theme.SemCodeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm by viewModels<AppViewModel>()

    private val notifPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    private val storagePermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // Legacy storage mode (targetSdk 28): classic runtime permissions
        // unlock full shared-storage access.
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
        setContent {
            SemCodeTheme { App(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        com.danielsem65.semcodeai.core.AppForeground.foreground = true
    }

    override fun onStop() {
        super.onStop()
        com.danielsem65.semcodeai.core.AppForeground.foreground = false
    }
}

@Composable
private fun CrashDialog(report: String, onDismiss: () -> Unit, onClear: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The app crashed last time") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(report))
                onDismiss()
            }) { Text("Copy & close") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onClear) { Text("Clear") }
        }
    )
}

@Composable
private fun App(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()

    // Show last crash report once per cold start, if there is one
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var crashReport by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            com.danielsem65.semcodeai.core.CrashLog.latest(ctx)
        )
    }
    if (crashReport != null) {
        CrashDialog(
            report = crashReport!!,
            onDismiss = { crashReport = null },
            onClear = {
                com.danielsem65.semcodeai.core.CrashLog.clear(ctx)
                crashReport = null
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ProjectsPanel(
                vm,
                onNewChat = { tab = 0 },
                onOpenProject = { tab = 0 },
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                        label = { Text("AI") })
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        label = { Text("Shell") })
                    NavigationBarItem(
                        selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        label = { Text("Files") })
                    NavigationBarItem(
                        selected = tab == 3, onClick = { tab = 3 },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") })
                }
            }
        ) { padding ->
            val m = Modifier.padding(padding)
            when (tab) {
                0 -> holder.SaveableStateProvider("chat") {
                    Box(m) {
                        ChatScreen(
                            vm,
                            onOpenSettings = { tab = 3 },
                            onOpenDrawer = { scope.launch { drawerState.open() } }
                        )
                    }
                }
                1 -> holder.SaveableStateProvider("shell") {
                    Box(m) { TerminalScreen(vm) }
                }
                2 -> holder.SaveableStateProvider("files") {
                    Box(m) { FilesScreen() }
                }
                else -> holder.SaveableStateProvider("settings") {
                    Box(m) { SettingsScreen(vm) }
                }
            }
        }
    }
}
