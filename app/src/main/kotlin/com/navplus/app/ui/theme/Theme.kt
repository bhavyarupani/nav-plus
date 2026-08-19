package com.navplus.app.ui.theme

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

private val NavDark = Color(0xFF0F172A)
private val NavPrimary = Color(0xFF3B82F6)
private val NavPrimaryContainer = Color(0xFF1E3A5F)
private val NavOnPrimary = Color.White
private val NavSurface = Color(0xFF1C1C2E)
private val NavOnSurface = Color(0xFFE2E8F0)

private val DarkColors = darkColorScheme(
    primary = NavPrimary,
    onPrimary = NavOnPrimary,
    primaryContainer = NavPrimaryContainer,
    onPrimaryContainer = Color(0xFFBFDBFE),
    background = NavDark,
    onBackground = NavOnSurface,
    surface = NavSurface,
    onSurface = NavOnSurface,
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceVariant = Color(0xFF1E293B),
)

private val LightColors = lightColorScheme(
    primary = NavPrimary,
    onPrimary = NavOnPrimary,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A5F),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
)

@Composable
fun NavPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else      -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
