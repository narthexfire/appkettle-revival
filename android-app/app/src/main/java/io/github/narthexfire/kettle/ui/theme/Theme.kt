package io.github.narthexfire.kettle.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.narthexfire.kettle.R

/* ---------- colors (spec section 1) ---------- */

@Immutable
data class Zone(
    val fg: Color, val bg: Color, val line: Color,
    val soft: Color = bg, val fill: Color = fg, val onFill: Color = Color.White,
)

@Immutable
data class KettleColors(
    val dark: Boolean,
    val surface: Color, val card: Color, val card2: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val line: Color,
    val accent: Color, val onAccent: Color, val accentText: Color, val accentSoft: Color,
    val ok: Color, val off: Color, val warn: Color,
    val tile: Color,
    val status: Zone, val temp: Zone, val preset: Zone, val warm: Zone,
    val tempA: Color, val tempB: Color,
    // kettle illustration
    val kBody1: Color, val kBody2: Color, val kEdge: Color, val kDark: Color, val kDark2: Color,
    val kHi: Color, val kShadow: Color, val waterLine: Color, val waterEdge: Color, val waterLabel: Color,
)

val LightKettle = KettleColors(
    dark = false,
    surface = Color(0xFFFAF8F5), card = Color(0xFFFFFFFF), card2 = Color(0xFFF3EFE9),
    ink = Color(0xFF1D1B18), ink2 = Color(0xFF5C554C), ink3 = Color(0xFF8C847A), line = Color(0xFFE7E1D8),
    accent = Color(0xFFB3302A), onAccent = Color.White, accentText = Color(0xFFA3271F), accentSoft = Color(0xFFF7E5E1),
    ok = Color(0xFF2F7D4F), off = Color(0xFFA79F95), warn = Color(0xFF9A5B00), // >= 4.8:1 on white and the status tint
    tile = Color.White.copy(alpha = 0.78f),
    status = Zone(Color(0xFF3A5A7A), Color(0xFFEEF2F7), Color(0xFFD3DDE8)),
    temp = Zone(Color(0xFF1D6B66), Color(0xFFECF5F3), Color(0xFFCBE2DE)),
    preset = Zone(Color(0xFF885800), Color(0xFFFBF4E4), Color(0xFFEEDDB8), soft = Color(0xFFF5E2B5)),
    warm = Zone(Color(0xFF96461A), Color(0xFFFBF0E8), Color(0xFFF0D6C3), fill = Color(0xFF96461A), onFill = Color.White),
    tempA = Color(0xFF2A8C86), tempB = Color(0xFFB3302A),
    kBody1 = Color(0xFFF1ECE4), kBody2 = Color(0xFFD8D0C4), kEdge = Color(0xFFC4BAAC), kDark = Color(0xFF2C2926),
    kDark2 = Color(0xFF46413B), kHi = Color.White.copy(alpha = 0.75f), kShadow = Color(0x29281E14),
    waterLine = Color.White, waterEdge = Color(0xFF3F6688), waterLabel = Color(0xFF3A5A7A),
)

