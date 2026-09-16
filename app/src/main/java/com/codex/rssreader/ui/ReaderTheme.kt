package com.codex.rssreader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ReaderOrange = Color(0xFFFF650D)
val ReaderBackground = Color(0xFFF8F8FD)
val ReaderText = Color(0xFF15171B)
val ReaderMuted = Color(0xFF858A91)

private val colors = lightColorScheme(
    primary = ReaderOrange,
    onPrimary = Color.White,
    background = ReaderBackground,
    onBackground = ReaderText,
    surface = Color.White,
    onSurface = ReaderText,
    surfaceVariant = Color(0xFFF1F1F5),
)

@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
