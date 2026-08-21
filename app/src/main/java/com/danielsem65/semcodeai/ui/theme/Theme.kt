package com.danielsem65.semcodeai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF00E5A0)
val AccentDim = Color(0xFF0E6B52)
val Bg = Color(0xFF0B0F14)
val Surface1 = Color(0xFF11161D)
val Surface2 = Color(0xFF171E27)
val Surface3 = Color(0xFF1F2833)
val TextMain = Color(0xFFE6EDF3)
val TextDim = Color(0xFF8B98A5)
val ErrorRed = Color(0xFFFF7B72)
val CodeBg = Color(0xFF0D1117)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04120C),
    primaryContainer = AccentDim,
    secondary = Color(0xFF58A6FF),
    background = Bg,
    onBackground = TextMain,
    surface = Surface1,
    onSurface = TextMain,
    surfaceVariant = Surface3,
    onSurfaceVariant = TextDim,
    surfaceContainerLowest = Bg,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    error = ErrorRed,
    outline = Color(0xFF2D3742)
)

@Composable
fun SemCodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
