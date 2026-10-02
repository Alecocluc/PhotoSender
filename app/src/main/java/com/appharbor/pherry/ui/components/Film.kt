package com.appharbor.pherry.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ── Develop ────────────────────────────────────────────────────────────────

private val NegativeMatrix = floatArrayOf(
    -0.75f, 0f, 0f, 0f, 0.75f * 255f + 18f,
    0f, -0.54f, 0f, 0f, 0.54f * 255f + 8f,
    0f, 0f, -0.32f, 0f, 0.32f * 255f,
    0f, 0f, 0f, 1f, 0f,
)
private val IdentityMatrix = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

/**
 * Colour filter for a frame being developed: 0 is the orange negative, 1 the finished print.
 * Returns null at 1 so finished frames draw without a filter.
 */
fun developFilter(progress: Float): ColorFilter? {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 0.999f) return null
    // Ease so most of the change happens late, as an image does in the tray.
    val t = p * p * (3 - 2 * p)
    val m = FloatArray(20) { i -> NegativeMatrix[i] + (IdentityMatrix[i] - NegativeMatrix[i]) * t }
    return ColorFilter.colorMatrix(ColorMatrix(m))
}

// ── Frame ──────────────────────────────────────────────────────────────────

/**
 * One photograph on film. Selection is a yellow grease-pencil ring with its order number drawn on
 * the picture, never a chrome overlay. [develop] runs 0→1 from negative to print.
 */
@Composable
fun Frame(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 1f,
    isVideo: Boolean = false,
    selectedNumber: Int? = null,
    develop: Float = 1f,
    failed: Boolean = false,
) {
    val colors = PherryTheme.colors
    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .clip(PherryShape.frame)
            .background(colors.film2),
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            colorFilter = developFilter(develop),
            modifier = Modifier.fillMaxSize(),
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(PherryShape.frame)
                    .background(Color(0xB80B0A09)),
                contentAlignment = Alignment.Center,
            ) {
                PhIcon(Ph.Play, contentDescription = null, tint = Color.White, size = 12.dp)
            }
        }
        if (failed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(PherryShape.frame)
                    .background(colors.red),
                contentAlignment = Alignment.Center,
            ) {
                PhIcon(Ph.XBold, contentDescription = "Failed", tint = Color.White, size = 12.dp)
            }
        }
        GreaseRing(visible = selectedNumber != null, number = selectedNumber)
    }
}

/**
 * A yellow grease-pencil ring around the picture, drawn on when it appears, with the pick's number.
 * The shape is seeded by [number], so a pick always gets the same ring and its neighbours differ.
 */
