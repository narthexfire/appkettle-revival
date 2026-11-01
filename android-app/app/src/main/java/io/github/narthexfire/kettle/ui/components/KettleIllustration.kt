package io.github.narthexfire.kettle.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.narthexfire.kettle.data.DeviceFacts
import io.github.narthexfire.kettle.ui.theme.K
import io.github.narthexfire.kettle.ui.theme.KettleColors
import io.github.narthexfire.kettle.ui.theme.Manrope
import io.github.narthexfire.kettle.ui.theme.lerpColor
import kotlin.math.max
import kotlin.math.min

enum class KettleMode { Standby, Heating, Ready, KeepWarm, OffBase, Unknown }

/** Kettle geometry and level maths (spec section 5), in the 260-unit body space. */
object KettleGeometry {
    const val BODY = "M70,226 Q56,226 57,212 L72,76 Q74,62 88,62 L172,62 Q186,62 188,76 L203,212 Q204,226 190,226 Z"
    const val Y_BOTTOM = 226f
    const val Y_TOP = 70f
    const val W_EMPTY = 224f
    const val W_MAX = 84f
    const val VB_X = -44f
    const val VB_Y = 20f
    const val VB_W = 304f
    const val VB_H = 236f

    fun waterY(ml: Int): Float =
        W_EMPTY - (ml.toFloat() / DeviceFacts.CAPACITY_ML).coerceIn(0f, 1f) * (W_EMPTY - W_MAX)

    fun leftEdge(y: Float) = 72f - (y - 76f) * 15f / 136f
    fun rightEdge(y: Float) = 188f + (y - 76f) * 15f / 136f
    /** "Empty" at 0 ml and "MAX+" above the 1.7 L capacity. */
    fun fmtMl(ml: Int) = when {
        ml <= 0 -> "Empty"
        ml > DeviceFacts.CAPACITY_ML -> "MAX+"
        ml >= 1000 -> "\u2248" + String.format("%.1f L", ml / 1000f)
        else -> "\u2248$ml ml"
    }
}


private fun svg(d: String): Path = PathParser().parsePathString(d).toPath()

private fun wavePath(amp: Float, closed: Boolean): Path {
    val p = Path()
    p.moveTo(-260f, 0f)
    var x = -260f
    var i = 0
    while (x < 520f) {
        p.quadraticTo(x + 15f, if (i % 2 == 0) -amp * 2 else amp * 2, x + 30f, 0f)
        x += 30f; i++
    }
    if (closed) {
        p.lineTo(520f, 190f); p.lineTo(-260f, 190f); p.close()
    }
    return p
}

private object Paths {
    val body = svg(KettleGeometry.BODY)
    val handle = svg("M182,86 C222,86 234,102 232,138 C230,178 222,196 197,201")
    val handleHi = svg("M190,92 C220,94 226,108 224,138")
    val spout = svg("M78,80 L42,63 Q33,60 33,68 Q34,73 39,77 L74,114 Z")
    val spoutEdge = svg("M42,63 Q33,60 33,68")
    val steam = listOf(svg("M0,10 C-6,0 6,-8 0,-18"), svg("M10,12 C4,2 16,-6 10,-16"), svg("M-10,12 C-16,2 -4,-6 -10,-16"))
    val lid = svg("M84,63 Q88,44 130,42 Q172,44 176,63 Z")
    val lidHi = svg("M96,54 Q110,47 130,46")
    val waveA = wavePath(4f, true)
    val waveB = wavePath(3f, true)
    val line = wavePath(2.5f, false)
}

/**
 * What the picture shows in each state. The water is always drawn at its real level and its
 * color depends ONLY on the temperature (blue cold -> red hot); heating is shown by rising bubbles,
 * steam and a pulsing base light, never by a color change.
 */
