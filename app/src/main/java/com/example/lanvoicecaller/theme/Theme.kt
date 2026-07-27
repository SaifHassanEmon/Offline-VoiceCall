package com.example.lanvoicecaller.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = darkColorScheme(
    primary          = Violet100,
    onPrimary        = OnBg,
    primaryContainer = Violet20,
    secondary        = Violet80,
    onSecondary      = OnBg,
    background       = Background,
    onBackground     = OnBg,
    surface          = Surface,
    onSurface        = OnSurface,
    surfaceVariant   = SurfaceVar,
    outline          = Divider
)

@Composable
fun LANVoiceCallerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography  = Typography,
        content     = content
    )
}