val DarkKettle = KettleColors(
    dark = true,
    surface = Color(0xFF141210), card = Color(0xFF1E1B18), card2 = Color(0xFF292521),
    ink = Color(0xFFEDE7E1), ink2 = Color(0xFFB5ACA1), ink3 = Color(0xFF7F776D), line = Color(0xFF322D28),
    accent = Color(0xFFC83A2F), onAccent = Color.White, accentText = Color(0xFFFF8C7C), accentSoft = Color(0xFF3B201C),
    ok = Color(0xFF62C98F), off = Color(0xFF6E675F), warn = Color(0xFFF0A73A),
    tile = Color.White.copy(alpha = 0.05f),
    status = Zone(Color(0xFFA3BEDB), Color(0xFF18202A), Color(0xFF26313F)),
    temp = Zone(Color(0xFF7FCFC7), Color(0xFF142321), Color(0xFF213A36)),
    preset = Zone(Color(0xFFE8BC6A), Color(0xFF221C12), Color(0xFF3A3020), soft = Color(0xFF3A2E17)),
    warm = Zone(Color(0xFFF2A576), Color(0xFF251912), Color(0xFF3D2A1F), fill = Color(0xFFE8925E), onFill = Color(0xFF1A0E08)),
    tempA = Color(0xFF3BA39B), tempB = Color(0xFFD2453A),
    kBody1 = Color(0xFF4A4540), kBody2 = Color(0xFF2A2724), kEdge = Color(0xFF5A544D), kDark = Color(0xFF3B3733),
    kDark2 = Color(0xFF5A534C), kHi = Color.White.copy(alpha = 0.22f), kShadow = Color.Black.copy(alpha = 0.5f),
    waterLine = Color(0xFFE4F0FA), waterEdge = Color(0xFF0C1620), waterLabel = Color(0xFFA3BEDB),
)

val LocalKettleColors = staticCompositionLocalOf { LightKettle }

/* ---------- type (spec section 2) ---------- */

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun manrope(w: Int) = Font(
    R.font.manrope, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

val Manrope = FontFamily(manrope(300), manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

@Immutable
data class KettleType(
    val display: TextStyle, val displayUnit: TextStyle, val appTitle: TextStyle, val state: TextStyle,
    val sheetTitle: TextStyle, val button: TextStyle, val title: TextStyle, val chip: TextStyle,
    val presetName: TextStyle, val secondary: TextStyle, val overline: TextStyle, val value: TextStyle,
    val mono: TextStyle,
)

private fun s(size: Int, line: Int, w: Int, track: Double = 0.0, tnum: Boolean = false) = TextStyle(
    fontFamily = Manrope, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(w),
    // Tracking is expressed in sp, never em: Material 3's own styles use sp, and an OutlinedTextField
    // label animates (lerps) between our style and M3's. Mixing em and sp threw
    // "Cannot perform operation for Em and Sp" and crashed the preset edit dialog.
    letterSpacing = (size * track).sp, fontFeatureSettings = if (tnum) "tnum" else null,
)

val KType = KettleType(
    // Sizes are in whole sp, with a 12 sp floor.
    display = s(62, 62, 300, -0.05, tnum = true),
    displayUnit = s(26, 28, 400),
    appTitle = s(20, 24, 800, -0.025),
    state = s(20, 24, 700, -0.02, tnum = true),
    sheetTitle = s(19, 23, 800, -0.025),
    button = s(15, 18, 700, tnum = true),
    title = s(15, 20, 700, tnum = true),
    chip = s(15, 18, 700, tnum = true),
    presetName = s(15, 18, 700),
    secondary = s(14, 18, 500, tnum = true),
    overline = s(14, 17, 700, 0.06),
    value = s(20, 22, 700, tnum = true),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp),
)

/**
 * Half-size type for the Kettle details (Diagnostics) screen: 50 % of the normal sizes with a
 * 14 sp floor, headings kept larger than values, monospace raw frames at 13 sp.
 */
val KTypeCompact = KType.copy(
    appTitle = s(18, 22, 800, -0.02),
    overline = s(16, 20, 700, 0.06),
    title = s(14, 19, 700, tnum = true),
    secondary = s(14, 19, 500, tnum = true),
    button = s(14, 18, 700),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp),
)

/**
 * Explicit type for the screens under the overflow menu, built around a 16 sp body
 * (body text 16 sp). Hierarchy: title 22 > values / buttons 18 > headings 17 > body 16 > mono 14.
 * Exact values are tabled in docs/Kettle App Design Spec.md.
 */
