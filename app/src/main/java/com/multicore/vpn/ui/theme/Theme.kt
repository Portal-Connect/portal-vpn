package com.multicore.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.multicore.vpn.AccentColorOption
import com.multicore.vpn.ThemeMode

// Sci-Fi Portal Dark Palette (matches black & white / neon cyber launcher style)
val PortalVoid = Color(0xFF07090E)
val PortalSurface = Color(0xFF10141F)
val PortalSurfaceVariant = Color(0xFF192031)

val LightBg = Color(0xFFF1F5F9)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE2E8F0)

val PortalCyan = Color(0xFF00E5FF)
val PortalPurple = Color(0xFFA855F7)
val ConnectedGreen = Color(0xFF00F5A0)
val DisconnectedRed = Color(0xFFFF4B6E)

@Composable
fun MulticoreTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accentOption: AccentColorOption = AccentColorOption.CYAN,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val primaryColor = accentOption.color

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = primaryColor,
            onPrimary = Color.Black,
            secondary = ConnectedGreen,
            background = PortalVoid,
            onBackground = Color(0xFFE2E8F0),
            surface = PortalSurface,
            onSurface = Color.White,
            surfaceVariant = PortalSurfaceVariant
        )
    } else {
        lightColorScheme(
            primary = primaryColor,
            onPrimary = Color.White,
            secondary = ConnectedGreen,
            background = LightBg,
            onBackground = Color(0xFF07090E),
            surface = LightSurface,
            onSurface = Color(0xFF07090E),
            surfaceVariant = LightSurfaceVariant
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
