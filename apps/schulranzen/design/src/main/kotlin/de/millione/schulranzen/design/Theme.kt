package de.millione.schulranzen.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** How the app looks: follow the system, or always paper / always slate. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun RanzenTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    // Material only as a base for text fields and selection handles.
    val scheme = if (dark) darkColorScheme(
        primary = colors.signal, onPrimary = colors.onSignal, background = colors.desk,
        surface = colors.paper, onSurface = colors.ink, onBackground = colors.ink, outline = colors.line,
    ) else lightColorScheme(
        primary = colors.signal, onPrimary = colors.onSignal, background = colors.desk,
        surface = colors.paper, onSurface = colors.ink, onBackground = colors.ink, outline = colors.line,
    )
    CompositionLocalProvider(LocalRanzenColors provides colors, LocalRanzenType provides RanzenType()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object Ranzen {
    val colors: RanzenColors
        @Composable @ReadOnlyComposable get() = LocalRanzenColors.current
    val type: RanzenType
        @Composable @ReadOnlyComposable get() = LocalRanzenType.current
}