val KTypeOverflow = KType.copy(
    appTitle = s(22, 26, 800, -0.02),
    title = s(18, 24, 700, tnum = true),
    button = s(18, 22, 700, tnum = true),
    presetName = s(18, 22, 700),
    chip = s(18, 22, 700, tnum = true),
    value = s(22, 26, 700, tnum = true),
    overline = s(17, 21, 700, 0.06),
    secondary = s(16, 22, 500, tnum = true),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 18.sp),
)

/** Kettle details (Diagnostics): the same 16 sp body, values a step smaller for its long lists. */
val KTypeDetails = KTypeCompact.copy(
    appTitle = s(22, 26, 800, -0.02),
    title = s(17, 22, 700, tnum = true),
    button = s(17, 21, 700),
    overline = s(17, 21, 700, 0.06),
    secondary = s(16, 22, 500, tnum = true),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 18.sp),
)

val LocalKettleType = staticCompositionLocalOf { KType }

object K {
    val c: KettleColors @Composable get() = LocalKettleColors.current
    val t: KettleType @Composable get() = LocalKettleType.current
}

@Composable
private fun animated(c: KettleColors): KettleColors {
    // 300 ms crossfade on theme switch for the main surfaces.
    val surface by animateColorAsState(c.surface, tween(300), label = "surface")
    val card by animateColorAsState(c.card, tween(300), label = "card")
    val ink by animateColorAsState(c.ink, tween(300), label = "ink")
    return c.copy(surface = surface, card = card, ink = ink)
}

@Composable
fun KettleTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val kc = animated(if (dark) DarkKettle else LightKettle)
    val scheme = if (dark) darkColorScheme(
        primary = kc.accent, onPrimary = kc.onAccent, primaryContainer = kc.accentSoft, onPrimaryContainer = kc.ink,
        background = kc.surface, onBackground = kc.ink, surface = kc.surface, onSurface = kc.ink,
        surfaceContainerLow = kc.card, surfaceContainer = kc.card, surfaceContainerHigh = kc.card2,
        surfaceContainerHighest = kc.card2, onSurfaceVariant = kc.ink2, outline = kc.ink3, outlineVariant = kc.line,
        error = kc.accentText, scrim = Color.Black,
    ) else lightColorScheme(
        primary = kc.accent, onPrimary = kc.onAccent, primaryContainer = kc.accentSoft, onPrimaryContainer = kc.ink,
        background = kc.surface, onBackground = kc.ink, surface = kc.surface, onSurface = kc.ink,
        surfaceContainerLow = kc.card, surfaceContainer = kc.card, surfaceContainerHigh = kc.card2,
        surfaceContainerHighest = kc.card2, onSurfaceVariant = kc.ink2, outline = kc.ink3, outlineVariant = kc.line,
        error = kc.accentText, scrim = Color(0xFF181410),
    )
    val typography = Typography(
        bodyLarge = KType.secondary.copy(color = Color.Unspecified),
        bodyMedium = KType.secondary,
        bodySmall = KType.secondary,
        labelMedium = KType.secondary,
        labelSmall = KType.secondary,
        titleMedium = KType.title,
        labelLarge = KType.button,
        headlineSmall = KType.sheetTitle,
        headlineMedium = KType.appTitle,
    )
    CompositionLocalProvider(LocalKettleColors provides kc, LocalKettleType provides KType) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/* ---------- color helpers ---------- */

fun lerpColor(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f, green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f, alpha = a.alpha + (b.alpha - a.alpha) * f,
    )
}

/** Marker ring / selected chip fill: lerp(#1F6F6B, #B3302A, (t - 40) / (boil - 40)). */
fun tempColor(t: Int, boilPoint: Int): Color =
    lerpColor(Color(0xFF1F6F6B), Color(0xFFB3302A), (minOf(t, boilPoint) - 40f) / (boilPoint - 40f))

/** Water color : blue when cold, through purple, to bright red at the measured boil point. See [WaterRamp]. */
fun rampColor(t: Float, boilPoint: Int): Color = Color(WaterRamp.argb(t, boilPoint))
