package com.danielsem65.semcodeai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Accent = Color(0xFF00E5A0)
val DarkBg = Color(0xFF0D1117)
val DarkSurface = Color(0xFF161B22)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Color(0xFF58A6FF),
    background = DarkBg,
    onBackground = Color(0xFFE6EDF3),
    surface = DarkSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFFF7B72)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00A97F),
    onPrimary = Color.White
)

@Composable
fun SemCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
