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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    inversePrimary = InversePrimaryLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    inversePrimary = InversePrimaryDark,
)

@Immutable
data class ExtendedColors(
    val primaryFixed: Color = Color.Unspecified,
    val primaryFixedDim: Color = Color.Unspecified,
    val tertiaryFixed: Color = Color.Unspecified,
    val tertiaryFixedDim: Color = Color.Unspecified,
    val progressGradient: Brush = Brush.horizontalGradient(listOf(Color.Unspecified, Color.Unspecified)),
    val buttonGradient: Brush = Brush.horizontalGradient(listOf(Color.Unspecified, Color.Unspecified)),
    val progressGlowColor: Color = Color.Unspecified,
)

val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors() }

// Pherry signature gradient — teal "ferry/flow", consistent across all modes.
private val PherryButtonGradient = Brush.horizontalGradient(
    listOf(PrimaryLight, PrimaryContainerLight)  // #0E9E8E → #13B5A6
)
private val PherryProgressGradient = Brush.horizontalGradient(
    listOf(PrimaryLight, SecondaryLight)  // #0E9E8E → #119EB0
)

private fun buildExtendedColors(
    colorScheme: ColorScheme,
    isDynamic: Boolean,
    isDark: Boolean,
): ExtendedColors {
    // When dynamic (Material You) is enabled, derive gradients from the
    // dynamic colorScheme so they blend with the wallpaper palette.
    // When dynamic is off, always use the Pherry signature gradient.
    val buttonGradient = if (isDynamic) {
        Brush.horizontalGradient(
            listOf(colorScheme.primary, colorScheme.primaryContainer)
        )
    } else {
        PherryButtonGradient
    }
    val progressGradient = if (isDynamic) {
        Brush.horizontalGradient(
            listOf(colorScheme.primary, colorScheme.secondary)
        )
    } else {
        PherryProgressGradient
    }
    val glowColor = if (isDynamic) colorScheme.primary else PrimaryLight

    return ExtendedColors(
        primaryFixed = PrimaryFixedLight,
        primaryFixedDim = PrimaryFixedDimLight,
        tertiaryFixed = TertiaryFixedLight,
        tertiaryFixedDim = TertiaryFixedDimLight,
        progressGradient = progressGradient,
        buttonGradient = buttonGradient,
        progressGlowColor = glowColor,
    )
}

@Composable
fun PherryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        isDynamic -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val extendedColors = buildExtendedColors(
        colorScheme = colorScheme,
        isDynamic = isDynamic,
        isDark = darkTheme,
    )

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = PherryShapes,
            content = content
        )
    }
}