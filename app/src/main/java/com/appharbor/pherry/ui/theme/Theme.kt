package com.appharbor.pherry.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Material roles carry the print world: primary is ink (filled buttons print in ink), the primary
// container is envelope yellow (the job), surfaces are paper and sheet.
private val LightColorScheme = lightColorScheme(
    primary = InkLight,
    onPrimary = PaperLight,
    primaryContainer = EnvelopeYellow,
    onPrimaryContainer = OnEnvelope,
    inversePrimary = EnvelopeYellow,
    secondary = Ink2Light,
    onSecondary = SheetLight,
    secondaryContainer = Well2Light,
    onSecondaryContainer = InkLight,
    tertiary = GreenLight,
    onTertiary = SheetLight,
    tertiaryContainer = Color(0xFFDDEFE3),
    onTertiaryContainer = Color(0xFF0B3D20),
    error = RedLight,
    onError = SheetLight,
    errorContainer = RedWashLight,
    onErrorContainer = OnRedWashLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = WellLight,
    onSurfaceVariant = Ink2Light,
    surfaceTint = Color.Transparent,
    surfaceBright = SheetLight,
    surfaceDim = Well2Light,
    surfaceContainerLowest = SheetLight,
    surfaceContainerLow = Color(0xFFF0F0EC),
    surfaceContainer = WellLight,
    surfaceContainerHigh = Color(0xFFE4E4DE),
    surfaceContainerHighest = Color(0xFFDDDDD6),
    outline = RuleStrongLight,
    outlineVariant = RuleLight,
    inverseSurface = InkLight,
    inverseOnSurface = PaperLight,
    scrim = Color.Black,
)

private val DarkColorScheme = darkColorScheme(
    primary = InkDark,
    onPrimary = PaperDark,
    primaryContainer = EnvelopeYellow,
    onPrimaryContainer = OnEnvelope,
    inversePrimary = InkLight,
    secondary = Ink2Dark,
    onSecondary = PaperDark,
    secondaryContainer = Well2Dark,
    onSecondaryContainer = InkDark,
    tertiary = GreenDark,
    onTertiary = Color(0xFF062012),
    tertiaryContainer = Color(0xFF173A26),
    onTertiaryContainer = Color(0xFFC6F0D4),
    error = RedDark,
    onError = Color(0xFF1A0505),
    errorContainer = RedWashDark,
    onErrorContainer = OnRedWashDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = WellDark,
    onSurfaceVariant = Ink2Dark,
    surfaceTint = Color.Transparent,
    surfaceBright = Well2Dark,
    surfaceDim = Color(0xFF0F0E0D),
    surfaceContainerLowest = Color(0xFF0F0E0D),
    surfaceContainerLow = Color(0xFF1A1917),
    surfaceContainer = SheetDark,
    surfaceContainerHigh = WellDark,
    surfaceContainerHighest = Well2Dark,
    outline = RuleStrongDark,
    outlineVariant = RuleDark,
    inverseSurface = InkDark,
    inverseOnSurface = PaperDark,
    scrim = Color.Black,
)

/** Colours Material has no role for: film, edge print, the envelope, lamps, washes. */
@Immutable
data class PherryColors(
    val paper: Color,
    val sheet: Color,
    val well: Color,
    val well2: Color,
    val rule: Color,
    val ruleStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val film: Color,
    val film2: Color,
    val filmRule: Color,
    val filmInk: Color,
    val edge: Color,
    val envelope: Color,
    val envelopePressed: Color,
    val onEnvelope: Color,
    val onEnvelope2: Color,
    val red: Color,
    val redWash: Color,
    val onRedWash: Color,
    val green: Color,
    val isDark: Boolean,
)

private fun pherryColors(dark: Boolean, scheme: ColorScheme, dynamic: Boolean) = PherryColors(
    paper = scheme.surface,
    sheet = scheme.surfaceContainerLowest.takeIf { !dark } ?: scheme.surfaceContainer,
    well = scheme.surfaceVariant,
    well2 = scheme.secondaryContainer,
    rule = scheme.outlineVariant,
    ruleStrong = scheme.outline,
    ink = scheme.onSurface,
    ink2 = scheme.onSurfaceVariant,
    ink3 = if (dark) Ink3Dark else Ink3Light,
    film = if (dark) FilmDark else FilmLight,
    film2 = Film2,
    filmRule = FilmRule,
    filmInk = FilmInk,
    edge = EdgePrint,
    // With wallpaper colours on, the envelope follows the wallpaper's primary container.
    envelope = if (dynamic) scheme.primaryContainer else EnvelopeYellow,
    envelopePressed = if (dynamic) scheme.primaryContainer else EnvelopeYellowPressed,
    onEnvelope = if (dynamic) scheme.onPrimaryContainer else OnEnvelope,
    onEnvelope2 = if (dynamic) scheme.onPrimaryContainer.copy(alpha = 0.78f) else OnEnvelope2,
    red = scheme.error,
    redWash = scheme.errorContainer,
    onRedWash = scheme.onErrorContainer,
    green = if (dark) GreenDark else GreenLight,
    isDark = dark,
)

val LocalPherryColors = staticCompositionLocalOf { pherryColors(false, LightColorScheme, false) }

@Composable
fun PherryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    // Wallpaper colours replace only the envelope (primary container). Paper, ink, rules and the
    // safelight red stay Pherry's, as the Settings switch promises.
    val colorScheme = if (isDynamic) {
        val context = LocalContext.current
        val dyn = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        base.copy(
            primaryContainer = dyn.primaryContainer,
            onPrimaryContainer = dyn.onPrimaryContainer,
            inversePrimary = dyn.inversePrimary,
        )
    } else {
        base
    }
    CompositionLocalProvider(
        LocalPherryColors provides pherryColors(darkTheme, colorScheme, isDynamic),
        LocalPherryText provides PherryTextStyles(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = PherryShapes,
            content = content,
        )
    }
}

/** Accessors: `PherryTheme.colors.film`, `PherryTheme.text.edge`. */
object PherryTheme {
    val colors: PherryColors
        @Composable @ReadOnlyComposable get() = LocalPherryColors.current
    val text: PherryTextStyles
        @Composable @ReadOnlyComposable get() = LocalPherryText.current
}