data class KettleVisuals(
    val showWater: Boolean,
    val waterTop: Float,
    val fillArgb: Int,
    val bubbles: Boolean,
    /** 0 = none, 1 = one small steady wisp (keep warm), 3 = full steam. */
    val steamWisps: Int,
    val ledOn: Boolean,
    val ledPulse: Boolean,
) {
    companion object {
        fun of(mode: KettleMode, temperature: Int, waterMl: Int, boilPoint: Int): KettleVisuals {
            val water = mode != KettleMode.OffBase && mode != KettleMode.Unknown
            return KettleVisuals(
                showWater = water,
                waterTop = KettleGeometry.waterY(waterMl),
                fillArgb = io.github.narthexfire.kettle.ui.theme.WaterRamp.argb(temperature.toFloat(), boilPoint),
                bubbles = water && mode == KettleMode.Heating,
                steamWisps = when {
                    mode == KettleMode.KeepWarm -> 1
                    mode == KettleMode.Heating && temperature > 60 -> 3
                    mode == KettleMode.Ready && temperature >= 70 -> 3
                    else -> 0
                },
                ledOn = mode == KettleMode.Heating || mode == KettleMode.KeepWarm,
                ledPulse = mode == KettleMode.Heating,
            )
        }
    }
}

private val BUBBLES = listOf(96f to 0f, 118f to .9f, 142f to 1.8f, 160f to .4f, 108f to 2.6f, 176f to 1.3f, 130f to 3.1f)

