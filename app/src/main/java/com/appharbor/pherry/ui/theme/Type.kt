package com.appharbor.pherry.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.appharbor.pherry.R

// Both faces ship inside the app (res/font): Archivo for every word, Martian Mono for edge print,
// codes and measurements. Variable axes give each role its own width without extra files.

private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

private fun martian(weight: Int, width: Float) = Font(
    R.font.martian_mono,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

/** Running text and controls. */
val ArchivoText = FontFamily(archivo(400, 100f), archivo(500, 100f), archivo(600, 100f), archivo(700, 100f))

/** Headlines and display numbers: a touch narrower, heavy. */
val ArchivoDisplay = FontFamily(archivo(700, 90f), archivo(800, 88f))

/** Printed form labels (the envelope's "ON PHONE", "BACKED UP"). */
val ArchivoCondensed = FontFamily(archivo(600, 75f), archivo(700, 75f))

/** The wordmark's own cut. */
val ArchivoWordmark = FontFamily(archivo(800, 82f))

/** Data: sizes, addresses, counts. */
val MartianText = FontFamily(martian(400, 87.5f), martian(500, 87.5f), martian(600, 87.5f))

/** Edge print on film. */
val MartianEdge = FontFamily(martian(500, 75f), martian(600, 75f))

/** Ticket numbers and the pairing code. */
val MartianCode = FontFamily(martian(600, 100f))

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(800), fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(800), fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-0.02).em),
    displaySmall = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(800), fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.015).em),
    headlineLarge = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(800), fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.015).em),
    headlineMedium = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(800), fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.01).em),
    headlineSmall = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(700), fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.005).em),
    titleLarge = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(700), fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(400), fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 20.sp, lineHeightStyle = Trim),
    labelMedium = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(600), fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(600), fontSize = 11.sp, lineHeight = 14.sp),
)

/** Pherry's own text roles beyond the Material scale. */
@Immutable
data class PherryTextStyles(
    /** Printed form label: condensed caps. */
    val formLabel: TextStyle = TextStyle(fontFamily = ArchivoCondensed, fontWeight = FontWeight(700), fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
    /** Edge print on film: tiny condensed mono caps. */
    val edge: TextStyle = TextStyle(fontFamily = MartianEdge, fontWeight = FontWeight(500), fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.08.em),
    /** Measurements and addresses. */
    val mono: TextStyle = TextStyle(fontFamily = MartianText, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 18.sp),
    /** A value printed on the envelope's order form ("4,212" under ON THIS PHONE). */
    val envelopeValue: TextStyle = TextStyle(fontFamily = MartianText, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 20.sp),
    /** Small data line under a title (counts, dates), set in caps by the caller. */
    val monoCaps: TextStyle = TextStyle(fontFamily = MartianEdge, fontWeight = FontWeight(500), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.em),
    /** Pairing code and ticket numbers. */
    val code: TextStyle = TextStyle(fontFamily = MartianCode, fontWeight = FontWeight(600), fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = 0.16.em),
    /** The wordmark. */
    val wordmark: TextStyle = TextStyle(fontFamily = ArchivoWordmark, fontWeight = FontWeight(800), fontSize = 23.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em),
)

val LocalPherryText = staticCompositionLocalOf { PherryTextStyles() }
