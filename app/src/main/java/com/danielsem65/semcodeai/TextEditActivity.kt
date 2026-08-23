package com.danielsem65.semcodeai

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.danielsem65.semcodeai.ui.theme.SemCodeAITheme
import java.io.File

/** Full-screen text file editor launched from the Files tab. */
class TextEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: return finish()
        setContent {
            SemCodeAITheme {
                com.danielsem65.semcodeai.ui.EditorScreen(path = path) { changed ->
                    setResult(if (changed) RESULT_OK else RESULT_CANCELED)
                    finish()
                }
            }
        }
    }
}

/** Full-screen local HTML preview launched from the Files tab. */
class HtmlPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: return finish()
        setContent {
            SemCodeAITheme {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { finish() }) {
                            Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(18.dp))
                            Text("Close", Modifier.padding(start = 4.dp))
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Preview", style = MaterialTheme.typography.titleSmall)
                            Text(
                                File(path).name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        val ctx = LocalContext.current
                        TextButton(onClick = {
                            ctx.startActivity(
                                android.content.Intent(ctx, TextEditActivity::class.java)
                                    .putExtra("path", path)
                            )
                        }) { Text("Edit") }
                    }
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = true
                                settings.allowFileAccessFromFileURLs = true
                                settings.allowUniversalAccessFromFileURLs = true
                                settings.loadWithOverviewMode = true
                                webViewClient = WebViewClient()
                            }
                        },
                        update = { wv -> wv.loadUrl("file://$path") },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
