package com.danielsem65.semcodeai.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

@Composable
fun DepProbe() {
    var t by remember { mutableStateOf("hello") }
    BasicTextField(
        value = t,
        onValueChange = { t = it },
        softWrap = true,
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            lineHeight = 21.sp
        ),
        cursorBrush = SolidColor(MaterialThemeColor()),
        modifier = Modifier
    )
}

@Composable
private fun MaterialThemeColor() = androidx.compose.material3.MaterialTheme.colorScheme.primary
