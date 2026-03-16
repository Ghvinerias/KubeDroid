package com.kubedroid.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kubedroid.feature.settings.domain.model.AppTheme

private val DarkColorScheme = darkColorScheme(
    background = Background,
    surface = SurfaceContainer,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceElevated,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    primary = AccentCyan,
    onPrimary = Background,
    secondary = AccentPurple,
    onSecondary = Background,
    tertiary = AccentGreen,
    outline = SurfaceBorder,
    outlineVariant = SurfaceBorder,
    error = AccentRed,
)

private val LightColorScheme = lightColorScheme(
    background = Color(0xFFFAFBFC),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFE9EDF1),
    onBackground = Color(0xFF1A1C1E),
    onSurface = Color(0xFF1A1C1E),
    primary = Color(0xFF0097A7),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF38656F),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF3A6655),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    error = Color(0xFFBA1A1A),
)

private val OledColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color(0xFF0A0A0A),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KubeDroidTheme(
    theme: AppTheme = AppTheme.System,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (theme) {
        AppTheme.System -> if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
        AppTheme.Light -> LightColorScheme
        AppTheme.Dark -> DarkColorScheme
        AppTheme.OledBlack -> OledColorScheme
    }

    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(
            color = AccentCyan.copy(alpha = 0.12f),
        ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
