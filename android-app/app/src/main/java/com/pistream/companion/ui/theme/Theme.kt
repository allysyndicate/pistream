package com.pistream.companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PiColors = lightColorScheme(
    primary = Color(0xFF28635B),
    secondary = Color(0xFF705C2E),
    tertiary = Color(0xFF7B5260),
    surface = Color(0xFFFBFCFA),
    surfaceVariant = Color(0xFFE4ECE8)
)

@Composable
fun PiStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PiColors,
        content = content
    )
}
