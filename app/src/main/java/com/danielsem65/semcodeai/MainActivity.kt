package com.danielsem65.semcodeai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danielsem65.semcodeai.ui.ChatScreen
import com.danielsem65.semcodeai.ui.FilesScreen
import com.danielsem65.semcodeai.ui.SettingsScreen
import com.danielsem65.semcodeai.ui.TerminalPanel
import com.danielsem65.semcodeai.ui.theme.SemCodeTheme

class MainActivity : ComponentActivity() {

    private val vm by viewModels<AppViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SemCodeTheme {
                App(vm)
            }
        }
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val termOpen by vm.termOpen.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                    label = { Text("AI") })
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    label = { Text("Files") })
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") })
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.toggleTerm() }) {
                Icon(
                    if (termOpen) Icons.Filled.Close else Icons.Filled.Terminal,
                    contentDescription = "Toggle terminal"
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Column(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ChatScreen(vm)
                    1 -> FilesScreen()
                    else -> SettingsScreen(vm)
                }
            }
            AnimatedVisibility(
                visible = termOpen,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                TerminalPanel(vm, Modifier.fillMaxWidth().height(340.dp))
            }
        }
    }
}
