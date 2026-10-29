package de.millione.schulranzen.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens. Light is warm paper with ink, dark is a slate board with
 * chalk; both carry the same roles so every screen works in either.
 */
@Immutable
data class RanzenColors(
    val isDark: Boolean,
    /** Desk behind everything. */
    val desk: Color,
    /** Paper sheets, panels. */
    val paper: Color,
    /** Raised controls (keys, tabs). */
    val raised: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val line: Color,
    /** Soft 3D: the light edge (top left) and the shadow (bottom right). */
    val bevelLight: Color,
    val bevelShadow: Color,
    /** The one action colour: a warm signal orange like an old device's power key. */
    val signal: Color,
    val onSignal: Color,
)

val LightColors = RanzenColors(
    isDark = false,
    desk = Color(0xFFEDE8DE),
    paper = Color(0xFFF8F5EE),
    raised = Color(0xFFFBF9F4),
    ink = Color(0xFF1C1B19),
    inkMuted = Color(0xFF6E6A62),
    inkFaint = Color(0xFFA9A397),
    line = Color(0xFFDDD6C8),
    bevelLight = Color(0xFFFFFFFF),
    bevelShadow = Color(0x2E6B5A3C),
    signal = Color(0xFFD9542F),
    onSignal = Color(0xFFFFF8F0),
)

val DarkColors = RanzenColors(
    isDark = true,
    desk = Color(0xFF181715),
    paper = Color(0xFF22211E),
    raised = Color(0xFF2B2A26),
    ink = Color(0xFFEDE8DD),
    inkMuted = Color(0xFFA39D91),
    inkFaint = Color(0xFF6B665D),
    line = Color(0xFF3A3834),
    bevelLight = Color(0x14FFFFFF),
    bevelShadow = Color(0x80000000),
    signal = Color(0xFFE8673F),
    onSignal = Color(0xFF1C1208),
)

/** Muted retro subject colours. They mark edges, spines and tabs, never whole surfaces. */
object SubjectPalette {
    val colors: List<Color> = listOf(
        Color(0xFF2F6F73), // Petrol
        Color(0xFFB5523B), // Ziegel
        Color(0xFFC99A2E), // Senf
        Color(0xFF7C9A6D), // Salbei
        Color(0xFF5F7896), // Taube
        Color(0xFF7D5A7A), // Pflaume
        Color(0xFFB07A3B), // Ocker
        Color(0xFF5B5A57), // Graphit
    )
    fun of(index: Int): Color = colors[Math.floorMod(index, colors.size)]
}

/** Writing colours offered in the pen menus (ARGB ints for the ink engine). */
object InkPalette {
    val pen: List<Int> = listOf(
        0xFF1C1B19.toInt(), // Tinte
        0xFF2448A8.toInt(), // Königsblau
        0xFFC2362B.toInt(), // Korrekturrot
        0xFF2E7D4F.toInt(), // Tannengrün
        0xFF7D5A7A.toInt(), // Pflaume
        0xFFB07A3B.toInt(), // Ocker
    )
    val marker: List<Int> = listOf(
        0xFFF2D04B.toInt(), // Gelb
        0xFF9BD37A.toInt(), // Grün
        0xFFF29BB2.toInt(), // Rosa
        0xFF86C5F0.toInt(), // Blau
        0xFFF5A35C.toInt(), // Orange
    )
}

val LocalRanzenColors = staticCompositionLocalOf { LightColors }