@Composable
fun BoxScope.GreaseRing(visible: Boolean, number: Int?) {
    val colors = PherryTheme.colors
    val drawn = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        if (visible) drawn.animateTo(1f, tween(240)) else drawn.snapTo(0f)
    }
    // Recompose only when the ring starts and when the number is due; the stroke itself just redraws.
    val inked by remember { derivedStateOf { drawn.value > 0f } }
    val numbered by remember { derivedStateOf { drawn.value > 0.9f } }
    if (!inked) return
    val seed = number ?: 0
    Spacer(
        Modifier
            .matchParentSize()
            .drawWithCache {
                // Built once per size and pick. Per frame only the stretch drawn so far is cut again.
                val measure = PathMeasure().apply { setPath(greaseRingPath(size, seed), false) }
                val length = measure.length
                val width = 1.75.dp.toPx()
                val halo = 1.dp.toPx()
                val whole = Array(PenPasses.size) { k ->
                    Path().also { measure.getSegment(length * PenPasses[k].from, length * PenPasses[k].to, it, true) }
                }
                val partial = Array(PenPasses.size) { Path() }
                val shown = arrayOfNulls<Path>(PenPasses.size)
                val wax = Array(PenPasses.size) { k -> Stroke(width * PenPasses[k].weight, cap = StrokeCap.Round) }
                val shadow = Array(PenPasses.size) { k -> Stroke(width * PenPasses[k].weight + halo, cap = StrokeCap.Round) }
                val badgeAt = Offset(4.dp.toPx(), 4.dp.toPx())
                val badge = Size(18.dp.toPx(), 18.dp.toPx())
                val badgeCorner = CornerRadius(2.dp.toPx())
                onDrawBehind {
                    val progress = drawn.value
                    val reach = length * progress
                    for (k in PenPasses.indices) {
                        val pass = PenPasses[k]
                        shown[k] = when {
                            progress >= 1f -> whole[k]
                            reach <= length * pass.from -> null
                            else -> partial[k].apply {
                                rewind()
                                measure.getSegment(length * pass.from, minOf(reach, length * pass.to), this, true)
                            }
                        }
                    }
                    // Every shadow goes down first, so where the tail crosses the start it is wax on wax.
                    // The nested shadows stack into a soft edge that keeps the ring readable on light photos.
                    for (k in PenPasses.indices) shown[k]?.let { drawPath(it, GreaseHalo, style = shadow[k]) }
                    for (k in PenPasses.indices) shown[k]?.let { drawPath(it, colors.envelope, style = wax[k]) }
                    if (number != null && progress > 0.9f) {
                        drawRoundRect(colors.envelope, topLeft = badgeAt, size = badge, cornerRadius = badgeCorner)
                    }
                }
            },
    )
    if (number != null && numbered) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .size(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (number > 99) "99" else number.toString(),
                style = PherryTheme.text.edge,
                color = colors.onEnvelope,
                maxLines = 1,
            )
        }
    }
}

private val Tau = (2 * PI).toFloat()
private val GreaseHalo = Color(0x2E000000)

/** One pass of the pen: it covers [from]..[to] of the stroke's length at [weight] of the full width. */
private class PenPass(val from: Float, val to: Float, val weight: Float)

/**
 * Pen weight along the ring as nested passes. Round caps blend the steps into a taper: the pen
 * lands quickly, presses hardest round the middle and lifts slowly through the tail.
 */
private val PenPasses = arrayOf(
    PenPass(0f, 1f, 0.42f),
    PenPass(0.015f, 0.94f, 0.62f),
    PenPass(0.04f, 0.87f, 0.82f),
    PenPass(0.08f, 0.79f, 1f),
)

/**
 * The ring's centre line, the way a china marker draws it: a slightly tilted oval with a lumpy
 * radius that lands a little outside where it starts, comes round inside that start, crosses it and
 * drifts outward before it lifts. Every choice comes from [seed], so the same pick draws the same ring.
 */