@Composable
fun KettleIllustration(
    mode: KettleMode,
    temperature: Int,
    waterMl: Int,
    boilPoint: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    cupMl: Int = io.github.narthexfire.kettle.data.Cups.DEFAULT_ML,
) {
    val c = K.c
    val offBase = mode == KettleMode.OffBase
    val v = KettleVisuals.of(mode, temperature, waterMl, boilPoint)
    val wy = v.waterTop

    // The water level follows new readings gently; the color follows the temperature over 400 ms.
    val waterAnim = remember { Animatable(wy) }
    LaunchedEffect(wy) { waterAnim.animateTo(wy, tween(400)) }
    val fill by animateColorAsState(Color(v.fillArgb), tween(400), label = "fill")
    val waterAlpha by animateFloatAsState(if (v.showWater) 1f else 0f, tween(400), label = "wa")
    val bubbleAlpha by animateFloatAsState(if (v.bubbles) 1f else 0f, tween(400), label = "ba")

    val inf = rememberInfiniteTransition(label = "kettle")
    val waveA by inf.animateFloat(0f, 60f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "wa")
    val waveB by inf.animateFloat(0f, 60f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "wb")
    val cycle by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart), label = "cy")
    val pa = if (reduceMotion) 0f else -waveA
    val pb = if (reduceMotion) 0f else waveB - 60f
    val cyc = if (reduceMotion) 0f else cycle
    val ledPulse by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "led")

    val measurer = rememberTextMeasurer()

    Canvas(modifier) {
        val s = min(size.width / KettleGeometry.VB_W, size.height / KettleGeometry.VB_H)
        val ox = (size.width - KettleGeometry.VB_W * s) / 2f
        val oy = (size.height - KettleGeometry.VB_H * s) / 2f
        fun px(x: Float) = ox + (x - KettleGeometry.VB_X) * s
        fun py(y: Float) = oy + (y - KettleGeometry.VB_Y) * s

        withTransform({
            translate(ox, oy)
            scale(s, s, pivot = Offset.Zero)
            translate(-KettleGeometry.VB_X, -KettleGeometry.VB_Y)
        }) {
            drawStatic(c, offBase)
            if (v.steamWisps > 0) drawSteam(c, if (reduceMotion) 0.35f else cyc, v.steamWisps, steady = reduceMotion)

            // Body
            val bodyBrush = Brush.horizontalGradient(
                0f to c.kBody2, .28f to c.kBody1, .62f to c.kBody1, 1f to c.kBody2, startX = 57f, endX = 204f,
            )
            drawPath(Paths.body, bodyBrush, alpha = if (offBase) .45f else 1f)
            if (offBase) {
                drawPath(Paths.body, c.ink3, style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))))
            } else {
                drawPath(Paths.body, c.kEdge, style = Stroke(1f))
            }

            clipPath(Paths.body) {
                if (!offBase && waterAlpha > 0.01f) {
                    val t = waterAnim.value
                    val topC = lerpColor(fill, Color.White, .22f)
                    val botC = lerpColor(fill, Color.Black, .12f)
                    translate(pb, t) { drawPath(Paths.waveB, Brush.verticalGradient(0f to topC, 1f to botC, startY = -6f, endY = 104f), alpha = .45f) }
                    translate(pa, t + 3f) { drawPath(Paths.waveA, Brush.verticalGradient(0f to topC, 1f to botC, startY = -11f, endY = 102f), alpha = .92f) }
                    // Waterline riding the meniscus (water mode only)
                    if (waterAlpha > 0.01f) {
                        val w = waterAnim.value
                        translate(pa, w + 1.6f) { drawPath(Paths.line, c.waterEdge, alpha = .7f * waterAlpha, style = Stroke(1.2f)) }
                        translate(pa, w) { drawPath(Paths.line, c.waterLine, alpha = waterAlpha, style = Stroke(2.6f)) }
                    }
                    if (bubbleAlpha > 0.01f) {
                        // Heating: gentle bubbles rise through the whole depth of the water (static with reduced motion).
                        val w = waterAnim.value
                        clipRect(-50f, w + 3f, 310f, 260f) {
                            BUBBLES.forEachIndexed { i, (bx, d) ->
                                val ph = if (reduceMotion) (0.15f + i * 0.11f) % 1f else ((cyc * 2f) + d / 3.6f) % 1f // 3.6 s cycle
                                val y = 220f - ph * (220f - (w + 6f))
                                val a = (if (ph < .2f) ph / .2f else 1f - (ph - .2f) / .8f) * .55f * bubbleAlpha
                                drawCircle(Color.White, radius = 1.6f + (i % 3) * .6f, center = Offset(bx, y), alpha = a)
                            }
                        }
                    }
                }
                drawRect(
                    Brush.horizontalGradient(.7f to Color.Black.copy(alpha = 0f), 1f to Color.Black.copy(alpha = .16f), startX = 0f, endX = 260f),
                    Offset.Zero, Size(260f, 260f),
                )
                drawLine(
                    Brush.horizontalGradient(0f to Color.White.copy(alpha = 0f), .5f to Color.White.copy(alpha = .55f), 1f to Color.White.copy(alpha = 0f), startX = 69f, endX = 93f),
                    Offset(86f, 72f), Offset(76f, 210f), strokeWidth = 14f, cap = StrokeCap.Round, alpha = .7f,
                )
                drawLine(c.kHi, Offset(98f, 70f), Offset(94f, 120f), strokeWidth = 3f, cap = StrokeCap.Round)
            }

            // MIN / MAX ticks
            val minY = KettleGeometry.waterY(DeviceFacts.MIN_ML)
            // Every tick has a card-colored outline so it reads on white, pink, red and empty.
            for (y in listOf(KettleGeometry.W_MAX, minY)) {
                val r = KettleGeometry.rightEdge(y)
                drawLine(c.card, Offset(r - 11f, y), Offset(r - 1f, y), strokeWidth = 4.4f, cap = StrokeCap.Round)
                drawLine(c.ink, Offset(r - 11f, y), Offset(r - 1f, y), strokeWidth = 2.2f, cap = StrokeCap.Round)
            }
            // Cup ticks at 2-6 cups, like the gauge on the kettle (2 cups = 0.5 L ... 6 cups = 1.5 L).
            for (n in 2..6) {
                val ml = n * cupMl
                if (ml >= DeviceFacts.CAPACITY_ML) continue
                val y = KettleGeometry.waterY(ml)
                val r = KettleGeometry.rightEdge(y)
                drawLine(c.card, Offset(r - 7f, y), Offset(r - 1f, y), strokeWidth = 3.6f, cap = StrokeCap.Round)
                drawLine(c.ink2, Offset(r - 7f, y), Offset(r - 1f, y), strokeWidth = 1.8f, cap = StrokeCap.Round)
            }
            // Level tick
            if (waterAlpha > 0.01f) {
                val w = waterAnim.value
                val lx = KettleGeometry.leftEdge(w)
                drawLine(c.waterLabel, Offset(lx - 10f, w), Offset(lx + 3f, w), strokeWidth = 2f, cap = StrokeCap.Round, alpha = waterAlpha)
            }
            // Lid, knob, base, LED
            drawPath(Paths.lid, c.kDark)
            drawPath(Paths.lidHi, c.kDark2, style = Stroke(2.5f, cap = StrokeCap.Round))
            drawRoundRect(c.kDark, Offset(116f, 31f), Size(28f, 13f), CornerRadius(6.5f))
            drawRoundRect(c.kDark, Offset(50f, 225f), Size(160f, 15f), CornerRadius(7.5f))
            if (v.ledOn) {
                // Heating: a soft pulsing glow; keep warm: steady.
                val a = if (v.ledPulse && !reduceMotion) ledPulse else 1f
                drawCircle(Color(0xFFE5483D), 7f, Offset(130f, 232.5f), alpha = 0.28f * a)
                drawCircle(Color(0xFFE5483D), 2.6f, Offset(130f, 232.5f), alpha = 0.55f + 0.45f * a)
            } else {
                drawCircle(c.kDark2, 2.6f, Offset(130f, 232.5f))
            }
        }

        // Text is drawn unscaled so it stays crisp. 12 sp floor.
        fun textSize(px: Float) = maxOf((px * 0.7f).toSp().value, 12f).sp
        val markStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = textSize(15f * s), color = c.ink)
        val minY = KettleGeometry.waterY(DeviceFacts.MIN_ML)
        // Gauge labels as printed on the kettle: MAX 1.7 L and 1.0 L, plus MIN. ("0.5 L" keeps only its
        // tick: its label crowded MIN and sat on the waterline at the usual ~0.5 L fill.)
        for ((label, y) in listOf(
            "MAX 1.7 L" to KettleGeometry.W_MAX, "1.0 L" to KettleGeometry.waterY(1000), "MIN" to minY,
        )) {
            val lay = measurer.measure(label, markStyle)
            val x = px(KettleGeometry.rightEdge(y) - 15f) - lay.size.width
            val top = py(y) - lay.size.height / 2f
            // A solid chip (card color, 90 %) behind each scale label keeps it legible over any water.
            val padH = 3.dp.toPx(); val padV = 0.5.dp.toPx()
            drawRoundRect(
                c.card.copy(alpha = 0.9f), topLeft = Offset(x - padH, top - padV),
                size = Size(lay.size.width + 2 * padH, lay.size.height + 2 * padV), cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawText(lay, topLeft = Offset(x, top))
        }
        if (waterAlpha > 0.01f) {
            val w = waterAnim.value
            val labelStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = textSize(22f * s), color = c.waterLabel, fontFeatureSettings = "tnum")
            // The ml line is centered on the waterline tick; the cups line (1.5x, semibold) sits above it.
            val lay = measurer.measure(KettleGeometry.fmtMl(waterMl), labelStyle)
            val x = px(KettleGeometry.leftEdge(w) - 14f) - lay.size.width
            val mlTop = py(w) - lay.size.height / 2f
            drawText(lay, topLeft = Offset(max(0f, x), mlTop), alpha = waterAlpha)
            val cupStyle = labelStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = labelStyle.fontSize * 1.5f)
            if (waterMl in 1..DeviceFacts.CAPACITY_ML) {
                val cups = measurer.measure(io.github.narthexfire.kettle.data.Cups.format(waterMl, cupMl), cupStyle)
                val cx = px(KettleGeometry.leftEdge(w) - 14f) - cups.size.width
                drawText(cups, topLeft = Offset(max(0f, cx), mlTop - cups.size.height + 2.dp.toPx()), alpha = waterAlpha)
            }
        }
    }
}

