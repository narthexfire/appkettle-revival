package io.github.narthexfire.kettle.ui.theme

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Water color ("blue when cold, red when hot"):
 *  - solid water-blue up to 40 C;
 *  - 40-75 C: a short blend by lightness, blue -> pale -> soft red (never through purple);
 *  - 75 C -> boil point: soft red brightening to BRIGHT red at the measured boil point (lower at altitude, else 100).
 * Blends are straight lines in OKLab, so they stay clean (no muddy grays, no hue detour).
 * While heating, the overlay is always [RED].
 */
object WaterRamp {
    const val BLUE = 0xFF4A90D9.toInt()     // <= 40 C
    const val PALE = 0xFFEEF1F5.toInt()     // midpoint of the 40-75 blend
    const val SOFT_RED = 0xFFD2605A.toInt() // 75 C
    const val RED = 0xFFE53935.toInt()      // >= measured boil point; also the heating overlay
    const val BLUE_UNTIL = 40f
    const val RED_FROM = 75f

    /** ARGB for water at [t] C, with [boilPoint] the measured boil point (or 100). */
    fun argb(t: Float, boilPoint: Int): Int {
        val mid = (BLUE_UNTIL + RED_FROM) / 2f
        val top = maxOf(boilPoint.toFloat(), RED_FROM + 1f)
        return when {
            t <= BLUE_UNTIL -> BLUE
            t <= mid -> mixOklab(BLUE, PALE, (t - BLUE_UNTIL) / (mid - BLUE_UNTIL))
            t <= RED_FROM -> mixOklab(PALE, SOFT_RED, (t - mid) / (RED_FROM - mid))
            t >= top -> RED
            else -> mixOklab(SOFT_RED, RED, (t - RED_FROM) / (top - RED_FROM))
        }
    }

    fun mixOklab(a: Int, b: Int, f: Float): Int {
        val x = toOklab(a); val y = toOklab(b); val k = f.coerceIn(0f, 1f).toDouble()
        return fromOklab(x[0] + (y[0] - x[0]) * k, x[1] + (y[1] - x[1]) * k, x[2] + (y[2] - x[2]) * k)
    }

    /* ---- OKLab / OKLCH (Björn Ottosson) ---- */

    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    private fun gam(v: Double): Int {
        val x = if (v <= 0.0031308) 12.92 * v else 1.055 * v.coerceAtLeast(0.0).pow(1 / 2.4) - 0.055
        return (x.coerceIn(0.0, 1.0) * 255).roundToInt()
    }

    fun toOklab(argb: Int): DoubleArray {
        val r = lin((argb shr 16) and 255); val g = lin((argb shr 8) and 255); val bl = lin(argb and 255)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * bl)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * bl)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * bl)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    /** [L, C, h(deg)]. */
    fun toOklch(argb: Int): DoubleArray {
        val (L, A, B) = toOklab(argb).let { Triple(it[0], it[1], it[2]) }
        var h = Math.toDegrees(atan2(B, A)); if (h < 0) h += 360.0
        return doubleArrayOf(L, sqrt(A * A + B * B), h)
    }

    fun fromOklab(L: Double, A: Double, B: Double): Int {
        val l = (L + 0.3963377774 * A + 0.2158037573 * B).pow(3)
        val m = (L - 0.1055613458 * A - 0.0638541728 * B).pow(3)
        val s = (L - 0.0894841775 * A - 1.2914855480 * B).pow(3)
        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return (0xFF shl 24) or (gam(r) shl 16) or (gam(g) shl 8) or gam(b)
    }
}
