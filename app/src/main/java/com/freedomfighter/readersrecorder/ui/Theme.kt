package com.freedomfighter.readersrecorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.freedomfighter.readersrecorder.data.Align
import com.freedomfighter.readersrecorder.data.FontChoice
import com.freedomfighter.readersrecorder.data.Settings
import com.freedomfighter.readersrecorder.data.TextSize
import com.freedomfighter.readersrecorder.data.Palettes

/**
 * The colours of a theme: a background, a text colour, and the accent that fills the inverted
 * rows (the text colour itself in the two inks). `dim` is the text at reduced opacity for
 * secondary lines and rules.
 */
data class ReaderColors(val bg: Color, val fg: Color, val accent: Color = fg, val onAccent: Color = bg) {
    val dim: Color get() = fg.copy(alpha = 0.55f)
    val rule: Color get() = fg.copy(alpha = 0.25f)
    val isDark: Boolean get() = bg.luminance() < 0.5f
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

data class ReaderTypography(
    val family: FontFamily,
    val weight: FontWeight,
    val tile: TextUnit,
    val alignLeft: Boolean
) {
    val small: TextUnit get() = tile * 0.62f
    val title: TextUnit get() = tile * 0.8f
    val big: TextUnit get() = tile * 2.2f
    val textAlign: TextAlign get() = if (alignLeft) TextAlign.Start else TextAlign.Center
}

val LocalColors = compositionLocalOf { ReaderColors(Color.Black, Color.White) }
val LocalTypo = compositionLocalOf { ReaderTypography(FontFamily.SansSerif, FontWeight.Light, 28.sp, true) }
val LocalHaptics = compositionLocalOf { true }

@Composable
fun ReaderTheme(settings: Settings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val p = Palettes.of(settings.theme, systemDark)
    val colors = ReaderColors(Color(p.bg), Color(p.fg), Color(p.accent), Color(p.onAccent))
    val family = when (settings.font) {
        FontChoice.SERIF -> FontFamily.Serif
        FontChoice.SANS -> FontFamily.SansSerif
        FontChoice.MONO -> FontFamily.Monospace
    }
    // Roboto Light for sans (the Light Phone look); serif and mono only ship in regular weight.
    val weight = if (settings.font == FontChoice.SANS) FontWeight.Light else FontWeight.Normal
    val size = when (settings.textSize) {
        TextSize.SMALL -> 24.sp
        TextSize.MEDIUM -> 28.sp
        TextSize.LARGE -> 32.sp
    }
    val typo = ReaderTypography(family, weight, size, settings.align == Align.LEFT)
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalTypo provides typo,
        LocalHaptics provides settings.haptics,
        content = content
    )
}

@Composable
fun tileTextStyle(): TextStyle {
    val t = LocalTypo.current
    return TextStyle(
        color = LocalColors.current.fg,
        fontFamily = t.family,
        fontWeight = t.weight,
        fontSize = t.tile,
        lineHeight = t.tile * 1.25f,
        textAlign = t.textAlign
    )
}