private fun greaseRingPath(size: Size, seed: Int): Path {
    val rnd = Random(seed)
    fun pick(from: Float, until: Float) = from + (until - from) * rnd.nextFloat()
    val tilt = pick(-7f, -3f) * Tau / 360f
    val start = pick(-1.45f, -1.1f) // a little right of twelve o'clock, going clockwise
    val overshoot = pick(0.65f, 0.85f) // how far past the start the tail runs, in radians
    val landing = pick(0.08f, 0.10f) // how far outside the line the pen lands, as a share of the radius
    val leadIn = 0.8f
    val lift = pick(0.11f, 0.14f) // how far outside the line the tail ends
    val liftFrom = Tau - 0.3f
    // Three low harmonics with their own phases keep the radius from ever being tidy: about 5–8% in all.
    val a2 = pick(0.028f, 0.038f)
    val p2 = pick(0f, Tau)
    val a3 = pick(0.018f, 0.026f)
    val p3 = pick(0f, Tau)
    val a4 = pick(0.008f, 0.014f)
    val p4 = pick(0f, Tau)
    val rx = size.width * 0.435f
    val ry = size.height * pick(0.405f, 0.425f)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val cosTilt = cos(tilt)
    val sinTilt = sin(tilt)
    val sweep = Tau + overshoot
    val steps = 96
    val path = Path()
    for (i in 0..steps) {
        val t = sweep * i / steps
        val wobble = a2 * sin(2 * t + p2) + a3 * sin(3 * t + p3) + a4 * sin(4 * t + p4)
        val landed = (1f - t / leadIn).coerceAtLeast(0f)
        val lifted = ((t - liftFrom) / (sweep - liftFrom)).coerceIn(0f, 1f)
        val r = 1f + wobble + landing * landed * landed + lift * lifted * lifted
        val ex = rx * r * cos(start + t)
        val ey = ry * r * sin(start + t)
        val x = cx + ex * cosTilt - ey * sinTilt
        val y = cy + ex * sinTilt + ey * cosTilt
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

// ── Strips ─────────────────────────────────────────────────────────────────

/** Tiny condensed mono caps in edge-print orange (or film ink when [dim]). */
@Composable
fun EdgeText(
    text: String,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
) {
    val colors = PherryTheme.colors
    Text(
        text = text.uppercase(),
        style = PherryTheme.text.edge,
        color = if (dim) colors.filmInk.copy(alpha = 0.75f) else colors.edge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Sprocket holes along a strip's edge. Only on standalone strips, never inside dense sheets. */
@Composable
fun Sprockets(modifier: Modifier = Modifier) {
    val colors = PherryTheme.colors
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val w = 6.dp.toPx()
        val h = 4.dp.toPx()
        val step = 13.dp.toPx()
        var x = step / 2
        while (x < size.width) {
            drawRoundRect(colors.filmRule, topLeft = Offset(x - w / 2, (size.height - h) / 2), size = Size(w, h), cornerRadius = CornerRadius(1.dp.toPx()))
            x += step
        }
    }
}

/**
 * A strip of film [columns] frames wide. Each frame gets an edge-print label above it and an
 * optional mark below it (e.g. a tick when it is already on the computer). Edge print is hidden
 * from accessibility: each frame's own description carries what it says.
 */
@Composable
fun FilmRow(
    count: Int,
    columns: Int,
    modifier: Modifier = Modifier,
    sprockets: Boolean = false,
    gap: Dp = 4.dp,
    edgeTop: @Composable (Int) -> Unit = {},
    edgeBottom: (@Composable (Int) -> Unit)? = null,
    frame: @Composable (Int) -> Unit,
) {
    val colors = PherryTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.film)
            .padding(horizontal = 6.dp),
    ) {
        if (sprockets) Sprockets()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (i in 0 until columns) {
                Box(Modifier.weight(1f).heightIn(min = 18.dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) {
                    if (i < count) edgeTop(i)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (i in 0 until columns) {
                Box(Modifier.weight(1f)) { if (i < count) frame(i) }
            }
        }
        if (edgeBottom != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (i in 0 until columns) {
                    Box(Modifier.weight(1f).heightIn(min = 18.dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) {
                        if (i < count) edgeBottom(i)
                    }
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
        }
        if (sprockets) Sprockets()
    }
}

/** A horizontally scrolling strip for short runs (recently sent, shared items). */
@Composable
fun ScrollingStrip(
    count: Int,
    modifier: Modifier = Modifier,
    frameWidth: Dp = 104.dp,
    edgeTop: @Composable (Int) -> Unit = {},
    frame: @Composable (Int) -> Unit,
) {
    val colors = PherryTheme.colors
    Column(modifier.fillMaxWidth().background(colors.film)) {
        Sprockets()
        LazyRow(
            contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(count) { i ->
                Column(Modifier.width(frameWidth)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 18.dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) { edgeTop(i) }
                    frame(i)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Sprockets()
    }
}

/** A frame number as edge print: "12▸". */
@Composable
fun FrameNumber(number: Int, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        EdgeText(number.toString())
        PhIcon(Ph.CaretRightBold, contentDescription = null, tint = PherryTheme.colors.edge, size = 8.dp)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            EdgeText(trailing, dim = true)
        }
    }
}
