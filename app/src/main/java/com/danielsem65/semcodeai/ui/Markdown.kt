package com.danielsem65.semcodeai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielsem65.semcodeai.ui.theme.CodeBg

private data class Seg(val code: Boolean, val text: String)

/** Splits message text on ``` fences into normal / code segments. */
private fun splitSegments(raw: String): List<Seg> {
    if (!raw.contains("```")) return listOf(Seg(false, raw))
    val out = mutableListOf<Seg>()
    var rest = raw
    while (true) {
        val start = rest.indexOf("```")
        if (start < 0) {
            if (rest.isNotBlank()) out += Seg(false, rest)
            break
        }
        val before = rest.substring(0, start)
        if (before.isNotBlank()) out += Seg(false, before.trimEnd())
        val afterFence = rest.substring(start + 3)
        val firstNl = afterFence.indexOf('\n')
        val contentStart = if (firstNl >= 0) firstNl + 1 else 0
        val end = afterFence.indexOf("```", contentStart)
        if (end < 0) {
            out += Seg(true, afterFence.substring(contentStart))
            break
        }
        out += Seg(true, afterFence.substring(contentStart, end).trimEnd('\n'))
        rest = afterFence.substring(end + 3)
    }
    return out
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val segments = remember(text) { splitSegments(text) }
    Column(modifier) {
        segments.forEachIndexed { i, seg ->
            if (seg.code) CodeBlock(seg.text, keyAttr = i)
            else Text(
                seg.text,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun CodeBlock(code: String, keyAttr: Int) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = Color(CodeBg),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Color(0xFFC9D1D9),
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy code",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
