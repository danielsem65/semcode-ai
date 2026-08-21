package com.danielsem65.semcodeai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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

// ---------- inline markdown ----------

/**
 * One alternation per token kind; order matters so the strongest marker wins:
 * link > code > bold-italic > bold > strike > underline > italic.
 */
private val INLINE_RE = Regex(
    "\\[[^\\]\n]+\\]\\([^)\n]+\\)" +   // [label](url)
        "|```[^`\n]+```|`[^`\n]+`" +   // code spans (double-backtick form first)
        "|\\*\\*\\*.+?\\*\\*\\*" +     // ***bold italic***
        "|\\*\\*.+?\\*\\*" +           // **bold**
        "|__.+?__" +                   // __bold__
        "|~~.+?~~" +                   // ~~strike~~
        "|\\+\\+.+?\\+\\+" +           // ++underline++
        "|<u>.+?</u>" +                // <u>underline</u>
        "|\\*[^*\n]+?\\*" +            // *italic*
        "|_[^_\n]+?_"                  // _italic_
)

@Composable
private fun inlineMarkdown(text: String, fontSize: Int = 14): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = CodeBg
    return buildAnnotatedString {
        var i = 0
        for (m in INLINE_RE.findAll(text)) {
            if (m.range.first > i) append(text.substring(i, m.range.first))
            val t = m.value
            when {
                t.startsWith("[") -> {
                    val close = t.indexOf("](")
                    val label = t.substring(1, close)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(label)
                    }
                }
                t.startsWith("`") -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (fontSize - 1).sp,
                        background = codeBg,
                        color = Color(0xFFC9D1D9)
                    )
                ) { append(t.replace("```", "").replace("`", "")) }
                t.startsWith("***") -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                ) { append(t.drop(3).dropLast(3)) }
                t.startsWith("**") || t.startsWith("__") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(t.drop(2).dropLast(2))
                    }
                t.startsWith("~~") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(t.drop(2).dropLast(2))
                    }
                t.startsWith("<u>") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        append(t.drop(3).dropLast(4))
                    }
                t.startsWith("++") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        append(t.drop(2).dropLast(2))
                    }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(t.drop(1).dropLast(1))
                }
            }
            i = m.range.last + 1
        }
        if (i < text.length) append(text.substring(i))
    }
}

// ---------- block-level rendering ----------

private val HEADING_RE = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET_RE = Regex("^(\\s*)[-*•]\\s+(.*)$")
private val NUMBERED_RE = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val QUOTE_RE = Regex("^>\\s?(.*)$")
private val HR_RE = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val segments = remember(text) { splitSegments(text) }
    Column(modifier) {
        segments.forEachIndexed { i, seg ->
            if (seg.code) CodeBlock(seg.text, keyAttr = i)
            else MarkdownBlocks(seg.text.trim('\n'))
        }
    }
}

@Composable
private fun MarkdownBlocks(block: String) {
    Column {
        block.split('\n').forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))

                HR_RE.matches(line) -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                line.startsWith("#") && HEADING_RE.matches(line) -> {
                    val m = HEADING_RE.matchEntire(line)!!
                    val level = m.groupValues[1].length
                    val style = when (level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    }
                    Text(
                        inlineMarkdown(m.groupValues[2], style.fontSize.value.toInt()),
                        style = style,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }

                QUOTE_RE.matches(line) -> {
                    val content = QUOTE_RE.matchEntire(line)!!.groupValues[1]
                    Row {
                        Text(
                            "▏",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            inlineMarkdown(content),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                                lineHeight = 20.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                BULLET_RE.matches(line) -> {
                    val m = BULLET_RE.matchEntire(line)!!
                    val indent = (m.groupValues[1].length / 2).coerceAtMost(4)
                    Row {
                        Spacer(Modifier.width((10 + indent * 14).dp))
                        Text("•  ", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            inlineMarkdown(m.groupValues[2]),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                NUMBERED_RE.matches(line) -> {
                    val m = NUMBERED_RE.matchEntire(line)!!
                    val indent = (m.groupValues[1].length / 2).coerceAtMost(4)
                    Row {
                        Spacer(Modifier.width((10 + indent * 14).dp))
                        Text("${m.groupValues[2]}.  ",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            inlineMarkdown(m.groupValues[3]),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                else -> Text(
                    inlineMarkdown(line),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, keyAttr: Int) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = CodeBg,
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