private fun DrawScope.drawStatic(c: KettleColors, offBase: Boolean) {
    drawOval(c.kShadow, Offset(130f - 98f, 244f - 7f), Size(196f, 14f))
    drawPath(Paths.handle, c.kDark, style = Stroke(16f, cap = StrokeCap.Round))
    drawPath(Paths.handleHi, c.kDark2, alpha = .8f, style = Stroke(3f, cap = StrokeCap.Round))
    drawPath(Paths.spout, c.kBody2, alpha = if (offBase) .45f else 1f)
    drawPath(Paths.spoutEdge, c.kEdge, style = Stroke(2f))
}

/** Three wisps above the spout, each rising 30 and fading over 2.4 s, offset by 0.8 s. */
private fun DrawScope.drawSteam(c: KettleColors, cycle: Float, wisps: Int = 3, steady: Boolean = false) {
    translate(34f, 46f) {
        Paths.steam.take(wisps).forEachIndexed { i, p ->
            val ph = if (steady) 0.35f else ((cycle * 3f) - i / 3f).mod(1f)
            val a = if (ph < .3f) ph / .3f * .8f else (1f - (ph - .3f) / .7f) * .8f
            translate(0f, -30f * ph) { drawPath(p, c.ink3, alpha = a, style = Stroke(3f, cap = StrokeCap.Round)) }
        }
    }
}
