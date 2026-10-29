package de.millione.schulranzen.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int) = Font(
    R.font.bricolage, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(88f)),
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Titles: a slightly condensed grotesk with a retro feel. */
val DisplayFamily = FontFamily(bricolage(400), bricolage(600), bricolage(800))
/** Running text: calm and very legible. */
val TextFamily = FontFamily(inter(400), inter(500), inter(600))
/** Numbers, page counts, the calculator: monospace like an old display. */
val MonoFamily = FontFamily(
    Font(R.font.plexmono_regular, FontWeight.Normal),
    Font(R.font.plexmono_medium, FontWeight.Medium),
)

@Immutable
data class RanzenType(
    val hero: TextStyle = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(800), fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.8).sp),
    val title: TextStyle = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(600), fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    val heading: TextStyle = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(600), fontSize = 19.sp, lineHeight = 23.sp),
    val body: TextStyle = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 22.sp),
    val label: TextStyle = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(500), fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    val caption: TextStyle = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    val mono: TextStyle = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp),
    val monoLarge: TextStyle = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 34.sp),
)

val LocalRanzenType = staticCompositionLocalOf { RanzenType() }
