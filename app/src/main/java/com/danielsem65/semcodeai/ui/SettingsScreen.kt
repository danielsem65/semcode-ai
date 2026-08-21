package com.danielsem65.semcodeai.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    val hasKey = vm.hasApiKey()
    val storageGranted = remember { mutableStateOf(hasStoragePermission()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Gemini API key", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (hasKey) "A key is saved." else "Get a free key at aistudio.google.com/apikey",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    placeholder = { Text("AIza…") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Button(onClick = {
                        if (key.isNotBlank()) vm.saveApiKey(key)
                    }) { Text("Save") }
                    if (hasKey) {
                        OutlinedButton(
                            onClick = { vm.saveApiKey("") },
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text("Remove saved key") }
                    }
                }
            }
        }

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

        Text(
            "SemCode AI v1.0 — personal build. The AI can create, read, write, copy, move and delete any file on this device. Use with care.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

fun hasStoragePermission(): Boolean =
    Environment.isExternalStorageManager() || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
