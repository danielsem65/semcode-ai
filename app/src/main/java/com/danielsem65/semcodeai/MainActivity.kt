package com.danielsem65.semcodeai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SemCodeTheme { App(vm) }
        }
    }
}

@Composable
private fun App(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()

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
