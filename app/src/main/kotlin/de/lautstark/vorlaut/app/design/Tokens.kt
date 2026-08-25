package de.lautstark.vorlaut.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Lautstark tokens, as Compose values.
 *
 * These are a port of `tokens/vorlaut.css` in Lautstark/design, which is
 * generated from one input — vorlaut's accent `#9B7BFF` — and regenerated
 * whenever the design repository moves. **The hexes below are copies and the
 * generator is the source.** Editing one here to taste silently drops the
 * contrast guarantee it was solved for, exactly as the header of the CSS file
 * warns; a value that needs to change changes there and is copied back.
 *
 * The names are the family's and are normative across all three products
 * (design.md §4.2). Only the values are per product.
 */
@Immutable
data class VorlautColors(
    /** The page itself. The furthest-back plane. */
    val bg: Color,
    /** A raised plane sitting on [bg]: a card, a sheet, a popup. */
    val surface: Color,
    /** A plane sitting on [surface]: a quiet button's fill, a field's fill. */
    val surface2: Color,
    /** [surface2] under the pointer. Never used at rest. */
    val surface3: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentInk: Color,
    val accentStrong: Color,
    val accentHover: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerInk: Color,
    val dangerSoft: Color,
    val isDark: Boolean,
)

private val Light =
    VorlautColors(
        bg = Color(0xFFF9F9FB),
        surface = Color(0xFFFFFFFF),
        surface2 = Color(0xFFF0F0F4),
        surface3 = Color(0xFFE6E5EC),
        line = Color(0xFFDADAE0),
        text = Color(0xFF1A1A1D),
        textDim = Color(0xFF63616B),
        textFaint = Color(0xFF6C6B74),
        accent = Color(0xFF9B7BFF),
        accentInk = Color(0xFF130B2A),
        accentStrong = Color(0xFF7A57D7),
        accentHover = Color(0xFF8E6DEF),
        accentSoft = Color(0xFFF3F1FF),
        danger = Color(0xFFAD332C),
        dangerInk = Color(0xFFFFF6F4),
        dangerSoft = Color(0xFFFFEBE8),
        isDark = false,
    )

private val Dark =
    VorlautColors(
        bg = Color(0xFF161618),
        surface = Color(0xFF1F1F22),
        surface2 = Color(0xFF28282B),
        surface3 = Color(0xFF323236),
        line = Color(0xFF3A3A3E),
        text = Color(0xFFEBEBF0),
        textDim = Color(0xFF9C9BA5),
        textFaint = Color(0xFF8F8E98),
        accent = Color(0xFF9B7BFF),
        accentInk = Color(0xFF130B2A),
        // On a dark ground --accent-strong equals --accent, which design.md §4.2
        // explicitly permits rather than inventing a second shade.
        accentStrong = Color(0xFF9B7BFF),
        accentHover = Color(0xFFAB96FF),
        accentSoft = Color(0xFF211D30),
        danger = Color(0xFFF17265),
        dangerInk = Color(0xFF330F0C),
        dangerSoft = Color(0xFF341B18),
        isDark = true,
    )

/**
 * Shape and spacing.
 *
 * [gap] is the one number that governs every gutter on the board: the outer
 * edges, between any two cells, and between the sentence bar and the grid. It
 * is a single value on purpose — the build this replaces set a padding on the
 * grid *and* a padding inside each cell, so the outer columns carried both and
 * sat further in than the inner ones.
 */
@Immutable
data class VorlautMetrics(
    val radius: Dp = 14.dp,
    val radiusSm: Dp = 9.dp,
    val radiusItem: Dp = 7.dp,
    val gap: Dp = 10.dp,
)

/**
 * The type scale. System font only — no webfont and no CDN, which is a family
 * value (design.md §1.2) and on Android means simply not shipping one.
 */
@Immutable
data class VorlautType(
    val title: TextStyle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    val rowName: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    val body: TextStyle = TextStyle(fontSize = 15.sp),
    val sub: TextStyle = TextStyle(fontSize = 13.5.sp),
    val small: TextStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    val caption: TextStyle = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.03.em),
    val mono: TextStyle = TextStyle(fontSize = 12.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
)

private val LocalColors = staticCompositionLocalOf { Light }
private val LocalMetrics = staticCompositionLocalOf { VorlautMetrics() }
private val LocalType = staticCompositionLocalOf { VorlautType() }

object Vorlaut {
    val colors: VorlautColors
        @Composable @ReadOnlyComposable
        get() = LocalColors.current
    val metrics: VorlautMetrics
        @Composable @ReadOnlyComposable
        get() = LocalMetrics.current
    val type: VorlautType
        @Composable @ReadOnlyComposable
        get() = LocalType.current
}

/**
 * The theme. Deliberately not `MaterialTheme`: Material's own colour roles and
 * type ramp are a second design system, and a screen drawn half in each is how
 * a product stops looking like its family.
 */
@Composable
fun VorlautTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalColors provides if (dark) Dark else Light,
        LocalMetrics provides VorlautMetrics(),
        LocalType provides VorlautType(),
        content = content,
    )
}

private val Int.em get() =
    androidx.compose.ui.unit
        .TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
private val Double.em get() =
    androidx.compose.ui.unit
        .TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
